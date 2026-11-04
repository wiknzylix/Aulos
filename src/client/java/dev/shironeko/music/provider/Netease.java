package dev.shironeko.music.provider;

import com.google.gson.*;
import dev.shironeko.music.MusicTogether;
import dev.shironeko.music.core.TrackRef;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

public final class Netease extends Web {
  private final java.net.http.HttpClient appHttp =
      java.net.http.HttpClient.newBuilder()
          .connectTimeout(java.time.Duration.ofSeconds(12))
          .build();

  public Netease(Path folder) {
    super(folder, "netease");
  }

  public String id() {
    return "netease";
  }

  public String name() {
    return "网易云音乐";
  }

  private JsonObject app(String path, Map<String, ?> data) throws Exception {
    if (s(auth, "device").isEmpty())
      auth.addProperty("device", UUID.randomUUID().toString().replace("-", ""));
    Map<String, Object> header =
        new LinkedHashMap<>(
            Map.of(
                "os",
                "pc",
                "appver",
                "3.1.29.205117",
                "osver",
                "Microsoft-Windows-10-Professional-build-19045-64bit",
                "deviceId",
                s(auth, "device"),
                "channel",
                "netease",
                "requestId",
                System.currentTimeMillis() + "_" + new Random().nextInt(1000),
                "__csrf",
                cookie("__csrf")));
    if (!cookie("MUSIC_U").isEmpty()) header.put("MUSIC_U", cookie("MUSIC_U"));
    Map<String, Object> body = new LinkedHashMap<>(data);
    body.put("header", header);
    body.put("e_r", false);
    String uri = "/api" + path,
        json = MusicTogether.JSON.toJson(body),
        digest = Crypto.hash("MD5", "nobody" + uri + "use" + json + "md5forencrypt");
    String text = uri + "-36cd479b6b5-" + json + "-36cd479b6b5-" + digest;
    String params =
        HexFormat.of()
            .formatHex(
                Crypto.aes(text.getBytes(StandardCharsets.UTF_8), "e82ckenh8dichen8", "ECB", true))
            .toUpperCase(Locale.ROOT);
    java.net.URI endpoint = java.net.URI.create("https://interfacepc.music.163.com/eapi" + path);
    Map<String, Object> cookies = new LinkedHashMap<>(header);
    for (java.net.HttpCookie cookie : jar.getCookieStore().getCookies())
      cookies.put(cookie.getName(), cookie.getValue());
    String cookieHeader =
        cookies.entrySet().stream()
            .map(e -> e.getKey() + "=" + enc(String.valueOf(e.getValue())).replace("+", "%20"))
            .collect(java.util.stream.Collectors.joining("; "));
    var request =
        java.net.http.HttpRequest.newBuilder(endpoint)
            .timeout(java.time.Duration.ofSeconds(20))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", cookieHeader)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko)"
                    + " Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/3.1.29.205117")
            .POST(java.net.http.HttpRequest.BodyPublishers.ofString(form(Map.of("params", params))))
            .build();
    var response = appHttp.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
    jar.put(endpoint, response.headers().map());
    require(response.statusCode() == 200, "网易云 HTTP " + response.statusCode());
    JsonObject result = parse(response.body());
    long code = n(result, "code");
    require(
        code == 200 || code >= 800 && code <= 803 || code == 8821,
        "网易云接口错误 " + code + " " + s(result, "message"));
    return result;
  }

  private JsonObject api(String path, Map<String, ?> data) throws Exception {
    String json = MusicTogether.JSON.toJson(data),
        secret = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    String first =
        Base64.getEncoder()
            .encodeToString(
                Crypto.aes(json.getBytes(StandardCharsets.UTF_8), "0CoJUm6Qyw8W8jud", "CBC", true));
    String params =
        Base64.getEncoder()
            .encodeToString(
                Crypto.aes(first.getBytes(StandardCharsets.UTF_8), secret, "CBC", true));
    String modulus =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7";
    String rsa =
        new BigInteger(
                1, new StringBuilder(secret).reverse().toString().getBytes(StandardCharsets.UTF_8))
            .modPow(new BigInteger("10001", 16), new BigInteger(modulus, 16))
            .toString(16);
    rsa = "0".repeat(256 - rsa.length()) + rsa;
    JsonObject r =
        parse(
            request(
                "https://music.163.com/weapi" + path + "?csrf_token=" + enc(cookie("__csrf")),
                "POST",
                form(Map.of("params", params, "encSecKey", rsa)),
                Map.of(
                    "Content-Type",
                    "application/x-www-form-urlencoded",
                    "Referer",
                    "https://music.163.com/")));
    long code = n(r, "code");
    require(
        code == 200 || code == 800 || code == 801 || code == 802 || code == 803,
        "网易云接口错误 " + code + " " + s(r, "message"));
    return r;
  }

  public Qr qr() throws Exception {
    JsonObject r = app("/login/qrcode/unikey", Map.of("type", 3));
    String key = s(r, "unikey");
    require(!key.isEmpty(), "未取得网易云二维码");
    return new Qr(
        key,
        "https://music.163.com/login?codekey=" + key,
        null,
        System.currentTimeMillis() + 180000);
  }

  public Login poll(Qr qr) throws Exception {
    JsonObject r = app("/login/qrcode/client/login", Map.of("key", qr.key(), "type", 3));
    int code = (int) n(r, "code");
    if (code == 803) {
      JsonObject user = api("/w/nuser/account/get", Map.of());
      auth.addProperty("uid", s(user, "account.id"));
      auth.addProperty("name", s(user, "profile.nickname"));
      save();
    }
    return new Login(
        code == 803
            ? "登录成功"
            : code == 802 ? "已扫码，请确认" : code == 800 || code == 8821 ? "二维码已失效，请刷新" : "等待扫码",
        code == 803,
        code == 800 || code == 8821);
  }

  private TrackRef track(JsonElement t) {
    return new TrackRef(
        id(),
        s(t, "id"),
        s(t, "name"),
        names(a(t, "ar")),
        s(t, "al.name"),
        s(t, "al.picUrl"),
        n(t, "dt"));
  }

  public Page<TrackRef> search(String q, int page) throws Exception {
    JsonObject r =
        api(
            "/cloudsearch/pc",
            Map.of("s", q, "type", 1, "offset", (page - 1) * 50, "limit", 50, "total", true));
    List<TrackRef> out = new ArrayList<>();
    for (JsonElement t : a(r, "result.songs")) out.add(track(t));
    return new Page<>(out, page * 50 < n(r, "result.songCount"));
  }

  public Page<Playlist> playlists(int page) throws Exception {
    require(!s(auth, "uid").isEmpty(), "请先扫码登录网易云");
    JsonObject r =
        api(
            "/user/playlist",
            Map.of(
                "uid",
                s(auth, "uid"),
                "offset",
                (page - 1) * 50,
                "limit",
                50,
                "includeVideo",
                true));
    List<Playlist> out = new ArrayList<>();
    for (JsonElement p : a(r, "playlist"))
      out.add(new Playlist(s(p, "id"), s(p, "name"), (int) n(p, "trackCount")));
    return new Page<>(out, at(r, "more").isJsonPrimitive() && at(r, "more").getAsBoolean());
  }

  private String cachedId = "";
  private JsonArray cachedTracks = new JsonArray();

  public synchronized Page<TrackRef> tracks(String id, int page) throws Exception {
    if (!cachedId.equals(id) || page == 1) {
      JsonObject r = api("/v6/playlist/detail", Map.of("id", id, "n", 100000, "s", 0));
      cachedId = id;
      cachedTracks = a(r, "playlist.trackIds");
    }
    int from = (page - 1) * 100, end = Math.min(cachedTracks.size(), from + 100);
    if (from >= end) return new Page<>(List.of(), false);
    List<Map<String, Object>> ids = new ArrayList<>();
    for (int i = from; i < end; i++) ids.add(Map.of("id", n(cachedTracks.get(i), "id")));
    JsonObject r = api("/v3/song/detail", Map.of("c", MusicTogether.JSON.toJson(ids)));
    List<TrackRef> out = new ArrayList<>();
    for (JsonElement t : a(r, "songs")) out.add(track(t));
    return new Page<>(out, end < cachedTracks.size());
  }

  protected Source resolveSource(TrackRef t) throws Exception {
    Exception last = null;
    for (String level :
        compactRequested()
            ? List.of("exhigh", "higher")
            : List.of("hires", "lossless", "exhigh", "higher", "standard")) {
      try {
        JsonObject result =
            api(
                "/song/enhance/player/url/v1",
                Map.of(
                    "ids",
                    "[" + Long.parseLong(t.id()) + "]",
                    "level",
                    level,
                    "encodeType",
                    "flac"));
        JsonArray data = a(result, "data");
        if (data.isEmpty()) continue;
        JsonElement item = data.get(0);
        String actual = s(item, "level");
        if (!actual.isEmpty()
            && !List.of("hires", "lossless", "exhigh", "higher", "standard").contains(actual))
          continue;
        if (!at(item, "freeTrialInfo").isJsonNull() || s(item, "url").isEmpty()) continue;
        if (!actual.isEmpty() && !actual.equals(level)) continue;
        return checkedSource(
            s(item, "url"), t.durationMs(), level.equals("hires") || level.equals("lossless"));
      } catch (Exception e) {
        last = e;
      }
    }
    throw new java.io.IOException(
        "网易云未授权可播放的原始 Hi-Res/无损/MP3 音源" + (last == null ? "" : "：" + last.getMessage()));
  }

  public String cover(TrackRef t) throws Exception {
    JsonObject r =
        api(
            "/v3/song/detail",
            Map.of("c", MusicTogether.JSON.toJson(List.of(Map.of("id", Long.parseLong(t.id()))))));
    for (JsonElement song : a(r, "songs"))
      if (s(song, "id").equals(t.id())) return s(song, "al.picUrl");
    return "";
  }

  public Lyrics lyrics(TrackRef t) throws Exception {
    JsonObject r =
        api(
            "/song/lyric/v1",
            Map.of(
                "id", t.id(), "cp", false, "lv", 0, "tv", 0, "rv", 0, "kv", 0, "yv", 0, "ytv", 0,
                "yrv", 0));
    String text = s(r, "yrc.lyric");
    return Lyrics.parse(
        text.isEmpty() ? s(r, "lrc.lyric") : text,
        s(r, "ytlrc.lyric").isEmpty() ? s(r, "tlyric.lyric") : s(r, "ytlrc.lyric"),
        text.isEmpty() ? "lrc" : "yrc");
  }

  protected boolean validateAccount() throws Exception {
    try {
      JsonObject result = api("/w/nuser/account/get", Map.of());
      return n(result, "account.id") > 0;
    } catch (java.io.IOException e) {
      if (e.getMessage().contains("301") || e.getMessage().contains("401")) return false;
      throw e;
    }
  }
}
