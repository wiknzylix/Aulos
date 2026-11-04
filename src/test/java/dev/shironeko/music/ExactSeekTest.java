package dev.shironeko.music;

import static org.junit.jupiter.api.Assertions.*;

import dev.shironeko.music.client.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ExactSeekTest {
  long verify(byte[] file, AudioRepository.Ranges ranges) throws Exception {
    long[] first = {-1}, last = {0};
    long duration =
        AudioDecoder.decode(
            new ByteArrayInputStream(file),
            file.length,
            10000,
            7000,
            ranges,
            new AudioDecoder.Sink() {
              public void format(int rate, int channels, int bits, String codec) {
                assertEquals(8000, rate);
                assertEquals(24, bits);
              }

              public void pcm(float[] data, int rate, int channels, long frame) {
                if (first[0] < 0) first[0] = frame;
                for (int i = 0; i < data.length; i++)
                  assertEquals(HiResTest.sample(frame + i / 2, i % 2) / 8388608f, data[i]);
                last[0] = frame + data.length / 2;
              }
            });
    assertEquals(10000, duration);
    assertEquals(80000, last[0]);
    return first[0];
  }

  @Test
  void binaryRangeSeekUsesRealFrameNumbersAndKeepsEverySampleExact() throws Exception {
    byte[] file = HiResTest.fixture(8000, 10);
    long first =
        verify(
            file,
            offset -> new ByteArrayInputStream(file, (int) offset, file.length - (int) offset));
    assertTrue(first > 0 && first <= 56000);
  }

  @Test
  void seekTableIsRelativeToMetadataEndAndBadOffsetsFallBackSafely() throws Exception {
    byte[] original = HiResTest.fixture(8000, 10);
    var input = new io.nayuki.flac.decode.ByteArrayFlacInput(original);
    input.seekTo(42);
    var decoder = new io.nayuki.flac.decode.FrameDecoder(input, 24);
    int[][] pcm = new int[2][65536];
    for (int i = 0; i < 40; i++) decoder.readFrame(pcm, 0);
    long offset = input.getPosition() - 42;
    byte[] file = new byte[original.length + 22];
    System.arraycopy(original, 0, file, 0, 42);
    file[4] = 0;
    ByteBuffer block = ByteBuffer.wrap(file, 42, 22);
    block
        .put((byte) 0x83)
        .put((byte) 0)
        .put((byte) 0)
        .put((byte) 18)
        .putLong(40960)
        .putLong(offset)
        .putShort((short) 1024);
    System.arraycopy(original, 42, file, 64, original.length - 42);
    assertEquals(
        40960,
        verify(file, at -> new ByteArrayInputStream(file, (int) at, file.length - (int) at)));
    ByteBuffer.wrap(file).putLong(54, offset + 3);
    assertEquals(
        0, verify(file, at -> new ByteArrayInputStream(file, (int) at, file.length - (int) at)));
  }

  @Test
  void unsupportedRangeFallsBackToFullDecodeWithoutGuessingProgress() throws Exception {
    assertEquals(
        0,
        verify(
            HiResTest.fixture(8000, 10),
            at -> {
              throw new IOException("No Range");
            }));
  }
}
