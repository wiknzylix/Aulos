package dev.shironeko.music.client;

import dev.shironeko.music.MusicTogether;
import java.nio.file.*;

public final class ClientConfig {
  public static final class HudLayout {
    public double x, y, scale = 1, width;

    HudLayout(double x, double y, double width) {
      this(x, y, width, 1);
    }

    HudLayout(double x, double y, double width, double scale) {
      this.scale = scale;
      this.x = x;
      this.y = y;
      this.width = width;
    }
  }

  public HudLayout card = new HudLayout(0, 1, 280, .4), lyrics = new HudLayout(.5, .035, 500);
  public int progress = 0xFF6F66A9,
      progressBackground = 0xFF414849,
      lyricActive = 0xFFBE7DC1,
      lyricBase = 0xFFF2F0F5,
      translation = 0xFFC6B6D0;
  public double opacity = .64, volume = .65;
  public boolean muted = false;
  public int key = 75, modifiers = 0;
  public String lastPlatform = "netease";

  public String preferredPlatform(
      java.util.Map<String, dev.shironeko.music.provider.MusicProvider> providers) {
    if (providers.containsKey(lastPlatform) && providers.get(lastPlatform).loggedIn())
      return lastPlatform;
    return providers.values().stream()
        .filter(dev.shironeko.music.provider.MusicProvider::loggedIn)
        .map(dev.shironeko.music.provider.MusicProvider::id)
        .findFirst()
        .orElse(providers.containsKey(lastPlatform) ? lastPlatform : "netease");
  }

  public java.util.Set<String> hiddenPlayers = new java.util.HashSet<>();

  public static ClientConfig load(Path path) {
    try {
      ClientConfig c = MusicTogether.JSON.fromJson(Files.readString(path), ClientConfig.class);
      if (c.card == null || c.lyrics == null) throw new IllegalArgumentException();
      c.volume = Math.clamp(c.volume, 0, 1);
      c.opacity = Math.clamp(c.opacity, 0, 1);
      return c;
    } catch (Exception ignored) {
      return new ClientConfig();
    }
  }

  public void save(Path path) {
    try {
      Files.createDirectories(path.getParent());
      Path tmp = path.resolveSibling("aulos-settings.tmp");
      Files.writeString(tmp, MusicTogether.JSON.toJson(this));
      Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
    } catch (Exception e) {
      MusicClient.message("设置保存失败：" + e.getClass().getSimpleName());
    }
  }
}
