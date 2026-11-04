package dev.shironeko.music.provider;

import dev.shironeko.music.MusicTogether;
import dev.shironeko.music.core.TrackRef;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import javazoom.jl.decoder.*;

/** Opt-in live check: counts and durations only, no credentials or private song names. */
public final class AccountProbe {
  public static void main(String[] args) throws Exception {
    Path folder = Path.of(args[0]);
    String platform = args[1];
    MusicProvider provider =
        switch (platform) {
          case "qq" -> new QQ(folder);
          case "kugou" -> new Kugou(folder);
          default -> new Netease(folder);
        };
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("platform", platform);
    result.put("restored_login", !provider.account().equals("未登录"));
    var lists = provider.playlists(1);
    result.put("playlists", lists.items().size());
    List<TrackRef> tracks = List.of();
    for (var list : lists.items()) {
      var page = provider.tracks(list.id(), 1);
      if (!page.items().isEmpty()) {
        tracks = page.items();
        if (page.more()) result.put("second_page", provider.tracks(list.id(), 2).items().size());
        break;
      }
    }
    result.put("tracks", tracks.size());
    if (!tracks.isEmpty()) {
      TrackRef track = tracks.getFirst();
      result.put("track_has_identity", !track.id().startsWith("|") && !track.id().isEmpty());
      result.put("track_has_title", !track.title().isEmpty());
      result.put("metadata_ms", track.durationMs());
      try {
        var lyric = provider.lyrics(track);
        result.put("lyric_lines", lyric.lines().size());
        result.put(
            "word_timed_lines",
            lyric.lines().stream().filter(line -> !line.words().isEmpty()).count());
        result.put(
            "translated_lines",
            lyric.lines().stream().filter(line -> !line.translation().isEmpty()).count());
      } catch (Exception ex) {
        result.put("lyrics_error", safe(ex));
      }
      try {
        result.putAll(decode(provider.source(track), track.durationMs()));
      } catch (Exception ex) {
        result.put("audio_error", safe(ex));
      }
    }
    if (result.containsKey("audio_error")) {
      int checked = 0;
      for (var track : provider.search("Beethoven", 1).items()) {
        if (track.durationMs() < 60000) continue;
        if (++checked > 12) break;
        try {
          var decodedResult = decode(provider.source(track), track.durationMs());
          long decoded = ((Number) decodedResult.get("decoded_ms")).longValue();
          result.put("free_control_format", decodedResult.get("format"));
          result.put("free_control_decoded_ms", decoded);
          result.put("free_control_metadata_ms", track.durationMs());
          result.put("free_control_complete", Math.abs(decoded - track.durationMs()) < 2500);
          break;
        } catch (Exception ex) {
          result.put("free_control_last_error", safe(ex));
        }
      }
      result.put("free_control_checked", Math.min(12, checked));
    }
    String text = MusicTogether.JSON.toJson(result);
    Files.writeString(folder.resolve("account-probe-" + platform + ".json"), text);
    System.out.println(text);
  }

  private static String safe(Exception ex) {
    return ex.getClass().getSimpleName()
        + ": "
        + Objects.toString(ex.getMessage(), "").replaceAll("https?://[^ ]+", "[URL]");
  }

  public static Map<String, Object> decode(MusicProvider.Source source, long expected)
      throws Exception {
    var client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    var response =
        client.send(
            HttpRequest.newBuilder(source.uri()).timeout(Duration.ofSeconds(60)).GET().build(),
            HttpResponse.BodyHandlers.ofInputStream());
    Map<String, Object> result = new LinkedHashMap<>();
    try (var stream = response.body()) {
      if (response.statusCode() != 200)
        throw new java.io.IOException("HTTP " + response.statusCode());
      long duration =
          dev.shironeko.music.client.AudioDecoder.decode(
              stream,
              response.headers().firstValueAsLong("Content-Length").orElse(-1),
              expected,
              new dev.shironeko.music.client.AudioDecoder.Sink() {
                public void format(int rate, int channels, int bits, String codec) {
                  result.put(
                      "format",
                      Map.of(
                          "codec", codec, "sample_rate", rate, "bits", bits, "channels", channels));
                }

                public void pcm(float[] samples, int rate, int channels, long frame) {}
              });
      result.put("decoded_ms", duration);
      return result;
    }
  }
}
