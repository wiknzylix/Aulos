package dev.shironeko.music;

import dev.shironeko.music.client.*;
import dev.shironeko.music.core.*;
import dev.shironeko.music.provider.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public final class NativeHiResProbe {
  public static void main(String[] args) throws Exception {
    Path folder = Path.of(args[0]);
    Files.createDirectories(folder);
    List<Object> results = new ArrayList<>();
    for (int rate : List.of(96000, 192000)) {
      Path fixture = folder.resolve("generated-24bit-" + rate + ".flac");
      Files.write(fixture, HiResTest.fixture(rate));
      MusicProvider provider =
          new MusicProvider() {
            public String id() {
              return "netease";
            }

            public String name() {
              return "Generated test fixture";
            }

            public String account() {
              return "No account";
            }

            public Qr qr() {
              throw new UnsupportedOperationException();
            }

            public Login poll(Qr q) {
              throw new UnsupportedOperationException();
            }

            public void logout() {}

            public Page<TrackRef> search(String q, int p) {
              throw new UnsupportedOperationException();
            }

            public Page<Playlist> playlists(int p) {
              throw new UnsupportedOperationException();
            }

            public Page<TrackRef> tracks(String l, int p) {
              throw new UnsupportedOperationException();
            }

            public Source source(TrackRef t) {
              return new Source(fixture.toUri(), 2000);
            }

            public Lyrics lyrics(TrackRef t) {
              return Lyrics.empty();
            }
          };
      QueueEngine q = new QueueEngine(UUID.randomUUID());
      q.add(
          List.of(new TrackRef("netease", "fixture", "Generated", "", "", "", 2000)),
          false,
          MusicTogether.now());
      AtomicInteger ready = new AtomicInteger();
      try (AudioEngine engine =
          new AudioEngine(
              id -> provider,
              (id, epoch, ok, generation) -> ready.set(ok ? 1 : -1),
              folder.resolve("cache"))) {
        long deadline = MusicTogether.now() + 10000;
        while (ready.get() == 0 && MusicTogether.now() < deadline) {
          engine.update(q.snapshot(), 0, 0);
          Thread.sleep(10);
        }
        if (ready.get() != 1)
          throw new AssertionError("HiRes preparation failed: " + engine.status);
        for (int i = 0; i < 30; i++) {
          engine.update(q.snapshot(), 0, 0);
          if (engine.firstOutputAt != 0) throw new AssertionError("Audio before readiness barrier");
          Thread.sleep(10);
        }
        q.ready(MusicTogether.now(), 300);
        long scheduled = q.snapshot().timeline().epochMs();
        while (MusicTogether.now() < scheduled + 1500) {
          engine.update(q.snapshot(), 0, 0);
          if (engine.firstOutputAt != 0 && engine.firstOutputAt < scheduled)
            throw new AssertionError("Audio before scheduled start");
          Thread.sleep(10);
        }
        if (engine.firstOutputAt < scheduled || !engine.format.contains("24 bit"))
          throw new AssertionError("Expected high-resolution native output: " + engine.status);
        results.add(
            Map.of(
                "generated_fixture",
                true,
                "format",
                engine.format,
                "early_start",
                false,
                "start_after_schedule_ms",
                engine.firstOutputAt - scheduled,
                "position_ms",
                engine.audiblePositionMs));
      } finally {
        Files.deleteIfExists(fixture);
      }
    }
    String json = MusicTogether.JSON.toJson(results);
    Files.writeString(folder.resolve("results.json"), json);
    System.out.println(json);
  }
}
