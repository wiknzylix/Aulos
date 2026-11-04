package dev.shironeko.music;

import dev.shironeko.music.provider.*;
import java.nio.file.*;
import java.util.*;

/** Anonymous live checks; never reads a player's saved credentials. */
public final class ProviderProbe {
  public static void main(String[] args) throws Exception {
    Path folder = Path.of(args[0]);
    Files.createDirectories(folder);
    List<Object> results = new ArrayList<>();
    for (MusicProvider p :
        List.of(
            new Netease(folder.resolve("isolated")),
            new QQ(folder.resolve("isolated")),
            new Kugou(folder.resolve("isolated")))) {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("platform", p.id());
      try {
        var qr = p.qr();
        result.put("qr", "generated");
        result.put("poll", p.poll(qr).status());
      } catch (Exception e) {
        result.put("qr_error", safe(e));
      }
      try {
        var search = p.search("Beethoven", 1);
        result.put("search_count", search.items().size());
        if (!search.items().isEmpty()) {
          var track = search.items().getFirst();
          result.put("metadata_ms", track.durationMs());
          try {
            result.put("lyric_lines", p.lyrics(track).lines().size());
          } catch (Exception e) {
            result.put("lyrics_error", safe(e));
          }
          try {
            result.putAll(
                dev.shironeko.music.provider.AccountProbe.decode(
                    p.source(track), track.durationMs()));
          } catch (Exception e) {
            result.put("source_error", safe(e));
          }
        }
      } catch (Exception e) {
        result.put("search_error", safe(e));
      }
      results.add(result);
    }
    String json = MusicTogether.JSON.toJson(results);
    Files.writeString(folder.resolve("results.json"), json);
    System.out.println(json);
  }

  private static String safe(Exception e) {
    return e.getClass().getSimpleName()
        + ": "
        + Objects.toString(e.getMessage(), "").replaceAll("https?://[^ ]+", "[URL]");
  }
}
