package dev.shironeko.music;

import static org.junit.jupiter.api.Assertions.*;

import dev.shironeko.music.provider.*;
import org.junit.jupiter.api.*;

class LyricsTest {
  @Test
  void lrcHasMultipleTagsAndOffset() {
    var l = Lyrics.parse("[offset:100]\n[00:01.20][00:03.20]hello", "[00:01.30]你好", "lrc");
    assertEquals(2, l.lines().size());
    assertEquals(1300, l.lines().getFirst().start());
    assertEquals("你好", l.lines().getFirst().translation());
    assertEquals(0, l.index(2000));
    assertTrue(l.lines().getFirst().words().isEmpty());
  }

  @Test
  void yrcUsesWordTimeNotCharacterGuessing() {
    var l = Lyrics.parse("[1000,2000](1000,500,0)Hi(1500,1500,0) you", "[00:01.00]你好", "yrc");
    assertEquals(2, l.lines().getFirst().words().size());
    assertEquals(1500, l.lines().getFirst().words().getLast().start());
    assertEquals("Hi you", l.lines().getFirst().text());
  }

  @Test
  void krcOffsetsAreRelative() {
    var l = Lyrics.parse("[5000,3000]<0,1000,0>你<1000,2000,0>好", "", "krc");
    assertEquals(6000, l.lines().getFirst().words().getLast().start());
  }

  @Test
  void qrcParsesXmlAndWordTiming() {
    var l =
        Lyrics.parse(
            "<LyricInfo LyricContent=\"[1000,2000]Hi(1000,500) you(1500,1500)&#10;\"/>", "", "qrc");
    assertEquals("Hi you", l.lines().getFirst().text());
    assertEquals(1000, l.lines().getFirst().words().getFirst().start());
  }

  @Test
  void qrcCryptoMatchesIndependentReference() throws Exception {
    String hex = "527d98d0bc3126cb30599ef7ce939c9931f3eb93e6372966ef7f29c3f61c0b47abc47dd8b7a73e0f";
    assertEquals("[0,2000]Hello(0,1000) world(1000,1000)", QrcCrypto.decrypt(hex));
  }

  @Test
  void unsupportedQrcDoesNotInventLyrics() {
    assertEquals("", Lyrics.decodeQq("nonsense"));
    assertTrue(Lyrics.empty().lines().isEmpty());
  }

  @Test
  void cryptoSignMatchesUpstreamFixture() {
    assertEquals(
        "zzcc9d0f4bqjzbqswmgrb756hxopxugkycogfb80ba12",
        Crypto.qqSign("{\"comm\":{\"ct\":\"24\"}}"));
  }
}
