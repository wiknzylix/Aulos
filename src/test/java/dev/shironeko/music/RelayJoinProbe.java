package dev.shironeko.music;

import dev.shironeko.music.client.*;
import dev.shironeko.music.core.*;
import dev.shironeko.music.net.*;
import dev.shironeko.music.provider.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Actual muted output for late joining through byte-range relay, with an already buffered sender.
 */
public final class RelayJoinProbe {
  public static void main(String[] args) throws Exception {
    Path folder = Path.of(args[0]);
    Files.createDirectories(folder);
    byte[] file = HiResTest.fixture(96000, 40);
    UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    QueueEngine q = new QueueEngine(a);
    q.join(b);
    q.add(
        List.of(new TrackRef("qq", "range-late", "Fixture", "", "", "", 40000)),
        false,
        MusicTogether.now());
    QueueEntry entry = q.snapshot().currentEntry();
    RelayRouter router = new RelayRouter(MusicTogether::now);
    RelayPeer[] peers = new RelayPeer[2];
    AtomicInteger requests = new AtomicInteger(), farRequests = new AtomicInteger();
    AtomicBoolean failed = new AtomicBoolean();
    Map<String, Object> result = new LinkedHashMap<>();
    try (var network = Executors.newScheduledThreadPool(2);
        AudioRepository owner =
            new AudioRepository(
                e -> new AudioRepository.Source(new ByteArrayInputStream(file), file.length, false),
                null,
                folder.resolve("owner"));
        AudioRepository listener =
            new AudioRepository(
                e -> {
                  throw new IOException("No direct permission");
                },
                e -> peers[1].open(e),
                folder.resolve("listener"))) {
      try {
        peers[0] =
            new RelayPeer(
                a,
                f -> network.schedule(() -> router.receive(a, f), 8, TimeUnit.MILLISECONDS),
                owner);
        peers[1] =
            new RelayPeer(
                b,
                f -> {
                  if (f.kind() == RelayFrame.REQUEST) {
                    requests.incrementAndGet();
                    if (f.offset() > 8 * 1024 * 1024) farRequests.incrementAndGet();
                  }
                  network.schedule(() -> router.receive(b, f), 8, TimeUnit.MILLISECONDS);
                },
                listener);
        router.peer(a, f -> network.schedule(() -> peers[0].receive(f), 8, TimeUnit.MILLISECONDS));
        router.peer(b, f -> network.schedule(() -> peers[1].receive(f), 8, TimeUnit.MILLISECONDS));
        for (RelayPeer p : peers) p.policy(q.snapshot().sessionId(), List.of(entry), true);
        router.policy(q.snapshot());
        try (var input = owner.open(entry)) {
          input.input().transferTo(OutputStream.nullOutputStream());
        }
        MusicProvider invalidator =
            (MusicProvider)
                java.lang.reflect.Proxy.newProxyInstance(
                    MusicProvider.class.getClassLoader(),
                    new Class[] {MusicProvider.class},
                    (o, m, x) -> null);
        try (AudioEngine engine =
            new AudioEngine(
                p -> invalidator,
                (id, epoch, ok, g) -> {
                  if (!ok) failed.set(true);
                },
                folder.resolve("unused"),
                listener)) {
          long start = MusicTogether.now();
          var state =
              new SessionSnapshot(
                  q.snapshot().sessionId(),
                  2,
                  a,
                  List.of(a, b),
                  List.of(entry),
                  entry.id(),
                  entry.id(),
                  "ONE",
                  false,
                  new PlaybackTimeline("PLAYING", 30000, start));
          while (engine.firstOutputAt == 0 && !failed.get() && MusicTogether.now() - start < 6000) {
            engine.update(state, 0, 0, 1);
            Thread.sleep(5);
          }
          if (engine.firstOutputAt == 0 || failed.get())
            throw new AssertionError("Relay late join failed: " + engine.status);
          for (int i = 0; i < 30; i++) {
            engine.update(state, 0, 0, 1);
            Thread.sleep(5);
          }
          if (engine.audiblePositionMs < 29000
              || engine.audiblePositionMs > 37000
              || farRequests.get() == 0)
            throw new AssertionError("Relay did not use precise remote frame positioning");
          result.put("first_output_ms", engine.firstOutputAt - start);
          result.put("join_position_ms", 30000);
          result.put("audible_position_ms", engine.audiblePositionMs);
          result.put("encoded_bytes", file.length);
          result.put("requests", requests.get());
          result.put("far_offset_requests", farRequests.get());
          result.put("format", engine.format);
          result.put(
              "scope",
              "One muted OpenAL listener, in-process delayed relay, prebuffered sender; not"
                  + " two-machine acoustic measurement");
        }
      } finally {
        for (RelayPeer p : peers) if (p != null) p.close();
      }
    }
    Files.writeString(folder.resolve("results.json"), MusicTogether.JSON.toJson(result));
    System.out.println(MusicTogether.JSON.toJson(result));
  }
}
