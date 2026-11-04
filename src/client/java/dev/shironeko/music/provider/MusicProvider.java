package dev.shironeko.music.provider;

import dev.shironeko.music.core.TrackRef;
import java.net.URI;
import java.util.List;

public interface MusicProvider {
  record Playlist(String id, String title, int count) {}

  record Page<T>(List<T> items, boolean more) {}

  record Qr(String key, String url, byte[] image, long expiresAt) {}

  record Login(String status, boolean done, boolean expired) {}

  record Source(URI uri, long durationMs) {}

  record AccountStatus(boolean loggedIn, String message) {}

  default boolean loggedIn() {
    return !account().equals("未登录");
  }

  default AccountStatus refreshAccount() throws Exception {
    throw new UnsupportedOperationException("账号状态刷新未实现");
  }

  default void invalidateSource(TrackRef track) {}

  String id();

  String name();

  String account();

  Qr qr() throws Exception;

  default void cancelLogin(Qr qr) {}

  Login poll(Qr qr) throws Exception;

  void logout() throws Exception;

  Page<TrackRef> search(String query, int page) throws Exception;

  Page<Playlist> playlists(int page) throws Exception;

  Page<TrackRef> tracks(String playlist, int page) throws Exception;

  Source source(TrackRef track) throws Exception;

  /** Refresh the exact track's artwork; never substitute a similarly named recording. */
  default String cover(TrackRef track) throws Exception {
    return track.cover();
  }

  default Source compactSource(TrackRef track) throws Exception {
    throw new java.io.IOException("没有可用的高码率MP3转发音源");
  }

  Lyrics lyrics(TrackRef track) throws Exception;
}
