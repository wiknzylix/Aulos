package dev.shironeko.music.provider;

import static org.junit.jupiter.api.Assertions.*;

import dev.shironeko.music.client.ClientConfig;
import dev.shironeko.music.core.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountFeaturesTest {
  @TempDir Path folder;

  @Test
  void compactAndOriginalSourcesHaveSeparateCaches() throws Exception {
    Fake provider =
        new Fake(folder, "qq", true) {
          protected Source resolveSource(TrackRef t) {
            calls++;
            return new Source(
                java.net.URI.create(
                    "https://example.invalid/" + (compactRequested() ? "compact" : "original")),
                1000);
          }
        };
    TrackRef track = new TrackRef("qq", "x", "x", "a", "", "", 1000);
    assertTrue(provider.source(track).uri().toString().endsWith("original"));
    assertTrue(provider.compactSource(track).uri().toString().endsWith("compact"));
    assertTrue(provider.source(track).uri().toString().endsWith("original"));
    assertEquals(2, provider.calls);
  }

  static class Fake extends Web {
    boolean valid = true;
    String fixtureId;
    int calls;

    Fake(Path folder, String id, boolean logged) {
      super(folder, id);
      fixtureId = id;
      if (logged) auth.addProperty("uid", "test");
    }

    public String id() {
      return fixtureId;
    }

    public String name() {
      return "Fixture";
    }

    public Qr qr() {
      throw new UnsupportedOperationException();
    }

    public Login poll(Qr qr) {
      throw new UnsupportedOperationException();
    }

    public Page<TrackRef> search(String q, int p) {
      return new Page<>(List.of(), false);
    }

    public Page<Playlist> playlists(int p) {
      return new Page<>(List.of(), false);
    }

    public Page<TrackRef> tracks(String l, int p) {
      return new Page<>(List.of(), false);
    }

    protected boolean validateAccount() {
      return valid;
    }

    protected Source resolveSource(TrackRef t) {
      calls++;
      return new Source(URI.create("https://example.invalid/source"), 1000);
    }

    public Lyrics lyrics(TrackRef t) {
      return Lyrics.empty();
    }
  }

  @Test
  void platformPrefersSignedInAndPersistsLastChoice() throws Exception {
    var config = new ClientConfig();
    config.lastPlatform = "qq";
    Map<String, MusicProvider> providers = new LinkedHashMap<>();
    var net = new Fake(folder, "netease", true);
    var qq = new Fake(folder, "qq", false);
    var kg = new Fake(folder, "kugou", false);
    providers.put("netease", net);
    providers.put("qq", qq);
    providers.put("kugou", kg);
    assertEquals("netease", config.preferredPlatform(providers));
    qq.auth.addProperty("uid", "test");
    kg.auth.addProperty("uid", "test");
    assertEquals("qq", config.preferredPlatform(providers));
    Path settings = folder.resolve("settings.json");
    config.save(settings);
    assertEquals("qq", ClientConfig.load(settings).lastPlatform);
    net.logout();
    qq.logout();
    kg.logout();
    assertEquals("qq", config.preferredPlatform(providers));
  }

  @Test
  void sourceCacheIsInvalidatedByLogout() throws Exception {
    var p = new Fake(folder, "cache", true);
    var track = new TrackRef("netease", "1", "x", "", "", "", 1000);
    p.source(track);
    p.source(track);
    assertEquals(1, p.calls);
    p.logout();
    p.source(track);
    assertEquals(2, p.calls);
  }

  @Test
  void refreshingTestsProviderInsteadOfSavedNickname() throws Exception {
    var p = new Fake(folder, "refresh", true);
    assertTrue(p.refreshAccount().loggedIn());
    p.valid = false;
    assertFalse(p.refreshAccount().loggedIn());
    assertFalse(p.loggedIn());
    p.valid = true;
    assertTrue(p.refreshAccount().loggedIn());
  }

  @Test
  void wechatPollParsesWaitingScannedConfirmedAndCancelled() throws Exception {
    assertEquals(408, QQ.parseWechat("window.wx_errcode=408;window.wx_code='';").status());
    assertEquals(404, QQ.parseWechat("window.wx_errcode = 404; window.wx_code = '';").status());
    var confirmed = QQ.parseWechat("window.wx_errcode=405;window.wx_code='test-code';");
    assertEquals(405, confirmed.status());
    assertEquals("test-code", confirmed.code());
    assertEquals(403, QQ.parseWechat("window.wx_errcode=403;").status());
  }

  @Test
  void shuffleHidesImplicitNextButRevealsPriority() {
    var q = new QueueEngine(UUID.randomUUID());
    q.add(
        List.of(
            new TrackRef("netease", "1", "a", "", "", "", 1000),
            new TrackRef("netease", "2", "b", "", "", "", 1000)),
        false,
        1000);
    q.repeat("SHUFFLE");
    assertTrue(q.snapshot().randomNextHidden());
    q.prioritize(List.of(q.snapshot().queue().getLast().id()));
    assertFalse(q.snapshot().randomNextHidden());
    q.next(1100);
    assertTrue(q.snapshot().randomNextHidden());
  }
}
