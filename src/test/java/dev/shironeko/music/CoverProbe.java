package dev.shironeko.music;

import dev.shironeko.music.client.CoverArt;
import dev.shironeko.music.core.TrackRef;
import dev.shironeko.music.provider.*;
import java.nio.file.*;
import java.util.*;

/** Uses real public metadata/images, reports no track IDs, names, URLs or account data. */
public final class CoverProbe {
  public static void main(String[] args) throws Exception {
    Path folder = Path.of(args[0]), output = Path.of(args[1]);
    Files.createDirectories(output);
    List<Map<String, Object>> results = new ArrayList<>();
    for (MusicProvider provider : List.of(new Netease(folder), new QQ(folder), new Kugou(folder))) {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("platform", provider.id());
      try {
        TrackRef original = provider.search("晴天 周杰伦", 1).items().getFirst();
        TrackRef missing =
            new TrackRef(
                original.platform(),
                original.id(),
                original.title(),
                original.artist(),
                original.album(),
                "",
                original.durationMs());
        long start = System.nanoTime();
        var image = CoverArt.load(missing, "", provider);
        result.put("missing_cover_recovered", image != null);
        result.put("elapsed_ms", (System.nanoTime() - start) / 1_000_000);
        if (image != null) {
          result.put("width", image.pixels().getWidth());
          result.put("height", image.pixels().getHeight());
        }
      } catch (Exception e) {
        result.put("error_type", e.getClass().getSimpleName());
      }
      results.add(result);
    }
    String json = MusicTogether.JSON.toJson(results);
    Files.writeString(output.resolve("results.json"), json);
    System.out.println(json);
    if (results.stream().anyMatch(r -> !Boolean.TRUE.equals(r.get("missing_cover_recovered"))))
      throw new AssertionError("Artwork recovery failed; inspect sanitized results");
  }
}
