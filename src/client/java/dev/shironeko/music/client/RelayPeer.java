package dev.shironeko.music.client;

import dev.shironeko.music.core.*;
import dev.shironeko.music.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Transport independent peer. Incoming network calls never read disk or wait for a download. */
public final class RelayPeer implements AutoCloseable {
  private record Policy(UUID session, Map<UUID, QueueEntry> entries, boolean receiving) {}

  private record Pending(RelayFrame request, CompletableFuture<RelayFrame> future) {}

  private final UUID self;
  private final Consumer<RelayFrame> sender;
  private final AudioRepository repository;
  private volatile Policy policy = new Policy(null, Map.of(), false);
  private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();
  private final ThreadPoolExecutor serving =
      new ThreadPoolExecutor(
          2,
          2,
          30,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(128),
          r -> {
            Thread t = new Thread(r, "Aulos forwarding");
            t.setDaemon(true);
            return t;
          });

  private record Serving(RelayFrame frame, Future<?> job) {}

  private final Map<UUID, Serving> serveJobs = new ConcurrentHashMap<>();
  private volatile boolean closed;
  private long nextPull;
  private final Set<UUID> compactEntries = ConcurrentHashMap.newKeySet();

  public boolean preferCompact(UUID id) {
    return compactEntries.add(id);
  }

  private void pace() throws InterruptedIOException {
    long delay;
    synchronized (this) {
      long now = System.nanoTime();
      long scheduled = Math.max(now, nextPull);
      nextPull = scheduled + TimeUnit.MILLISECONDS.toNanos(4);
      delay = scheduled - now;
    }
    try {
      if (delay > 0) TimeUnit.NANOSECONDS.sleep(delay);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException();
    }
  }

  public RelayPeer(UUID self, Consumer<RelayFrame> sender, AudioRepository repository) {
    this.self = self;
    this.sender = sender;
    this.repository = repository;
  }

  public void policy(UUID session, List<QueueEntry> entries, boolean receiving) {
    Map<UUID, QueueEntry> allowed = new HashMap<>();
    for (QueueEntry e : entries) if (e.requestedBy() != null) allowed.put(e.id(), e);
    Policy next = new Policy(session, Map.copyOf(allowed), receiving);
    policy = next;
    serveJobs.forEach(
        (id, s) -> {
          if (!Objects.equals(session, s.frame.session())
              || !allowed.containsKey(s.frame.entry())) {
            s.job.cancel(true);
            serveJobs.remove(id);
          }
        });
    repository.retain(allowed.keySet());
    compactEntries.retainAll(allowed.keySet());
    pending.forEach(
        (id, p) -> {
          if (!receiving
              || !Objects.equals(p.request.session(), session)
              || !allowed.containsKey(p.request.entry())) {
            if (pending.remove(id, p)) p.future.completeExceptionally(new IOException("共听音频请求已取消"));
          }
        });
  }

  public void receiving(boolean enabled) {
    Policy p = policy;
    policy(p.session, List.copyOf(p.entries.values()), enabled);
  }

  public boolean sharedEntry(UUID id) {
    return policy.entries.containsKey(id);
  }

  public AudioRepository.Source open(QueueEntry entry) throws IOException {
    Policy p = policy;
    if (closed
        || !p.receiving
        || !p.entries.containsKey(entry.id())
        || self.equals(entry.requestedBy())
        || p.session == null) return null;
    ForwardedInput stream = new ForwardedInput(p.session, entry.id());
    int first = stream.read();
    InputStream joined =
        first < 0
            ? stream
            : new java.io.SequenceInputStream(
                new ByteArrayInputStream(new byte[] {(byte) first}), stream);
    return new AudioRepository.Source(
        joined,
        stream.encodedLength,
        true,
        entry.track().durationMs(),
        offset -> {
          ForwardedInput range = new ForwardedInput(p.session, entry.id());
          range.requested = offset;
          range.delivered = true;
          range.triedCompact = true;
          range.quality = stream.quality;
          return range;
        });
  }

