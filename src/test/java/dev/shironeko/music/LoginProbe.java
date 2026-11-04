package dev.shironeko.music;

import com.google.zxing.*;
import com.google.zxing.qrcode.QRCodeWriter;
import dev.shironeko.music.provider.*;
import java.nio.file.*;
import java.util.*;

public final class LoginProbe {
  public static void main(String[] args) throws Exception {
    Path folder = Path.of(args[0]);
    Files.createDirectories(folder);
    String platform = args.length > 2 ? args[2] : "netease";
    MusicProvider provider =
        switch (platform) {
          case "qq", "qq-wx" -> new QQ(folder);
          case "kugou" -> new Kugou(folder);
          default -> new Netease(folder);
        };
    var qr = platform.equals("qq-wx") ? ((QQ) provider).wechatQr() : provider.qr();
    if (qr.image() != null) {
      Files.write(Path.of(args[1]), qr.image());
    } else {
      var matrix =
          new QRCodeWriter()
              .encode(qr.url(), BarcodeFormat.QR_CODE, 400, 400, Map.of(EncodeHintType.MARGIN, 4));
      var image =
          new java.awt.image.BufferedImage(400, 400, java.awt.image.BufferedImage.TYPE_INT_RGB);
      for (int y = 0; y < 400; y++)
        for (int x = 0; x < 400; x++) image.setRGB(x, y, matrix.get(x, y) ? 0 : 0xffffff);
      javax.imageio.ImageIO.write(image, "PNG", Path.of(args[1]).toFile());
    }
    System.out.println("QR_READY");
    while (System.currentTimeMillis() < qr.expiresAt()) {
      var result = provider.poll(qr);
      System.out.println(result.status());
      if (result.done()) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("login", "success");
        report.put("platform", provider.id());
        Files.writeString(
            folder.resolve("login-report-" + platform + ".json"),
            MusicTogether.JSON.toJson(report));
        var lists = provider.playlists(1);
        report.put("playlist_count_first_page", lists.items().size());
        report.put("more_playlists", lists.more());
        if (!lists.items().isEmpty()) {
          var tracks = provider.tracks(lists.items().getFirst().id(), 1);
          report.put("track_count_first_page", tracks.items().size());
          report.put("more_tracks", tracks.more());
          if (!tracks.items().isEmpty()) {
            var lyrics = provider.lyrics(tracks.items().getFirst());
            report.put("lyric_lines", lyrics.lines().size());
            try {
              provider.source(tracks.items().getFirst());
              report.put("audio_source", "authorized");
            } catch (Exception e) {
              report.put("audio_source", e.getClass().getSimpleName());
            }
          }
        }
        Files.writeString(
            folder.resolve("login-report-" + platform + ".json"),
            MusicTogether.JSON.toJson(report));
        System.out.println(MusicTogether.JSON.toJson(report));
        return;
      }
      if (result.expired()) break;
      Thread.sleep(2000);
    }
    System.out.println("QR_EXPIRED");
  }
}
