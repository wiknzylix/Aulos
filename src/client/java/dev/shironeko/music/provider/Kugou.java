package dev.shironeko.music.provider;

import com.google.gson.*;
import dev.shironeko.music.MusicTogether;
import dev.shironeko.music.core.TrackRef;
import java.nio.file.Path;
import java.util.*;

public final class Kugou extends Web {
  public Kugou(Path folder) {
    super(folder, "kugou");
    if (s(auth, "mid").isEmpty())
      auth.addProperty(
          "mid",
          new java.math.BigInteger(UUID.randomUUID().toString().replace("-", ""), 16).toString());
  }

  public String id() {
    return "kugou";
  }

  public String name() {
    return "酷狗音乐";
  }

  private JsonObject api(
      String base, String path, Map<String, ?> params, Object body, boolean web, String router)
      throws Exception {
    if (s(auth, "mid").isEmpty())
      auth.addProperty(
          "mid",
          new java.math.BigInteger(UUID.randomUUID().toString().replace("-", ""), 16).toString());
    if (s(auth, "dev").isEmpty())
      auth.addProperty(
          "dev",
          UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT));
    TreeMap<String, Object> p = new TreeMap<>();
    p.putAll(
        Map.of(
            "dfid",
            s(auth, "dfid").isEmpty() ? "-" : s(auth, "dfid"),
            "mid",
            s(auth, "mid"),
            "uuid",
            "-",
            "appid",
            1005,
            "clientver",
            20489,
            "clienttime",
            System.currentTimeMillis() / 1000));
    if (!s(auth, "token").isEmpty()) {
      p.put("token", s(auth, "token"));
      p.put("userid", s(auth, "uid"));
    }
    p.putAll(params);
    String json = body == null ? "" : MusicTogether.JSON.toJson(body),
        salt = web ? "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt" : "OIlwieks28dk2k092lksi2UIkp";
    if (path.contains("tracker") || router.equals("trackercdn.kugou.com"))
      p.put(
          "key",
          Crypto.hash(
              "MD5",
              p.get("hash")
                  + "57ae12eb6890223e355ccfcb74edf70d"
                  + p.get("appid")
                  + p.get("mid")
                  + p.getOrDefault("userid", 0)));
    StringBuilder sign = new StringBuilder(salt);
    p.forEach((k, v) -> sign.append(k).append('=').append(v));
    sign.append(json).append(salt);
    p.put("signature", Crypto.hash("MD5", sign.toString()));
    Map<String, String> headers =
        new HashMap<>(
            Map.of(
                "Content-Type",
                "application/json",
                "dfid",
                p.get("dfid").toString(),
                "mid",
                p.get("mid").toString(),
                "clienttime",
                p.get("clienttime").toString(),
                "kg-rc",
                "1",
                "kg-thash",
                "5d816a0",
                "kg-rec",
                "1",
                "kg-rf",
                "B9EDA08A64250DEFFBCADDEE00F8F25F"));
    if (!router.isEmpty()) headers.put("x-router", router);
    JsonObject r =
        parse(request(base + path + "?" + form(p), body == null ? "GET" : "POST", json, headers));
    require(
        n(r, "error_code") == 0 && (n(r, "errcode") == 0 || n(r, "errcode") == 200),
        "酷狗接口错误 " + n(r, "error_code") + " " + s(r, "error_msg"));
    return r;
  }

  public Qr qr() throws Exception {
    JsonObject r =
        api(
            "https://login-user.kugou.com",
            "/v2/qrcode",
            Map.of(
                "appid",
                1001,
                "type",
                1,
                "plat",
                4,
                "qrcode_txt",
                "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=1005&",
                "srcappid",
                2919),
            null,
            true,
            "");
    String key = s(r, "data.qrcode");
    require(!key.isEmpty(), "酷狗未返回二维码");
    return new Qr(
        key,
        "https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=" + key,
        null,
        System.currentTimeMillis() + 180000);
  }

  public Login poll(Qr qr) throws Exception {
    JsonObject r =
        api(
            "https://login-user.kugou.com",
            "/v2/get_userinfo_qrcode",
            Map.of(
                "plat",
                4,
                "appid",
                1005,
                "srcappid",
                2919,
                "qrcode",
                qr.key(),
                "dev",
                s(auth, "dev")),
            null,
            true,
            "");
    int status = (int) n(r, "data.status");
    if (status == 4) {
      auth.addProperty("uid", s(r, "data.userid"));
      auth.addProperty("token", s(r, "data.token"));
      auth.addProperty("name", s(r, "data.nickname"));
      save();
    }
    return new Login(
        status == 4 ? "登录成功" : status == 2 ? "已扫码，请确认" : status == 0 ? "二维码已过期" : "等待酷狗扫码",
        status == 4,
        status == 0);
  }

  TrackRef track(JsonElement t) {
    String hash = s(t, "FileHash");
    if (hash.isEmpty()) hash = s(t, "hash");
    String title = s(t, "SongName");
    if (title.isEmpty()) title = s(t, "songname");
    if (title.isEmpty()) title = s(t, "name");
    String artist = s(t, "SingerName");
    if (artist.isEmpty()) artist = s(t, "singername");
    if (artist.isEmpty()) artist = names(a(t, "singerinfo"));
    if (title.contains(" - ")) {
      String[] parts = title.split(" - ", 2);
      if (artist.isEmpty()) artist = parts[0];
      title = parts[1];
    }
    String albumId = s(t, "AlbumID");
    if (albumId.isEmpty()) albumId = s(t, "album_id");
    long duration = n(t, "Duration") * 1000;
    if (duration == 0) duration = n(t, "timelen");
    String cover = s(t, "Image");
    if (cover.isEmpty()) cover = s(t, "trans_param.union_cover");
    if (cover.isEmpty()) cover = s(t, "cover");
    if (cover.isEmpty()) cover = s(t, "sizable_cover");
    return new TrackRef(
        id(),
        hash + "|" + albumId,
        title.isEmpty() ? s(t, "filename") : title,
        artist,
        s(t, "AlbumName").isEmpty() ? s(t, "albuminfo.name") : s(t, "AlbumName"),
        cover.replace("{size}", "300"),
        duration);
  }

  public Page<TrackRef> search(String q, int page) throws Exception {
    JsonObject r =
        api(
            "https://gateway.kugou.com",
            "/v2/search/song",
            Map.of(
                "keyword",
                q,
                "page",
                page,
                "pagesize",
                50,
                "platform",
                "AndroidFilter",
                "iscorrection",
                1,
                "privilegefilter",
                0,
                "area_code",
                1,
                "dopicfull",
                1),
            null,
            false,
            "complexsearch.kugou.com");
    List<TrackRef> out = new ArrayList<>();
    for (JsonElement t : a(r, "data.lists")) out.add(track(t));
    return new Page<>(out, page * 50 < n(r, "data.total"));
  }

  public Page<Playlist> playlists(int page) throws Exception {
    require(!s(auth, "uid").isEmpty(), "请先扫码登录酷狗");
    JsonObject r =
        api(
            "https://gateway.kugou.com",
            "/v7/get_all_list",
            Map.of("plat", 1),
            Map.of(
                "userid",
                Long.parseLong(s(auth, "uid")),
                "token",
                s(auth, "token"),
                "total_ver",
                979,
                "type",
                2,
                "page",
                page,
                "pagesize",
                50),
            false,
            "cloudlist.service.kugou.com");
    List<Playlist> out = new ArrayList<>();
    for (JsonElement p : a(r, "data.info")) {
      String id = s(p, "global_collection_id");
      if (id.isEmpty()) id = s(p, "list_create_gid");
      if (!id.isEmpty()) out.add(new Playlist(id, s(p, "name"), (int) n(p, "count")));
    }
    return new Page<>(out, out.size() == 50);
  }

  public Page<TrackRef> tracks(String playlist, int page) throws Exception {
    JsonObject r =
        api(
            "https://gateway.kugou.com",
            "/pubsongs/v2/get_other_list_file_nofilt",
            Map.of(
                "area_code",
                1,
                "begin_idx",
                (page - 1) * 100,
                "plat",
                1,
                "type",
                1,
                "mode",
                1,
                "personal_switch",
                1,
                "extend_fields",
                "abtags,hot_cmt,popularization",
                "pagesize",
                100,
                "global_collection_id",
                playlist),
            null,
            false,
            "");
    List<TrackRef> out = new ArrayList<>();
    for (JsonElement t : a(r, "data.songs")) out.add(track(t));
    return new Page<>(out, out.size() == 100);
  }

  protected Source resolveSource(TrackRef t) throws Exception {
    String[] id = t.id().split("\\|", -1);
    Map<String, Object> p =
        new HashMap<>(
            Map.of(
                "hash",
                id[0].toLowerCase(Locale.ROOT),
                "album_id",
                id.length > 1 && !id[1].isEmpty() ? id[1] : "0",
                "quality",
                128,
                "need_ogg",
                0,
                "cmd",
                26,
                "behavior",
                "play",
                "pid",
                2,
                "module_id",
                51,
                "version",
                11430,
                "clientver",
                11430));
    p.putAll(
        Map.of(
            "area_code",
            1,
            "album_audio_id",
            0,
            "ssa_flag",
            "is_fromtrack",
            "ptype",
            0,
            "mtype",
            0,
            "page_id",
            151369488,
            "ppage_id",
            "463467626,350369493,788954147",
            "pidversion",
            3001,
            "cdnBackup",
            1));
    p.put("IsFreePart", 0);
    JsonObject r = null;
    JsonArray urls = new JsonArray();
    for (Object quality : compactRequested() ? List.of(320) : List.of("high", "flac", 320, 128)) {
      p.put("quality", quality);
      try {
        r = api("https://gateway.kugou.com", "/v5/url", p, null, false, "trackercdn.kugou.com");
        urls = a(r, "url");
        if (urls.isEmpty()) urls = a(r, "data.url");
        if (!urls.isEmpty() && n(r, "is_free_part") == 0)
          return checkedSource(
              urls.get(0).getAsString(),
              t.durationMs(),
              quality.equals("high") || quality.equals("flac"));
      } catch (java.io.IOException ignored) {
      }
    }
    throw new java.io.IOException("酷狗未授权可用的原始高解析度/无损/MP3完整音源");
  }

  public String cover(TrackRef t) throws Exception {
    String[] identity = t.id().split("\\|", -1);
    if (identity.length < 2 || identity[1].isBlank() || identity[1].equals("0")) return "";
    JsonObject result =
        api(
            "https://gateway.kugou.com",
            "/kmr/v2/albums",
            Map.of(),
            Map.of(
                "data",
                List.of(Map.of("album_id", Long.parseLong(identity[1]))),
                "is_buy",
                0,
                "fields",
                "album_id,sizable_cover"),
            false,
            "openapi.kugou.com");
    for (JsonElement album : a(result, "data"))
      if (s(album, "album_id").equals(identity[1]))
        return s(album, "sizable_cover").replace("{size}", "300");
    return "";
  }

  public Lyrics lyrics(TrackRef t) throws Exception {
    JsonObject r =
        api(
            "https://lyrics.kugou.com",
            "/v1/search",
            Map.of(
                "hash",
                t.id().split("\\|")[0],
                "keyword",
                t.title(),
                "duration",
                t.durationMs(),
                "lrctxt",
                1,
                "man",
                "no"),
            null,
            false,
            "");
    JsonArray list = a(r, "candidates");
    if (list.isEmpty()) return Lyrics.empty();
    JsonElement p = list.get(0);
    JsonObject lyric =
        api(
            "https://lyrics.kugou.com",
            "/download",
            Map.of(
                "ver",
                1,
                "client",
                "android",
                "id",
                s(p, "id"),
                "accesskey",
                s(p, "accesskey"),
                "fmt",
                "krc",
                "charset",
                "utf8"),
            null,
            false,
            "");
    return Lyrics.parse(Lyrics.decodeKrc(s(lyric, "content")), "", "krc");
  }

  protected boolean validateAccount() throws Exception {
    try {
      JsonObject result =
          api(
              "https://gateway.kugou.com",
              "/v7/get_all_list",
              Map.of("plat", 1),
              Map.of(
                  "userid",
                  Long.parseLong(s(auth, "uid")),
                  "token",
                  s(auth, "token"),
                  "total_ver",
                  0,
                  "type",
                  2,
                  "page",
                  1,
                  "pagesize",
                  1),
              false,
              "cloudlist.service.kugou.com");
      return n(result, "status") == 1 && s(result, "data.userid").equals(s(auth, "uid"));
    } catch (java.io.IOException e) {
      if (e.getMessage().contains("20010") || e.getMessage().contains("20011")) return false;
      throw e;
    }
  }
}
