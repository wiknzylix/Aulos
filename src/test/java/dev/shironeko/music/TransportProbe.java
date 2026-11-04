package dev.shironeko.music;

import dev.shironeko.music.client.*;
import dev.shironeko.music.core.*;
import dev.shironeko.music.provider.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Real muted OpenAL output while switching local/foreign time origins and rejoining a session. */
public final class TransportProbe {
  public static void main(String[] args) throws Exception {
    Path folder = Path.of(args[0]);
    Files.createDirectories(folder);
    Path file = folder.resolve("fixture.flac");
    Files.write(file, HiResTest.fixture(96000));
    MusicProvider provider =
        (MusicProvider)
            java.lang.reflect.Proxy.newProxyInstance(
                MusicProvider.class.getClassLoader(),
                new Class[] {MusicProvider.class},
                (o, m, a) ->
                    switch (m.getName()) {
                      case "source" -> new MusicProvider.Source(file.toUri(), 2000);
                      case "invalidateSource", "logout" -> null;
                      default -> throw new UnsupportedOperationException(m.getName());
                    });
    QueueEntry entry =
        QueueEntry.of(new TrackRef("netease", "same-entry", "Fixture", "", "", "", 2000));
    UUID solo = UUID.randomUUID(), shared = UUID.randomUUID(), owner = UUID.randomUUID();
    AtomicInteger callbacks = new AtomicInteger();
    List<Object> results = new ArrayList<>();
    try (AudioEngine engine =
        new AudioEngine(
            id -> provider,
            (id, epoch, ok, generation) -> {
              if (!ok) throw new AssertionError("Decode failed");
              callbacks.incrementAndGet();
            },
            folder.resolve("cache"))) {
      for (int phase = 0; phase < 5; phase++) {
        long start = MusicTogether.now(),
            offset = phase == 0 || phase == 2 ? 0 : 10L * 24 * 3600000;
        UUID session = phase == 0 || phase == 2 ? solo : shared;
        var state =
            new SessionSnapshot(
                session,
                phase,
                owner,
                List.of(owner),
                List.of(entry),
                entry.id(),
                entry.id(),
                "ONE",
                false,
                new PlaybackTimeline("PLAYING", 0, start + offset - 100));
        int expected = callbacks.get() + 1;
        long deadline = start + 2500;
        do {
          engine.update(state, offset, 0, phase + 1);
          Thread.sleep(5);
        } while ((callbacks.get() < expected || engine.firstOutputAt < start)
            && MusicTogether.now() < deadline);
        if (callbacks.get() < expected || engine.firstOutputAt < start)
          throw new AssertionError(
              "Transport switch stayed silent: phase " + phase + " " + engine.status);
        for (int i = 0; i < 20; i++) {
          engine.update(state, offset, 0, phase + 1);
          Thread.sleep(5);
        }
        if (engine.audiblePositionMs < 50 || engine.audiblePositionMs > 1900)
          throw new AssertionError(
              "Foreign clock produced invalid seek: " + engine.audiblePositionMs);
        results.add(
            Map.of(
                "phase",
                phase,
                "clock_offset_ms",
                offset,
                "first_output_ms",
                engine.firstOutputAt - start,
                "position_ms",
                engine.audiblePositionMs));
      }
    } finally {
      Files.deleteIfExists(file);
    }
    String json = MusicTogether.JSON.toJson(results);
    Files.writeString(folder.resolve("results.json"), json);
    System.out.println(json);
  }
}
