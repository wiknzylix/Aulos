package dev.shironeko.music.client;

import java.io.*;
import java.nio.file.*;

/** Network writes never wait for PCM consumers. Reader blocks only at the growing end of disk. */
final class AudioSpool extends InputStream {
  final Path path;
  private final RandomAccessFile reader;
  private long size;
  private boolean done, closed;
  private IOException error;

  AudioSpool(Path folder) throws IOException {
    Files.createDirectories(folder);
    path = Files.createTempFile(folder, "aulos-", ".audio");
    reader = new RandomAccessFile(path.toFile(), "r");
  }

  synchronized void written(long bytes) {
    size = bytes;
    notifyAll();
  }

  synchronized void finish(IOException failure) {
    done = true;
    error = failure;
    notifyAll();
  }

  @Override
  public synchronized int read(byte[] data, int off, int len) throws IOException {
    if (len == 0) return 0;
    while (!closed && reader.getFilePointer() >= size && !done)
      try {
        wait(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException();
      }
    if (closed) throw new IOException("任务已取消");
    if (error != null) throw error;
    long remaining = size - reader.getFilePointer();
    if (remaining == 0) return -1;
    return reader.read(data, off, (int) Math.min(len, remaining));
  }

  @Override
  public int read() throws IOException {
    byte[] one = new byte[1];
    return read(one, 0, 1) == -1 ? -1 : one[0] & 255;
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) return;
    closed = true;
    notifyAll();
    reader.close();
    Files.deleteIfExists(path);
  }
}
