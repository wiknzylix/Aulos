package dev.shironeko.music.client;

import dev.shironeko.music.core.QueueEntry;
import dev.shironeko.music.net.*;
import dev.shironeko.music.provider.MusicProvider;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;

/** Shared growing encoded files for decoding and forwarding; one download per queue entry. */
public final class AudioRepository implements AutoCloseable {
  @FunctionalInterface
  public interface Ranges {
    InputStream open(long offset) throws Exception;
  }

  public record Source(
      InputStream input, long length, boolean forwarded, long durationMs, Ranges ranges)
      implements AutoCloseable {
    public Source(InputStream input, long length, boolean forwarded, long durationMs) {
      this(input, length, forwarded, durationMs, null);
    }

    public Source(InputStream input, long length, boolean forwarded) {
      this(input, length, forwarded, 0, null);
    }

    public void close() throws IOException {
      input.close();
    }
  }

  @FunctionalInterface
  public interface Opener {
    Source open(QueueEntry entry) throws Exception;
  }

  public static final long DISK_LIMIT = 512L * 1024 * 1024;
  private final Opener direct, fallback, compactDirect;
  private final Path folder;
  private java.nio.channels.FileChannel lockFile;
  private java.nio.channels.FileLock cacheLock;
  private final CompletableFuture<Void> initialized = new CompletableFuture<>();
  private final Map<UUID, Asset> assets = new HashMap<>();
  private final AtomicLong disk = new AtomicLong();
  private volatile Set<UUID> retained = Set.of();
  private final ExecutorService downloads =
      Executors.newFixedThreadPool(4, r -> daemon(r, "Aulos encoded download"));
  private final ScheduledExecutorService cleanup =
      Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "Aulos cache cleanup"));

  public AudioRepository(Opener direct, Opener fallback, Path folder) {
    this(direct, fallback, folder, null);
  }

  public AudioRepository(Opener direct, Opener fallback, Path folder, Opener compactDirect) {
    this.compactDirect = compactDirect;
    this.direct = direct;
    this.fallback = fallback;
    this.folder = folder;
    cleanup.execute(
        () -> {
          try {
            Files.createDirectories(folder);
            lockFile =
                java.nio.channels.FileChannel.open(
                    folder.resolve(".aulos-cache.lock"),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
            try {
              cacheLock = lockFile.tryLock();
            } catch (java.nio.channels.OverlappingFileLockException ignored) {
            }
            if (cacheLock != null)
              try (var files = Files.newDirectoryStream(folder, "aulos-*.audio")) {
                for (Path f : files)
                  if (Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS))
                    try {
                      Files.deleteIfExists(f);
                    } catch (IOException ignored) {
                    }
              }
            initialized.complete(null);
          } catch (Exception e) {
            initialized.completeExceptionally(e);
          }
        });
    cleanup.scheduleWithFixedDelay(this::prune, 1, 1, TimeUnit.SECONDS);
  }

  private static Thread daemon(Runnable job, String name) {
    Thread t = new Thread(job, name);
    t.setDaemon(true);
    return t;
  }

  public static Source direct(MusicProvider provider, QueueEntry entry) throws Exception {
    MusicProvider.Source src = provider.source(entry.track());
    return directUri(src);
  }

  public static Source directUri(MusicProvider.Source src) throws Exception {
    var connection = src.uri().toURL().openConnection();
    connection.setConnectTimeout(2000);
    connection.setReadTimeout(6000);
    connection.setRequestProperty("User-Agent", "Mozilla/5.0");
    long length = connection.getContentLengthLong();
    if (length > RelayFrame.MAX_FILE) throw new IOException("原始音源超过单曲256MiB缓存上限");
    return new Source(
        connection.getInputStream(),
        length,
        false,
        src.durationMs(),
        offset -> {
          if (!src.uri().getScheme().startsWith("http")) throw new IOException("Range unsupported");
          var c = (java.net.HttpURLConnection) src.uri().toURL().openConnection();
          c.setConnectTimeout(1800);
          c.setReadTimeout(4000);
          c.setRequestProperty("User-Agent", "Mozilla/5.0");
          c.setRequestProperty("Accept-Encoding", "identity");
          c.setRequestProperty("Range", "bytes=" + offset + "-");
          if (c.getResponseCode() != 206
              || c.getHeaderField("Content-Range") == null
              || !c.getHeaderField("Content-Range").startsWith("bytes " + offset + "-")) {
            c.disconnect();
            throw new IOException("音源不支持精确Range读取");
          }
          return c.getInputStream();
        });
  }

  public void retain(Collection<UUID> ids) {
    retained = Set.copyOf(ids);
  }

  private synchronized Asset acquire(QueueEntry entry) throws IOException {
    return acquire(entry, 0);
  }

  private synchronized Asset acquire(QueueEntry entry, int quality) throws IOException {
    UUID cacheId =
        quality == 0
            ? entry.id()
            : UUID.nameUUIDFromBytes(
                (entry.id() + ":compact").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    Asset asset = assets.get(cacheId);
    if (asset != null
        && asset.done
        && asset.error != null
        && System.nanoTime() - asset.finishedAt > TimeUnit.SECONDS.toNanos(1)) {
      assets.remove(cacheId);
      asset.cancel();
      asset = null;
    }
    if (asset != null && !asset.closed) {
      asset.users++;
      return asset;
    }
    prune();
    if (assets.size() >= 6) throw new IOException("音频缓存任务已满，请稍后重试");
    asset = new Asset(entry, quality);
    asset.users = 1;
    assets.put(cacheId, asset);
    Asset target = asset;
    ((ThreadPoolExecutor) downloads).purge();
    asset.job = downloads.submit(() -> target.download());
    return asset;
  }

  public Source open(QueueEntry entry) throws IOException {
    Asset asset = acquire(entry);
    try {
      asset.resolved.get(18, TimeUnit.SECONDS);
    } catch (Exception e) {
      release(asset);
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
        throw new InterruptedIOException("音源准备已取消");
      }
      throw new IOException(
          "音源准备失败："
              + Objects.toString(
                  e.getCause() == null ? e.getMessage() : e.getCause().getMessage(), "超时"));
    }
    return new Source(
        new InputStream() {
          long position;
          volatile boolean closed;

          public int read() throws IOException {
            byte[] b = new byte[1];
            return read(b, 0, 1) < 0 ? -1 : b[0] & 255;
          }

          public int read(byte[] b, int off, int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            if (closed) throw new IOException("音频任务已取消");
            int n = asset.read(position, b, off, len, false);
            if (n > 0) position += n;
            return n;
          }

          public synchronized void close() {
            if (!closed) {
              closed = true;
              release(asset);
            }
          }
        },
        asset.length,
        asset.forwarded,
        asset.durationMs,
        offset -> {
          synchronized (asset) {
            if (offset < asset.size - 65536 || asset.done && asset.error == null) {
              return new InputStream() {
                long at = offset;

                public int read() throws IOException {
                  byte[] b = new byte[1];
                  return read(b, 0, 1) < 0 ? -1 : b[0] & 255;
                }

                public int read(byte[] b, int off, int len) throws IOException {
                  int n = asset.read(at, b, off, len, false);
                  if (n > 0) at += n;
                  return n;
                }
              };
            }
          }
          if (asset.ranges == null) throw new IOException("没有精确Range音源");
          return asset.ranges.open(offset);
        });
  }

  public RelayFrame chunk(QueueEntry entry, RelayFrame request) throws Exception {
    Asset asset = acquire(entry, request.quality());
    try {
      byte[] data = new byte[RelayFrame.CHUNK];
      int n = asset.read(request.offset(), data, 0, data.length, true);
      if (n < 0) n = 0;
      long total;
      synchronized (asset) {
        total =
            asset.done && asset.error == null ? asset.size : asset.length > 0 ? asset.length : -1;
      }
      // Requests beyond EOF are valid with pipelining, but cannot invent a larger file.
      if (total >= 0 && request.offset() > total) return request.failure("音频已结束");
      boolean eof = total >= 0 && request.offset() + n == total;
      return new RelayFrame(
          RelayFrame.DATA,
          request.session(),
          request.entry(),
          request.request(),
          request.offset(),
          total,
          eof,
          Arrays.copyOf(data, n),
          "",
          request.quality());
    } finally {
      release(asset);
    }
  }

  public synchronized boolean forwarded(UUID id) {
    Asset a = assets.get(id);
    return a != null && a.forwarded;
  }

  private synchronized void release(Asset a) {
    a.users--;
  }

  private synchronized void prune() {
    assets
        .values()
        .removeIf(
            a -> {
              if (a.users == 0 && !retained.contains(a.entry.id())) {
                a.cancel();
                return true;
              }
              return false;
            });
  }

  public synchronized void clear() {
    retained = Set.of();
    for (Asset a : assets.values()) a.cancel();
    assets.clear();
  }

  public synchronized void invalidateEntry(UUID id) {
    assets
        .values()
        .removeIf(
            a -> {
              if (a.entry.id().equals(id)) {
                a.cancel();
                return true;
              }
              return false;
            });
  }

  public synchronized void invalidate(String platform) {
    assets
        .values()
        .removeIf(
            a -> {
              if (a.entry.track().platform().equals(platform)) {
                a.cancel();
                return true;
              }
              return false;
            });
  }

  public void close() {
    clear();
    downloads.shutdownNow();
    cleanup.execute(
        () -> {
          try {
            if (cacheLock != null) cacheLock.release();
            if (lockFile != null) lockFile.close();
          } catch (IOException ignored) {
          }
        });
    cleanup.shutdown();
  }

  public long diskBytes() {
    return disk.get();
  }

  private final class Asset {
    final QueueEntry entry;
    final int quality;
    final Path path;
    final RandomAccessFile reader;
    int users;
    long size;
    volatile boolean closed, done, forwarded;
    IOException error;
    Future<?> job;
    long charged;
    boolean deleted;
    boolean started;
    volatile long finishedAt;
    volatile long durationMs, length;
    volatile Ranges ranges;
    volatile InputStream networkInput;
    final CompletableFuture<Void> resolved = new CompletableFuture<>();

    Asset(QueueEntry entry, int quality) throws IOException {
      this.entry = entry;
      this.quality = quality;
      try {
        initialized.get(10, TimeUnit.SECONDS);
      } catch (Exception e) {
        throw new IOException("无法初始化音频缓存");
      }
      Files.createDirectories(folder);
      path = Files.createTempFile(folder, "aulos-", ".audio");
      reader = new RandomAccessFile(path.toFile(), "r");
    }

    void download() {
      synchronized (this) {
        started = true;
        if (closed) {
          done = true;
          delete();
          return;
        }
      }
      IOException failure = null;
      try {
        Source opened;
        try {
          if (quality == 1 && compactDirect == null) throw new IOException("没有高码率MP3音源");
          opened = (quality == 1 ? compactDirect : direct).open(entry);
        } catch (Exception original) {
          if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
          if (quality == 1 || fallback == null) throw original;
          opened = fallback.open(entry);
          if (opened == null) throw original;
        }
        networkInput = opened.input();
        forwarded = opened.forwarded();
        length = opened.length();
        ranges = opened.ranges();
        durationMs = opened.durationMs() > 0 ? opened.durationMs() : entry.track().durationMs();
        resolved.complete(null);
        try (Source source = opened;
            OutputStream out = Files.newOutputStream(path)) {
          if (source.length() > RelayFrame.MAX_FILE) throw new IOException("音源超过单曲256MiB上限");
          byte[] bytes = new byte[32768];
          int n;
          long written = 0;
          while (!closed && (n = source.input().read(bytes)) != -1) {
            if (closed) throw new InterruptedIOException();
            if (n == 0) continue;
            if (written + n > RelayFrame.MAX_FILE) throw new IOException("音源超过单曲256MiB上限");
            long before;
            do {
              before = disk.get();
              if (before + n > DISK_LIMIT) throw new IOException("音频磁盘缓存达到512MiB上限");
            } while (!disk.compareAndSet(before, before + n));
            charged += n;
            out.write(bytes, 0, n);
            written += n;
            synchronized (this) {
              size = written;
              notifyAll();
            }
          }
          if (closed) throw new InterruptedIOException("音频任务已取消");
          if (source.length() >= 0 && written != source.length()) throw new IOException("音源下载不完整");
        }
      } catch (Exception e) {
        failure =
            new IOException(
                Objects.toString(e.getMessage(), e.getClass().getSimpleName())
                    .replaceAll("https?://[^ ]+", "[URL]"));
      } finally {
        synchronized (this) {
          error = failure;
          if (!resolved.isDone())
            resolved.completeExceptionally(failure == null ? new IOException("音源已取消") : failure);
          finishedAt = System.nanoTime();
          done = true;
          notifyAll();
        }
        if (closed) delete();
      }
    }

    synchronized int read(long position, byte[] b, int off, int len, boolean full)
        throws IOException {
      if (len == 0) return 0;
      if (length > 0 && position >= length) return -1;
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      while (!closed && !done && (size <= position || full && size - position < len)) {
        if (length > 0 && position >= length) return -1;
        if (System.nanoTime() > deadline) throw new IOException("等待点歌人音频超时");
        try {
          wait(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new InterruptedIOException();
        }
      }
      if (closed) throw new IOException("音频任务已取消");
      if (error != null) throw error;
      if (position >= size) return -1;
      reader.seek(position);
      return reader.read(b, off, (int) Math.min(len, size - position));
    }

    void cancel() {
      synchronized (this) {
        closed = true;
        resolved.completeExceptionally(new IOException("音源已取消"));
        if (!started) done = true;
        notifyAll();
      }
      if (job != null) job.cancel(true);
      cleanup.execute(
          () -> {
            try {
              if (networkInput != null) networkInput.close();
            } catch (IOException ignored) {
            }
            delete();
          });
    }

    synchronized void delete() {
      if (deleted || !done) return;
      try {
        reader.close();
        Files.deleteIfExists(path);
      } catch (IOException ignored) {
        return;
      }
      deleted = true;
      disk.addAndGet(-charged);
      charged = 0;
    }
  }
}
