package dev.shironeko.music.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.shironeko.music.core.*;
import dev.shironeko.music.provider.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class LocalSourcesTest {
  @TempDir Path folder;

  @Test
  void loggedOutReceiverDoesNotWaitForEverySlowPlatformTimeout() throws Exception {
    var provider = provider("netease", track("netease", 180000), false, 3000, new AtomicInteger());
    try (var sources = new LocalSources(() -> Map.of("netease", provider))) {
      long start = System.nanoTime();
      assertThrows(IOException.class, () -> sources.open(QueueEntry.of(track("netease", 180000))));
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2200);
    }
  }

  MusicProvider provider(
      String id, TrackRef candidate, boolean allowed, long wait, AtomicInteger sourceCalls)
      throws Exception {
    Path audio = folder.resolve(id);
    Files.write(audio, new byte[] {1, 2, 3});
    return (MusicProvider)
        java.lang.reflect.Proxy.newProxyInstance(
            MusicProvider.class.getClassLoader(),
            new Class[] {MusicProvider.class},
            (o, m, args) ->
                switch (m.getName()) {
                  case "id" -> id;
                  case "search" -> new MusicProvider.Page<>(List.of(candidate), false);
                  case "source" -> {
                    sourceCalls.incrementAndGet();
                    if (wait > 0) Thread.sleep(wait);
                    if (!allowed) throw new IOException("No full source");
                    yield new MusicProvider.Source(audio.toUri(), candidate.durationMs());
                  }
                  default -> null;
                });
  }

  TrackRef track(String p, long time) {
    return new TrackRef(p, p, "枫", "周杰伦", "", "", time);
  }

  @Test
  void anotherAuthorizedPlatformWinsWithoutWaitingForSlowOriginal() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    var original = track("netease", 180000);
    var net = provider("netease", original, false, 1200, calls);
    var qq = provider("qq", track("qq", 183000), true, 0, calls);
    try (var sources = new LocalSources(() -> Map.of("netease", net, "qq", qq))) {
      long start = System.nanoTime();
      try (var source = sources.open(QueueEntry.of(original))) {
        assertFalse(source.forwarded());
        assertEquals(183000, source.durationMs());
        assertEquals(1, source.input().read());
      }
      assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 900);
      assertEquals(2, sources.mask(original));
    }
  }

  @Test
  void allThreeDotsDisappearOnlyAfterRealSourceChecksSucceed() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    Map<String, MusicProvider> map = new HashMap<>();
    for (String id : List.of("netease", "qq", "kugou"))
      map.put(id, provider(id, track(id, 180000), true, 0, calls));
    try (var sources = new LocalSources(() -> map)) {
      sources.prefetch(track("qq", 180000));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (sources.mask(track("qq", 180000)) != 7 && System.nanoTime() < deadline)
        Thread.sleep(5);
      assertEquals(7, sources.mask(track("qq", 180000)));
      assertEquals(3, calls.get());
    }
  }

  @Test
  void similarMetadataWithoutPermissionDoesNotCountAsPlayable() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    var p = provider("qq", track("qq", 180000), false, 0, calls);
    try (var sources = new LocalSources(() -> Map.of("qq", p))) {
      assertThrows(IOException.class, () -> sources.open(QueueEntry.of(track("netease", 180000))));
      assertEquals(0, sources.mask(track("netease", 180000)));
    }
  }
}
