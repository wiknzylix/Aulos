package dev.shironeko.music;

import dev.shironeko.music.core.*;
import dev.shironeko.music.provider.*;
import java.nio.file.*;
import java.util.*;

/** Own account check of unprocessed high-bitrate MP3; no private metadata exported. */
public final class CompactSourceProbe {
  public static void main(String[] args) throws Exception {
    Path accounts = Path.of(args[0]), wechat = Path.of(args[1]), out = Path.of(args[2]);
    Files.createDirectories(out);
    List<Object> report = new ArrayList<>();
    for (MusicProvider p : List.of(new Netease(accounts), new QQ(wechat), new Kugou(accounts))) {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("platform", p.id());
      result.put("logged_in", p.loggedIn());
      List<TrackRef> candidates = new ArrayList<>();
      try {
        for (var list : p.playlists(1).items()) {
          var songs = p.tracks(list.id(), 1).items();
          if (!songs.isEmpty()) {
            candidates.add(songs.getFirst());
            break;
          }
        }
      } catch (Exception ignored) {
      }
      try {
        candidates.addAll(
            p.search("Beethoven", 1).items().stream()
                .filter(t -> t.durationMs() >= 60000)
                .limit(3)
                .toList());
      } catch (Exception ignored) {
      }
      int checked = 0;
      for (TrackRef track : candidates) {
        checked++;
        try {
          long began = MusicTogether.now();
          var data =
              dev.shironeko.music.provider.AccountProbe.decode(
                  p.compactSource(track), track.durationMs());
          result.put("available", true);
          result.put("metadata_ms", track.durationMs());
          result.putAll(data);
          result.put("check_ms", MusicTogether.now() - began);
          break;
        } catch (Exception ignored) {
          result.put("available", false);
        }
      }
      result.put("candidates_checked", checked);
      report.add(result);
    }
    Files.writeString(out.resolve("results.json"), MusicTogether.JSON.toJson(report));
    System.out.println(MusicTogether.JSON.toJson(report));
  }
}
