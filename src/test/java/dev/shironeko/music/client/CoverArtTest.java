package dev.shironeko.music.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.shironeko.music.core.TrackRef;
import org.junit.jupiter.api.Test;

class CoverArtTest {
  @Test
  void redirectsMustRemainOnArtworkCdnAndNeverCarryCredentials() {
    assertEquals("https", CoverArt.allowed("http://p1.music.126.net/test.jpg").getScheme());
    assertNotNull(CoverArt.allowed("https://y.gtimg.cn/music/cover.jpg"));
    assertNotNull(CoverArt.allowed("https://imgessl.kugou.com/{size}/cover.jpg"));
    for (String url :
        new String[] {
          "file:///C:/secret",
          "https://127.0.0.1/test",
          "https://qq.com.attacker.example/test",
          "https://user:pass@y.gtimg.cn/test",
          "https://y.gtimg.cn:8888/test"
        }) assertNull(CoverArt.allowed(url));
  }

  @Test
  void artworkFallbackRequiresExactRecordingIdentity() {
    TrackRef a = new TrackRef("kugou", "ABC|1", "title", "artist", "", "", 60000);
    assertTrue(
        CoverArt.sameRecording(
            a, new TrackRef("kugou", "abc|2", "title", "artist", "", "", 60000)));
    assertFalse(
        CoverArt.sameRecording(
            a, new TrackRef("kugou", "XYZ|1", "title", "artist", "", "", 60000)));
    assertFalse(
        CoverArt.sameRecording(a, new TrackRef("qq", "ABC|1", "title", "artist", "", "", 60000)));
  }
}
