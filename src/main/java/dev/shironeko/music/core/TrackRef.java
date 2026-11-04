package dev.shironeko.music.core;

/** Public metadata only. Audio URLs and credentials must never enter shared state. */
public record TrackRef(
    String platform,
    String id,
    String title,
    String artist,
    String album,
    String cover,
    long durationMs) {
  public TrackRef {
    if (!java.util.Set.of("netease", "qq", "kugou").contains(platform))
      throw new IllegalArgumentException("未知平台");
    id = clean(id, 160);
    title = clean(title, 2048);
    artist = clean(artist, 2048);
    album = clean(album, 300);
    cover = clean(cover, 2048);
    if (id.isBlank() || durationMs < 0 || durationMs > 86_400_000L)
      throw new IllegalArgumentException("歌曲信息无效");
  }

  private static String clean(String s, int max) {
    s = s == null ? "" : s.replaceAll("[\\p{Cntrl}§]", "");
    if (s.length() > max) {
      int end = max - 1;
      if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
      s = s.substring(0, end) + "…";
    }
    return s;
  }

  public String label() {
    return title + " — " + artist;
  }
}
