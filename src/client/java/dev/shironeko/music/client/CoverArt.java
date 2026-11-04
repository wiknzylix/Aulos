package dev.shironeko.music.client;

import dev.shironeko.music.core.TrackRef;
import dev.shironeko.music.provider.MusicProvider;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;

/**
 * Background-only artwork metadata and image loading, without platform cookies or audio requests.
 */
public final class CoverArt {
  public record Image(String url, BufferedImage pixels) {}

  private static final HttpClient HTTP =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(4))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private static final List<String> DOMAINS =
      List.of(
          "126.net",
          "163.com",
          "qq.com",
          "gtimg.cn",
          "gtimg.com",
          "kugou.com",
          "kgimg.com",
          "kugou.net");

  public static Image load(TrackRef track, String known, MusicProvider provider) {
    Image image = fetch(known);
    if (image != null || provider == null) return image;
    String refreshed = "";
    try {
      refreshed = provider.cover(track);
    } catch (Exception ignored) {
      if (Thread.currentThread().isInterrupted()) return null;
    }
    if (!refreshed.equals(known)) image = fetch(refreshed);
    if (image != null) return image;
    // Older playlist schemas sometimes omit artwork and album IDs. Only reuse the exact identity.
    try {
      for (TrackRef candidate : provider.search(track.title() + " " + track.artist(), 1).items()) {
        if (!sameRecording(track, candidate)
            || candidate.cover().isBlank()
            || candidate.cover().equals(known)
            || candidate.cover().equals(refreshed)) continue;
        image = fetch(candidate.cover());
        if (image != null) return image;
      }
    } catch (Exception ignored) {
      if (Thread.currentThread().isInterrupted()) Thread.currentThread().interrupt();
    }
    return null;
  }

  static boolean sameRecording(TrackRef a, TrackRef b) {
    if (!a.platform().equals(b.platform())) return false;
    return a.platform().equals("kugou")
        ? a.id().split("\\|", -1)[0].equalsIgnoreCase(b.id().split("\\|", -1)[0])
        : a.id().equals(b.id());
  }

  static URI allowed(String url) {
    try {
      URI uri = URI.create(Objects.requireNonNullElse(url, "").replace("{size}", "300"));
      String scheme = Objects.toString(uri.getScheme(), ""),
          host = Objects.toString(uri.getHost(), "").toLowerCase(Locale.ROOT);
      if ((!scheme.equalsIgnoreCase("https") && !scheme.equalsIgnoreCase("http"))
          || uri.getUserInfo() != null
          || uri.getPort() != -1 && uri.getPort() != 443 && uri.getPort() != 80
          || DOMAINS.stream()
              .noneMatch(domain -> host.equals(domain) || host.endsWith("." + domain))) return null;
      return scheme.equalsIgnoreCase("http")
          ? new URI("https", null, uri.getHost(), -1, uri.getPath(), uri.getQuery(), null)
          : uri;
    } catch (Exception ignored) {
      return null;
    }
  }

  private static Image fetch(String url) {
    URI uri = allowed(url);
    for (int redirect = 0; uri != null && redirect < 4; redirect++) {
      try {
        String host = uri.getHost();
        String referer =
            host.endsWith("qq.com") || host.contains("gtimg")
                ? "https://y.qq.com/"
                : host.contains("kugou") || host.contains("kgimg")
                    ? "https://www.kugou.com/"
                    : "https://music.163.com/";
        var response =
            HTTP.send(
                HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(6))
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Referer", referer)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofInputStream());
        try (var input = response.body()) {
          if (Set.of(301, 302, 303, 307, 308).contains(response.statusCode())) {
            String location = response.headers().firstValue("Location").orElse("");
            uri = location.isBlank() ? null : allowed(uri.resolve(location).toString());
            continue;
          }
          if (response.statusCode() != 200) return null;
          byte[] bytes = input.readNBytes(4 * 1024 * 1024 + 1);
          if (bytes.length > 4 * 1024 * 1024) return null;
          try (var imageInput = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) return null;
            var reader = readers.next();
            try {
              reader.setInput(imageInput);
              if (reader.getWidth(0) > 2048 || reader.getHeight(0) > 2048) return null;
              return new Image(uri.toString(), reader.read(0));
            } finally {
              reader.dispose();
            }
          }
        }
      } catch (Exception ignored) {
        if (ignored instanceof InterruptedException) Thread.currentThread().interrupt();
        return null;
      }
    }
    return null;
  }
}
