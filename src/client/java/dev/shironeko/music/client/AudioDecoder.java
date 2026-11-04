package dev.shironeko.music.client;

import io.nayuki.flac.common.*;
import io.nayuki.flac.decode.*;
import java.io.*;
import javazoom.jl.decoder.*;

/** Original samples only. FLAC integers retain up to 24 bits in normalized float PCM. No EQ. */
public final class AudioDecoder {
  public interface Sink {
    void format(int rate, int channels, int bits, String codec) throws Exception;

    void pcm(float[] data, int rate, int channels, long frame) throws Exception;
  }

  public static long decode(InputStream source, long length, long expectedMs, Sink sink)
      throws Exception {
    return decode(source, length, expectedMs, 0, null, sink);
  }

  public static long decode(
      InputStream source,
      long length,
      long expectedMs,
      long seekMs,
      AudioRepository.Ranges ranges,
      Sink sink)
      throws Exception {
    BufferedInputStream in = new BufferedInputStream(source, 32768);
    in.mark(4);
    byte[] magic = in.readNBytes(4);
    in.reset();
    if (magic.length == 4
        && magic[0] == 'f'
        && magic[1] == 'L'
        && magic[2] == 'a'
        && magic[3] == 'C') return flac(in, length, expectedMs, seekMs, ranges, sink);
    if (magic.length < 4
        || !((magic[0] == 'I' && magic[1] == 'D' && magic[2] == '3')
            || ((magic[0] & 255) == 255 && (magic[1] & 224) == 224)))
      throw new IOException("音源不是受支持的原始 FLAC/MP3，可能为加密格式");
    Bitstream bits = new Bitstream(in);
    Decoder decoder = new Decoder();
    Header header;
    long frame = 0;
    int rate = 44100;
    boolean first = true;
    while ((header = bits.readFrame()) != null) {
      if (first && length > 0 && length < Integer.MAX_VALUE) {
        double ms = header.total_ms((int) length);
        if (ms > 0 && expectedMs > 0 && ms + 2500 < expectedMs * .95)
          throw new IOException("音源仅为试听片段");
      }
      SampleBuffer pcm = (SampleBuffer) decoder.decodeFrame(header, bits);
      bits.closeFrame();
      rate = pcm.getSampleFrequency();
      int channels = pcm.getChannelCount();
      if (first) {
        sink.format(rate, channels, 16, "MP3 " + (header.bitrate() / 1000) + " kbps");
        first = false;
      }
      float[] samples = new float[pcm.getBufferLength()];
      for (int i = 0; i < samples.length; i++) samples[i] = pcm.getBuffer()[i] / 32768f;
      sink.pcm(samples, rate, channels, frame);
      frame += samples.length / channels;
    }
    verify(frame, rate, expectedMs);
    return frame * 1000 / rate;
  }

