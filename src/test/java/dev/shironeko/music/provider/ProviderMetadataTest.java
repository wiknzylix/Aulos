package dev.shironeko.music.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProviderMetadataTest {
  @TempDir Path folder;

  @Test
  void kugouPlaylistAndSearchSchemasPreserveIdentityAndDuration() {
    Kugou provider = new Kugou(folder);
    var playlist =
        provider.track(
            JsonParser.parseString(
                """
                {"hash":"abc","album_id":12,"name":"Artist - Title","timelen":166896,
                 "singerinfo":[{"name":"Artist"}],"albuminfo":{"name":"Album"},
                 "trans_param":{"union_cover":"https://img.kgimg.com/{size}/cover.jpg"}}
                """));
    assertEquals("abc|12", playlist.id());
    assertEquals("Title", playlist.title());
    assertEquals("Artist", playlist.artist());
    assertEquals("Album", playlist.album());
    assertEquals(166896, playlist.durationMs());
    assertTrue(playlist.cover().contains("/300/"));
    var search =
        provider.track(
            JsonParser.parseString(
                """
{"FileHash":"def","AlbumID":34,"SongName":"Search title","SingerName":"Singer","Duration":201}
"""));
    assertEquals("def|34", search.id());
    assertEquals("Search title", search.title());
    assertEquals(201000, search.durationMs());
  }

  @Test
  void qqLegacyAlbumMidAndKugouAlbumArtworkArePreserved() {
    QQ qq = new QQ(folder);
    var song =
        qq.track(
            JsonParser.parseString(
                "{\"songmid\":\"song\",\"name\":\"Title\",\"albummid\":\"album\",\"interval\":60}"));
    assertTrue(song.cover().endsWith("M000album.jpg"));
    Kugou kg = new Kugou(folder);
    var track =
        kg.track(
            JsonParser.parseString(
                "{\"hash\":\"hash\",\"name\":\"Title\",\"sizable_cover\":\"https://img.kgimg.com/{size}/test.jpg\"}"));
    assertEquals("https://img.kgimg.com/300/test.jpg", track.cover());
  }
}
