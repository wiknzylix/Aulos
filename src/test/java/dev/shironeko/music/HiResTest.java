package dev.shironeko.music;

import static org.junit.jupiter.api.Assertions.*;

import dev.shironeko.music.client.AudioDecoder;
import dev.shironeko.music.core.PreparationGate;
import io.nayuki.flac.common.*;
import io.nayuki.flac.encode.BitOutputStream;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;

public class HiResTest {
  public static int sample(long frame, int channel) {
    return (int) ((frame * 65537 + channel * 157) & 0xFFFFFF) - 0x800000;
  }

  public static byte[] fixture(int rate) throws Exception {
    return fixture(rate, 2);
  }

  public static byte[] fixture(int rate, int seconds) throws Exception {
    var bytes = new ByteArrayOutputStream();
    var out = new BitOutputStream(bytes);
    out.writeInt(32, 0x664C6143);
    StreamInfo info = new StreamInfo();
    info.minBlockSize = 16;
    info.maxBlockSize = 1024;
    info.minFrameSize = 0;
    info.maxFrameSize = 0;
    info.sampleRate = rate;
    info.numChannels = 2;
    info.sampleDepth = 24;
    info.numSamples = rate * (long) seconds;
    info.md5Hash = new byte[16];
    info.write(true, out);
    for (long frame = 0; frame < info.numSamples; frame += 1024) {
      FrameInfo header = new FrameInfo();
      header.sampleOffset = frame;
      header.numChannels = 2;
      header.channelAssignment = 1;
      header.blockSize = (int) Math.min(1024, info.numSamples - frame);
      header.sampleRate = rate;
      header.sampleDepth = 24;
      header.writeHeader(out);
      for (int channel = 0; channel < 2; channel++) {
        out.writeInt(8, 2);
        for (int i = 0; i < header.blockSize; i++) out.writeInt(24, sample(frame + i, channel));
      }
      out.alignToByte();
      out.writeInt(16, out.getCrc16());
    }
    out.close();
    return bytes.toByteArray();
  }

  @Test
  void real24bit96000And192000FlacRetainLowBitsWithoutEq() throws Exception {
    for (int rate : List.of(96000, 192000)) {
      byte[] bytes = fixture(rate);
      long[] count = {0};
      long duration =
          AudioDecoder.decode(
              new ByteArrayInputStream(bytes),
              bytes.length,
              2000,
              new AudioDecoder.Sink() {
                public void format(int r, int channels, int bits, String codec) {
                  assertEquals(rate, r);
                  assertEquals(24, bits);
                  assertEquals("FLAC", codec);
                  assertEquals(2, channels);
                }

                public void pcm(float[] data, int r, int channels, long frame) {
                  for (int i = 0; i < data.length; i++)
                    assertEquals(sample(frame + i / 2, i % 2) / 8388608f, data[i], 0f);
                  count[0] += data.length / 2;
                }
              });
      assertEquals(rate * 2L, count[0]);
      assertEquals(2000, duration);
    }
  }

  @Test
  void flacTrailingTagsAfterKnownSamplesDoNotBreakPlayback() throws Exception {
    byte[] audio = fixture(96000),
        tag =
            "APETAGEX trailing vendor metadata"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    byte[] file = java.util.Arrays.copyOf(audio, audio.length + tag.length);
    System.arraycopy(tag, 0, file, audio.length, tag.length);
    assertEquals(
        2000,
        AudioDecoder.decode(
            new java.io.ByteArrayInputStream(file),
            file.length,
            2000,
            new AudioDecoder.Sink() {
              public void format(int r, int c, int b, String codec) {}

              public void pcm(float[] d, int r, int c, long f) {}
            }));
  }

  @Test
  void firstReadyMemberCannotStartOthersEarly() {
    UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    assertFalse(PreparationGate.start(List.of(a, b), Map.of(a, true), 2000));
    assertTrue(PreparationGate.start(List.of(a, b), Map.of(a, true, b, true), 2000));
    assertTrue(PreparationGate.start(List.of(a, b), Map.of(a, true, b, false), 2000));
    assertTrue(PreparationGate.start(List.of(a, b), Map.of(a, true), 10000));
    assertFalse(PreparationGate.start(List.of(a, b), Map.of(a, false, b, false), 10000));
  }

  @Test
  void invalidFormatAndTruncatedTracksAreRejected() {
    assertThrows(
        IOException.class,
        () ->
            AudioDecoder.decode(
                new ByteArrayInputStream("<html>error</html>".getBytes()),
                18,
                1000,
                new AudioDecoder.Sink() {
                  public void format(int r, int c, int b, String n) {}

                  public void pcm(float[] d, int r, int c, long f) {}
                }));
  }
}
