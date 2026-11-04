package dev.shironeko.music;

import static org.junit.jupiter.api.Assertions.*;

import dev.shironeko.music.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TrackMatcherTest {
  TrackRef t(String platform, String name, String singer, long time) {
    return new TrackRef(platform, platform, name, singer, "", "", time);
  }

  @Test
  void matchingNormalizesUnicodeCaseSpacesAndPunctuation() {
    assertTrue(
        TrackMatcher.same(
            t("netease", "Ａ Song!", "Artist / Guest", 180000),
            t("qq", "a song", "Artist、Guest", 181000)));
  }

  @Test
  void bothNamesMustReachNinetyNinePercent() {
    assertFalse(TrackMatcher.same(t("netease", "枫", "周杰伦", 180000), t("qq", "枫", "其他歌手", 180000)));
    assertFalse(
        TrackMatcher.same(t("netease", "枫", "周杰伦", 180000), t("qq", "枫 (Live)", "周杰伦", 180000)));
  }

  @Test
  void fiveSecondDurationToleranceIsInclusive() {
    assertTrue(TrackMatcher.same(t("netease", "枫", "周杰伦", 180000), t("qq", "枫", "周杰伦", 185000)));
    assertFalse(TrackMatcher.same(t("netease", "枫", "周杰伦", 180000), t("qq", "枫", "周杰伦", 185001)));
  }

  @Test
  void unknownSingerDoesNotProduceFalseCrossPlatformMatches() {
    assertFalse(TrackMatcher.same(t("netease", "song", "", 180000), t("qq", "song", "", 180000)));
  }

  @Test
  void exactNinetyNinePercentAndBestDurationCandidate() {
    String a = "x".repeat(100), b = "x".repeat(99) + "y";
    assertEquals(.99, TrackMatcher.similarity(a, b), .000001);
    var original = t("netease", a, "artist", 180000);
    var near = t("qq", b, "artist", 180100);
    assertEquals(
        near,
        TrackMatcher.best(original, List.of(t("kugou", a, "artist", 185000), near)).orElseThrow());
  }
}
