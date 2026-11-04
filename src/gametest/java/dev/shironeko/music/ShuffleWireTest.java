package dev.shironeko.music;

import dev.shironeko.music.client.*;
import dev.shironeko.music.core.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.IntStream;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Real UI controls and the integrated server, with fixture metadata and audio disabled. */
final class ShuffleWireTest {
  static void run(ClientGameTestContext ctx) {
    ctx.setScreen(() -> new MusicScreen(null));
    ctx.clickScreenButton("列表循环");
    ctx.waitFor(c -> MusicClient.state().repeat().equals("SHUFFLE"));
    ctx.runOnClient(
        c ->
            MusicClient.add(
                IntStream.range(0, 30)
                    .mapToObj(
                        i ->
                            new TrackRef(
                                "qq",
                                "shuffle-fixture-" + i,
                                "Shuffle fixture " + i,
                                "Fixture",
                                "",
                                "",
                                60000))
                    .toList(),
                false));
    ctx.waitFor(c -> MusicClient.state().queue().size() == 30);
    List<UUID> played = new ArrayList<>();
    played.add(ctx.computeOnClient(c -> MusicClient.state().current()));
    for (int i = 1; i < 5; i++) {
      UUID before = played.getLast();
      ctx.clickScreenButton("▶|");
      ctx.waitFor(c -> !MusicClient.state().current().equals(before));
      played.add(ctx.computeOnClient(c -> MusicClient.state().current()));
      ctx.waitTicks(3);
    }
    UUID pending = ctx.computeOnClient(c -> MusicClient.state().next());
    for (int i = 3; i >= 0; i--) {
      UUID expected = played.get(i), forward = played.get(i + 1);
      ctx.clickScreenButton("|◀");
      ctx.waitFor(c -> MusicClient.state().current().equals(expected));
      ctx.runOnClient(
          c -> {
            if (!MusicClient.state().next().equals(forward)
                || MusicClient.state().randomNextHidden())
              throw new AssertionError("Server must publish the recorded forward song");
          });
      ctx.waitTicks(3);
    }
    long oldest = ctx.computeOnClient(c -> MusicClient.state().revision());
    ctx.clickScreenButton("|◀");
    ctx.waitTicks(4);
    ctx.runOnClient(
        c -> {
          if (MusicClient.state().revision() != oldest)
            throw new AssertionError("Cannot leave the five-song window");
        });
    for (int i = 1; i < 5; i++) {
      UUID expected = played.get(i);
      ctx.clickScreenButton("▶|");
      ctx.waitFor(c -> MusicClient.state().current().equals(expected));
      ctx.waitTicks(3);
    }
    ctx.runOnClient(
        c -> {
          if (!MusicClient.state().next().equals(pending)
              || !MusicClient.state().randomNextHidden())
            throw new AssertionError("Returning to frontier must keep its reserved random choice");
        });
    ctx.clickScreenButton("▶|");
    ctx.waitFor(c -> MusicClient.state().current().equals(pending));
    try {
      Files.writeString(
          Path.of("aulos-shuffle-navigation.json"),
          MusicTogether.JSON.toJson(
              Map.of(
                  "history_window_including_current",
                  5,
                  "backward_steps",
                  4,
                  "forward_steps",
                  4,
                  "oldest_noop",
                  true,
                  "reserved_next_preserved",
                  true,
                  "history_next_visible",
                  true,
                  "scope",
                  "UI controls through actual integrated-server protocol; fixture tracks, audio"
                      + " disabled")));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    ctx.waitTicks(3);
    ctx.runOnClient(c -> MusicClient.op("clear"));
    ctx.waitFor(c -> MusicClient.state().queue().isEmpty());
    ctx.waitTicks(3);
    ctx.runOnClient(c -> MusicClient.op("repeat", Map.of("mode", "LIST")));
    ctx.waitFor(c -> MusicClient.state().repeat().equals("LIST"));
    ctx.setScreen(() -> new MusicScreen(null));
  }
}
