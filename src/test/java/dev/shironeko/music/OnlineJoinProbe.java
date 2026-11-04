package dev.shironeko.music;

import dev.shironeko.music.client.*;
import dev.shironeko.music.core.*;
import dev.shironeko.music.provider.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Explicit opt-in with the operator's own saved account. No names, IDs or URLs in the report. */
public final class OnlineJoinProbe {
  public static void main(String[] args) throws Exception {
    Path account = Path.of(args[0]), folder = Path.of(args[1]);
    Files.createDirectories(folder);
    var provider = new Netease(account);
    var list = provider.playlists(1);
    var track = provider.tracks(list.items().getFirst().id(), 1).items().getFirst();
    QueueEntry entry = QueueEntry.of(track);
    UUID owner = UUID.randomUUID(), local = UUID.randomUUID(), remote = UUID.randomUUID();
    AtomicInteger callbacks = new AtomicInteger();
    List<Object> results = new ArrayList<>();
    try (AudioEngine engine =
        new AudioEngine(
            id -> provider,
            (id, epoch, ok, generation) -> {
              if (ok) callbacks.incrementAndGet();
            },
            folder.resolve("cache"))) {
      for (int phase = 0; phase < 3; phase++) {
        long start = MusicTogether.now(), offset = phase == 1 ? 0 : 864000000L;
        var state =
            new SessionSnapshot(
                phase == 1 ? local : remote,
                phase,
                owner,
                List.of(owner),
                List.of(entry),
                entry.id(),
                entry.id(),
                "ONE",
                false,
                new PlaybackTimeline("PLAYING", 120000, start + offset));
        int expected = callbacks.get() + 1;
        do {
          engine.update(state, offset, 0, phase + 1);
          Thread.sleep(10);
        } while ((callbacks.get() < expected || engine.firstOutputAt < start)
            && MusicTogether.now() - start < 20000);
        if (callbacks.get() < expected || engine.firstOutputAt < start)
          throw new AssertionError("Online join stayed silent: " + engine.status);
        results.add(
            Map.of(
                "phase",
                phase,
                "first_output_ms",
                engine.firstOutputAt - start,
                "seek_ms",
                120000,
                "format",
                engine.format));
      }
    }
    String json = MusicTogether.JSON.toJson(results);
    Files.writeString(folder.resolve("results.json"), json);
    System.out.println(json);
  }
}