  public void receive(RelayFrame frame) {
    if (closed) return;
    if (frame.kind() == RelayFrame.CANCEL) {
      Serving task = serveJobs.get(frame.request());
      if (task != null && task.frame.matches(frame)) {
        task.job.cancel(true);
        serveJobs.remove(frame.request());
      }
      return;
    }
    if (frame.kind() == RelayFrame.REQUEST) {
      Policy p = policy;
      QueueEntry entry = p.entries.get(frame.entry());
      if (!Objects.equals(frame.session(), p.session)
          || entry == null
          || !self.equals(entry.requestedBy())) {
        sender.accept(frame.failure("音频请求已过期"));
        return;
      }
      try {
        serving.purge();
        Future<?> job =
            serving.submit(
                () -> {
                  try {
                    if (closed) return;
                    if (!Objects.equals(policy.session, p.session)
                        || !entry.equals(policy.entries.get(entry.id()))) {
                      sender.accept(frame.failure("音频请求已过期"));
                      return;
                    }
                    RelayFrame answer;
                    try {
                      answer = repository.chunk(entry, frame);
                    } catch (Exception e) {
                      answer = frame.failure("点歌人无法提供完整音源");
                    }
                    if (!closed
                        && Objects.equals(policy.session, p.session)
                        && entry.equals(policy.entries.get(entry.id()))) sender.accept(answer);
                    else if (!closed) sender.accept(frame.failure("音频请求已过期"));
                  } finally {
                    serveJobs.remove(frame.request());
                  }
                });
        serveJobs.put(frame.request(), new Serving(frame, job));
        if (job.isDone()) serveJobs.remove(frame.request());
      } catch (RejectedExecutionException e) {
        sender.accept(frame.failure("点歌人转发繁忙"));
      }
    } else {
      Pending p = pending.remove(frame.request());
      if (p != null && p.request.matches(frame)) p.future.complete(frame);
    }
  }

  public void close() {
    closed = true;
    serving.shutdownNow();
    serveJobs.clear();
    pending.forEach((id, p) -> p.future.completeExceptionally(new IOException("已断开共听音频连接")));
    pending.clear();
    repository.retain(Set.of());
  }

  private final class ForwardedInput extends InputStream {
    final UUID session, entry;
    final ArrayDeque<Pending> window = new ArrayDeque<>();
    long requested;
    long encodedLength = -1;
    byte[] data = new byte[0];
    int cursor;
    boolean ended, cancelled, delivered, triedCompact, compactFailed;
    int quality;

    ForwardedInput(UUID session, UUID entry) {
      this.session = session;
      this.entry = entry;
      quality = compactEntries.contains(entry) ? 1 : 0;
    }

    void fill() throws IOException {
      while (!ended && window.size() < RelayFrame.WINDOW && requested < RelayFrame.MAX_FILE) {
        if (pending.size() >= 16) break;
        pace();
        RelayFrame frame = RelayFrame.request(session, entry, requested, quality);
        Pending p = new Pending(frame, new CompletableFuture<>());
        pending.put(frame.request(), p);
        window.addLast(p);
        requested += RelayFrame.CHUNK;
        sender.accept(frame);
      }
    }

    public int read() throws IOException {
      byte[] b = new byte[1];
      return read(b, 0, 1) < 0 ? -1 : b[0] & 255;
    }

    public int read(byte[] b, int off, int len) throws IOException {
      Objects.checkFromIndexSize(off, len, b.length);
      if (len == 0) return 0;
      if (cancelled || closed) throw new IOException("音频转发已取消");
      if (cursor == data.length) {
        if (ended) return -1;
        fill();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(35);
        while (window.isEmpty() && !closed && !cancelled && System.nanoTime() < deadline) {
          try {
            Thread.sleep(2);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException();
          }
          fill();
        }
        Pending p = window.pollFirst();
        if (p == null) throw new IOException("音源超过转发上限");
        RelayFrame answer;
        try {
          try {
            answer =
                p.future.get(
                    !delivered && quality == 0 && !triedCompact ? 900 : 35000,
                    TimeUnit.MILLISECONDS);
          } catch (TimeoutException slow) {
            if (!delivered && quality == 0 && !triedCompact) {
              cancel(p);
              cancelWindow();
              quality = 1;
              triedCompact = true;
              requested = 0;
              return read(b, off, len);
            }
            throw slow;
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new InterruptedIOException();
        } catch (Exception e) {
          throw new IOException("等待点歌人转发失败或超时");
        } finally {
          pending.remove(p.request.request(), p);
        }
        if (answer.kind() != RelayFrame.DATA) {
          if (!delivered && quality == 1 && !compactFailed) {
            cancelWindow();
            quality = 0;
            compactFailed = true;
            triedCompact = true;
            requested = 0;
            return read(b, off, len);
          }
          throw new IOException(answer.error());
        }
        encodedLength = answer.total();
        if (quality == 1) compactEntries.add(entry);
        data = answer.data();
        cursor = 0;
        if (answer.eof()) {
          ended = true;
          cancelWindow();
        } else fill();
        if (data.length == 0) return -1;
      }
      delivered = true;
      int n = Math.min(len, data.length - cursor);
      System.arraycopy(data, cursor, b, off, n);
      cursor += n;
      return n;
    }

    private void cancel(Pending p) {
      pending.remove(p.request.request(), p);
      if (!closed)
        sender.accept(
            new RelayFrame(
                RelayFrame.CANCEL,
                session,
                entry,
                p.request.request(),
                p.request.offset(),
                -1,
                false,
                new byte[0],
                "",
                p.request.quality()));
    }

    private void cancelWindow() {
      for (Pending p : window) {
        cancel(p);
      }
      window.clear();
    }

    public void close() {
      if (!cancelled) {
        cancelled = true;
        cancelWindow();
      }
    }
  }
}
