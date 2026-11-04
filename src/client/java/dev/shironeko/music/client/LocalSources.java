package dev.shironeko.music.client;

import dev.shironeko.music.core.*;
import dev.shironeko.music.provider.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Races independent platform checks; only real full source successes count as available. */
public final class LocalSources implements AutoCloseable {
  public record Match(TrackRef track, MusicProvider.Source source) {}

  private static final class Lookup {
    final long time = System.nanoTime();
    final CompletableFuture<Match> first = new CompletableFuture<>();
    final Map<String, Match> matches = new ConcurrentHashMap<>();
    volatile int remaining;
  }

  private final Supplier<Map<String, MusicProvider>> providers;
  private final Map<String, Lookup> cache = new LinkedHashMap<>(64, .75f, true);
  private final ThreadPoolExecutor workers =
      new ThreadPoolExecutor(
          6,
          6,
          30,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(96),
          r -> {
            Thread t = new Thread(r, "Aulos platform match");
            t.setDaemon(true);
            return t;
          });
  private long generation;

  public LocalSources(Supplier<Map<String, MusicProvider>> providers) {
    this.providers = providers;
  }

  private String key(TrackRef t) {
    return t.platform() + ":" + t.id();
  }

  private synchronized Lookup lookup(TrackRef track) {
    String key = key(track);
    Lookup found = cache.get(key);
    if (found != null && System.nanoTime() - found.time < TimeUnit.SECONDS.toNanos(90))
      return found;
    Lookup item = new Lookup();
    Map<String, MusicProvider> platforms = Map.copyOf(providers.get());
    item.remaining = platforms.size();
    cache.put(key, item);
    while (cache.size() > 48) cache.remove(cache.keySet().iterator().next());
    long gen = generation;
    for (MusicProvider p : platforms.values()) {
      try {
        workers.execute(
            () -> {
              try {
                TrackRef candidate = track;
                if (!p.id().equals(track.platform())) {
                  List<TrackRef> results =
                      p.search(track.title() + " " + track.artist(), 1).items();
                  candidate =
                      TrackMatcher.best(track, results)
                          .orElseThrow(() -> new IOException("没有符合99%匹配规则的歌曲"));
                }
                Match match = new Match(candidate, p.source(candidate));
                synchronized (this) {
                  if (gen != generation) return;
                  item.matches.put(p.id(), match);
                  item.first.complete(match);
                }
              } catch (Exception ignored) {
              } finally {
                synchronized (item) {
                  if (--item.remaining == 0 && !item.first.isDone())
                    item.first.completeExceptionally(new IOException("各平台均无本机可播放的完整音源"));
                }
              }
            });
      } catch (RejectedExecutionException e) {
        synchronized (item) {
          if (--item.remaining == 0) item.first.completeExceptionally(new IOException("平台匹配繁忙"));
        }
      }
    }
    if (item.remaining == 0 && !item.first.isDone())
      item.first.completeExceptionally(new IOException("没有可用平台"));
    return item;
  }

  private long waitBudget(QueueEntry entry) {
    UUID self = RelayClient.self();
    if (self != null && self.equals(entry.requestedBy())) return 8000;
    for (MusicProvider p : providers.get().values())
      try {
        if (p.loggedIn()) return 8000;
      } catch (Exception ignored) {
      }
    return 1200;
  }

  public AudioRepository.Source open(QueueEntry entry) throws Exception {
    Match match;
    try {
      match = lookup(entry.track()).first.get(waitBudget(entry), TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      throw new IOException("本机平台无可播放的匹配音源");
    }
    AudioRepository.Source source = AudioRepository.directUri(match.source());
    return new AudioRepository.Source(
        source.input(), source.length(), false, match.track().durationMs(), source.ranges());
  }

  public AudioRepository.Source compact(QueueEntry entry) throws Exception {
    Match match = lookup(entry.track()).first.getNow(null);
    if (match == null) match = new Match(entry.track(), null);
    MusicProvider p = providers.get().get(match.track().platform());
    MusicProvider.Source src = p.compactSource(match.track());
    AudioRepository.Source source = AudioRepository.directUri(src);
    return new AudioRepository.Source(
        source.input(), source.length(), false, match.track().durationMs(), source.ranges());
  }

  public synchronized void prefetch(TrackRef track) {
    Lookup prior = cache.get(key(track));
    if (prior != null && System.nanoTime() - prior.time < TimeUnit.SECONDS.toNanos(90)) return;
    if (cache.values().stream().filter(l -> l.remaining > 0).count() >= 2) return;
    lookup(track);
  }

  public synchronized boolean needsRelay(TrackRef track) {
    Lookup l = cache.get(key(track));
    return l != null && l.remaining == 0 && l.matches.isEmpty();
  }

  public synchronized int mask(TrackRef track) {
    Lookup l = cache.get(key(track));
    if (l == null) return 0;
    int mask = 0;
    for (String id : l.matches.keySet()) mask |= id.equals("netease") ? 1 : id.equals("qq") ? 2 : 4;
    return mask;
  }

  public synchronized void clear() {
    generation++;
    for (Lookup l : cache.values()) l.first.completeExceptionally(new IOException("平台匹配已取消"));
    cache.clear();
    workers.getQueue().clear();
  }

  public void close() {
    clear();
    workers.shutdownNow();
  }
}