  private static long flac(
      InputStream source,
      long length,
      long expected,
      long seekMs,
      AudioRepository.Ranges ranges,
      Sink sink)
      throws Exception {
    var input = new StreamInput(source);
    if (input.readUint(32) != 0x664C6143) throw new IOException("FLAC 标头错误");
    StreamInfo info = null;
    SeekTable table = null;
    boolean last = false;
    while (!last) {
      last = input.readUint(1) != 0;
      int type = input.readUint(7), blockLength = input.readUint(24);
      if (type == 0) {
        if (blockLength != 34) throw new IOException("FLAC STREAMINFO长度错误");
        byte[] bytes = new byte[34];
        input.readFully(bytes);
        info = new StreamInfo(bytes);
      } else if (type == 3 && blockLength <= 65536) {
        byte[] bytes = new byte[blockLength];
        input.readFully(bytes);
        table = new SeekTable(bytes);
      } else {
        byte[] discard = new byte[4096];
        while (blockLength > 0) {
          int count = Math.min(blockLength, discard.length);
          if (count == discard.length) input.readFully(discard);
          else input.readFully(new byte[count]);
          blockLength -= count;
        }
      }
    }
    if (info == null || info.numChannels < 1 || info.numChannels > 2 || info.sampleDepth > 24)
      throw new IOException("暂不支持此 FLAC 声道数或超过24位的原始音源");
    if (info.numSamples > 0) verify(info.numSamples, info.sampleRate, expected);
    sink.format(info.sampleRate, info.numChannels, info.sampleDepth, "FLAC");
    var decoder = new io.nayuki.flac.decode.FrameDecoder(input, info.sampleDepth);
    int[][] pcm = new int[info.numChannels][65536];
    long frame = 0;
    FrameInfo header;
    InputStream ranged = null;
    FrameInfo first = null;
    if (seekMs > 5000 && ranges != null) {
      long target =
          Math.min(
              info.numSamples > 0 ? info.numSamples - 1 : Long.MAX_VALUE,
              seekMs * info.sampleRate / 1000);
      long[] point = null;
      if (table != null)
        for (var p : table.points)
          if (p.sampleOffset > 0
              && p.sampleOffset <= target
              && p.fileOffset >= 0
              && (point == null || p.sampleOffset > point[0]))
            point = new long[] {p.sampleOffset, p.fileOffset + input.getPosition()};
      if (point == null && length > 0)
        point = findFrame(ranges, input.getPosition(), length, target, info);
      if (point != null)
        try {
          ranged = ranges.open(point[1]);
          var alternate = new StreamInput(new BufferedInputStream(ranged, 32768));
          var candidate = new io.nayuki.flac.decode.FrameDecoder(alternate, info.sampleDepth);
          first = candidate.readFrame(pcm, 0);
          if (first == null || sample(first, info) != point[0] || point[0] > target)
            throw new IOException("FLAC定位帧不匹配");
          decoder = candidate;
          frame = point[0];
        } catch (Exception ignored) {
          if (ranged != null) ranged.close();
          ranged = null;
          first = null;
        }
    }
    try {
      float divisor = (float) Math.scalb(1.0, info.sampleDepth - 1);
      while ((info.numSamples == 0 || frame < info.numSamples)
          && (header = first != null ? first : decoder.readFrame(pcm, 0)) != null) {
        first = null;
        if (sample(header, info) != frame) throw new IOException("FLAC帧采样序号不连续");
        // Split large FLAC frames so the queue's memory accounting stays strict.
        for (int offset = 0; offset < header.blockSize; offset += 1024) {
          int count = Math.min(1024, header.blockSize - offset);
          float[] data = new float[count * info.numChannels];
          for (int i = 0; i < count; i++)
            for (int c = 0; c < info.numChannels; c++)
              data[i * info.numChannels + c] = pcm[c][offset + i] / divisor;
          sink.pcm(data, info.sampleRate, info.numChannels, frame + offset);
        }
        frame += header.blockSize;
      }
      if (info.numSamples > 0 && frame != info.numSamples) throw new IOException("FLAC实际采样数与标头不符");
      verify(frame, info.sampleRate, expected);
      return frame * 1000 / info.sampleRate;
    } finally {
      if (ranged != null) ranged.close();
    }
  }

  private static long sample(FrameInfo frame, StreamInfo info) {
    return frame.sampleOffset >= 0
        ? frame.sampleOffset
        : (long) frame.frameIndex * info.maxBlockSize;
  }

  // Binary search validates actual FLAC frame headers; byte offsets never stand in for sample time.
  private static long[] findFrame(
      AudioRepository.Ranges ranges, long metadata, long length, long target, StreamInfo info) {
    long low = metadata, high = length;
    long[] best = null;
    for (int probe = 0; probe < 8 && high - low > 65536; probe++) {
      long mid = (low + high) / 2;
      long[] found = null;
      try (InputStream range = ranges.open(mid)) {
        byte[] bytes = range.readNBytes(65536);
        var input = new ByteArrayFlacInput(bytes);
        for (int i = 0; i + 16 < bytes.length; i++)
          if ((bytes[i] & 255) == 255 && (bytes[i + 1] & 254) == 248) {
            try {
              input.seekTo(i);
              FrameInfo f = FrameInfo.readFrame(input);
              long s = sample(f, info);
              if (f.numChannels == info.numChannels
                  && s >= 0
                  && (info.numSamples == 0 || s < info.numSamples)) {
                found = new long[] {s, mid + i};
                break;
              }
            } catch (Exception ignored) {
            }
          }
      } catch (Exception ignored) {
        return best;
      }
      if (found == null || found[0] > target) high = mid;
      else {
        best = found;
        low = found[1] + 1;
      }
    }
    return best;
  }

  private static void verify(long frames, int rate, long expected) throws IOException {
    if (frames < rate || expected > 0 && frames * 1000 / rate + 2500 < expected)
      throw new IOException("实际音频短于完整歌曲");
  }

  private static final class StreamInput extends AbstractFlacLowLevelInput {
    private final InputStream input;

    StreamInput(InputStream in) {
      input = in;
    }

    public long getLength() {
      return -1;
    }

    public void seekTo(long pos) {
      throw new UnsupportedOperationException();
    }

    protected int readUnderlying(byte[] bytes, int offset, int count) throws IOException {
      return input.read(bytes, offset, count);
    }
  }
}
