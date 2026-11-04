package dev.shironeko.music.provider;

import com.google.gson.*;
import dev.shironeko.music.MusicTogether;
import dev.shironeko.music.core.TrackRef;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.regex.*;

/** Protocol port of QQMusicApi (GPL-3.0-or-later). */
public final class QQ extends Web {
  private final java.util.concurrent.atomic.AtomicLong loginSerial =
      new java.util.concurrent.atomic.AtomicLong();
  private volatile String activeQr = "";
  private final HttpClient wxHttp =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build();

  public QQ(Path folder) {
    super(folder, "qq");
  }

  public String id() {
    return "qq";
  }

  public String name() {
    return "QQ 音乐";
  }

  private JsonObject rpc(String module, String method, Map<String, ?> params) throws Exception {
    return rpc(module, method, params, Map.of());
  }

  private JsonObject rpc(String module, String method, Map<String, ?> params, Map<String, ?> extra)
      throws Exception {
    if (!s(auth, "uid").isEmpty() && !s(auth, "musickey").isEmpty()) {
      for (String key : List.of("uin", "qqmusic_uin", "qm_keyst", "qqmusic_key")) {
        HttpCookie cookie =
            new HttpCookie(
                key,
                key.equals("uin") || key.equals("qqmusic_uin")
                    ? s(auth, "uid")
                    : s(auth, "musickey"));
        cookie.setVersion(0);
        cookie.setDomain(".qq.com");
        cookie.setPath("/");
        cookie.setSecure(true);
        jar.getCookieStore().add(URI.create("https://y.qq.com"), cookie);
      }
    }
    Map<String, Object> comm = new LinkedHashMap<>();
    comm.put("ct", "19");
    comm.put("cv", "2201");
    comm.put("chid", "0");
    comm.put("guid", "1E441EF7E23D40B6A8A521F950E8DA47");
    comm.put("g_tk", Long.toString(Crypto.hash33(s(auth, "musickey"), 5381)));
    comm.put("format", "json");
    comm.put("uin", s(auth, "uid").isEmpty() ? "0" : s(auth, "uid"));
    comm.put("authst", s(auth, "musickey"));
    comm.put("tmeLoginType", s(auth, "loginType").isEmpty() ? "2" : s(auth, "loginType"));
    comm.putAll(extra);
    String body =
        MusicTogether.JSON.toJson(
            Map.of(
                "comm",
                comm,
                "req_0",
                Map.of("module", module, "method", method, "param", params)));
    JsonObject r =
        parse(
            request(
                "https://u.y.qq.com/cgi-bin/musicu.fcg",
                "POST",
                body,
                Map.of("Content-Type", "application/json", "Referer", "https://y.qq.com/")));
    require(
        n(r, "code") == 0 && n(r, "req_0.code") == 0,
        "QQ 音乐接口错误 "
            + (n(r, "req_0.code") != 0 ? n(r, "req_0.code") : n(r, "code"))
            + "：登录可能过期或权限不足");
    JsonElement data = at(r, "req_0.data");
    require(data.isJsonObject(), "QQ 音乐未返回数据");
    return data.getAsJsonObject();
  }

  public Qr qr() throws Exception {
    long serial = beginLogin();
    byte[] png =
        request(
            "https://ssl.ptlogin2.qq.com/ptqrshow?"
                + form(
                    Map.of(
                        "appid",
                        "716027609",
                        "e",
                        "2",
                        "l",
                        "M",
                        "s",
                        "3",
                        "d",
                        "72",
                        "v",
                        "4",
                        "t",
                        Math.random(),
                        "daid",
                        "383",
                        "pt_3rd_aid",
                        "100497308")),
            "GET",
            "",
            Map.of("Referer", "https://xui.ptlogin2.qq.com/"));
    String key = cookie("qrsig");
    require(!key.isEmpty(), "QQ 未返回二维码会话");
    bindLogin(serial, key);
    return new Qr(key, "", png, System.currentTimeMillis() + 180000);
  }

  public Login poll(Qr qr) throws Exception {
    if (!qr.key().equals(activeQr)) return new Login("已取消登录", false, true);
    if (qr.key().startsWith("wx:")) return pollWechat(qr);
    Map<String, Object> p =
        new LinkedHashMap<>(
            Map.of(
                "u1",
                "https://graph.qq.com/oauth2.0/login_jump",
                "ptqrtoken",
                Crypto.hash33(qr.key(), 0),
                "ptredirect",
                "0",
                "h",
                "1",
                "t",
                "1",
                "g",
                "1",
                "from_ui",
                "1",
                "ptlang",
                "2052",
                "action",
                "0-0-" + System.currentTimeMillis()));
    p.putAll(
        Map.of(
            "js_ver",
            "20102616",
            "js_type",
            "1",
            "pt_uistyle",
            "40",
            "aid",
            "716027609",
            "daid",
            "383",
            "pt_3rd_aid",
            "100497308",
            "has_onekey",
            "1"));
    String text =
        new String(
            request(
                "https://ssl.ptlogin2.qq.com/ptqrlogin?" + form(p),
                "GET",
                "",
                Map.of("Referer", "https://xui.ptlogin2.qq.com/")),
            java.nio.charset.StandardCharsets.UTF_8);
    Matcher m = Pattern.compile("'([^']*)'").matcher(text);
    List<String> args = new ArrayList<>();
    while (m.find()) args.add(m.group(1));
    require(!args.isEmpty(), "QQ 二维码状态无法解析");
    String code = args.getFirst();
    if (code.equals("0")) {
      require(args.size() > 2, "QQ 登录缺少跳转地址");
      authorize(args.get(2), qr.key());
      return new Login("登录成功", true, false);
    }
    return new Login(
        code.equals("67") ? "已扫码，请在 QQ 确认" : code.equals("65") ? "二维码已过期" : "等待 QQ 扫码",
        false,
        code.equals("65"));
  }

  private static String query(String url, String key) {
    Matcher m = Pattern.compile("[?&]" + key + "=([^&]+)").matcher(url);
    return m.find() ? URLDecoder.decode(m.group(1), java.nio.charset.StandardCharsets.UTF_8) : "";
  }

  private void authorize(String callback, String qrKey) throws Exception {
    String uin = query(callback, "uin"), sig = query(callback, "ptsigx");
    require(!uin.isEmpty() && !sig.isEmpty(), "QQ 回调缺少凭据");
    Map<String, Object> p =
        new LinkedHashMap<>(
            Map.of(
                "uin",
                uin,
                "pttype",
                "1",
                "service",
                "ptqrlogin",
                "nodirect",
                "0",
                "ptsigx",
                sig,
                "s_url",
                "https://graph.qq.com/oauth2.0/login_jump",
                "ptlang",
                "2052",
                "ptredirect",
                "100",
                "aid",
                "716027609",
                "daid",
                "383"));
    p.putAll(
        Map.of(
            "j_later",
            "0",
            "low_login_hour",
            "0",
            "regmaster",
            "0",
            "pt_login_type",
            "3",
            "pt_aid",
            "0",
            "pt_aaid",
            "16",
            "pt_light",
            "0",
            "pt_3rd_aid",
            "100497308"));
    HttpClient noRedirect =
        HttpClient.newBuilder()
            .cookieHandler(jar)
            .connectTimeout(Duration.ofSeconds(12))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    noRedirect.send(
        HttpRequest.newBuilder(URI.create("https://ssl.ptlogin2.graph.qq.com/check_sig?" + form(p)))
            .timeout(Duration.ofSeconds(20))
            .header("Referer", "https://xui.ptlogin2.qq.com/")
            .GET()
            .build(),
        HttpResponse.BodyHandlers.discarding());
    String skey = cookie("p_skey");
    require(!skey.isEmpty(), "QQ 未取得 p_skey");
    Map<String, Object> data =
        new LinkedHashMap<>(
            Map.of(
                "response_type",
                "code",
                "client_id",
                "100497308",
                "redirect_uri",
                "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/",
                "scope",
                "get_user_info,get_app_friends",
                "state",
                "state",
                "switch",
                "",
                "from_ptlogin",
                "1",
                "src",
                "1",
                "update_auth",
                "1",
                "openapi",
                "1010_1030"));
    data.put("g_tk", Crypto.hash33(skey, 5381));
    data.put("auth_time", System.currentTimeMillis());
    data.put("ui", UUID.randomUUID().toString());
    HttpResponse<Void> response =
        noRedirect.send(
            HttpRequest.newBuilder(URI.create("https://graph.qq.com/oauth2.0/authorize"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(data)))
                .build(),
            HttpResponse.BodyHandlers.discarding());
    String code = query(response.headers().firstValue("Location").orElse(""), "code");
    require(!code.isEmpty(), "QQ 授权未完成，可能需要在官方客户端确认");
    JsonObject result =
        rpc(
            "QQConnectLogin.LoginServer",
            "QQLogin",
            Map.of("code", code),
            Map.of("tmeLoginType", 2));
    require(!s(result, "musickey").isEmpty(), "QQ 登录未返回音乐凭证");
    commitLogin(result, 2, qrKey);
  }

  private synchronized void commitLogin(JsonObject result, int type, String qrKey)
      throws Exception {
    require(activeQr.equals(qrKey), "本次登录已取消");
    auth = result;
    auth.addProperty(
        "uid",
        s(result, "str_musicid").isEmpty() ? s(result, "musicid") : s(result, "str_musicid"));
    auth.addProperty("loginType", type);
    auth.addProperty(
        "name",
        (type == 1 ? "微信 " : "QQ ")
            + (s(auth, "nick").isEmpty() ? s(auth, "uid") : s(auth, "nick")));
    save();
  }

  private synchronized long beginLogin() {
    activeQr = "";
    return loginSerial.incrementAndGet();
  }

  private synchronized void bindLogin(long serial, String key) throws InterruptedException {
    if (serial != loginSerial.get()) throw new InterruptedException();
    activeQr = key;
  }

  public synchronized void cancelLogin(Qr qr) {
    loginSerial.incrementAndGet();
    activeQr = "";
  }

  private byte[] wxRequest(String url, Duration timeout) throws Exception {
    var response =
        wxHttp.send(
            HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Referer", "https://open.weixin.qq.com/")
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
    try (var in = response.body()) {
      require(response.statusCode() == 200, "微信登录 HTTP " + response.statusCode());
      byte[] bytes = in.readNBytes(1024 * 1024 + 1);
      require(bytes.length <= 1024 * 1024, "微信登录响应过大");
      return bytes;
    }
  }

  public Qr wechatQr() throws Exception {
    long serial = beginLogin();
    String text =
        new String(
            wxRequest(
                "https://open.weixin.qq.com/connect/qrconnect?"
                    + form(
                        Map.of(
                            "appid",
                            "wx48db31d50e334801",
                            "redirect_uri",
                            "https://y.qq.com/portal/wx_redirect.html?login_type=2&surl=https://y.qq.com/",
                            "response_type",
                            "code",
                            "scope",
                            "snsapi_login",
                            "state",
                            "STATE",
                            "href",
                            "https://y.qq.com/mediastyle/music_v17/src/css/popup_wechat.css#wechat_redirect")),
                Duration.ofSeconds(12)),
            java.nio.charset.StandardCharsets.UTF_8);
    Matcher matcher = Pattern.compile("uuid=([A-Za-z0-9_-]+)").matcher(text);
    require(matcher.find(), "微信未返回二维码会话");
    String uuid = matcher.group(1);
    byte[] image =
        wxRequest("https://open.weixin.qq.com/connect/qrcode/" + uuid, Duration.ofSeconds(12));
    bindLogin(serial, "wx:" + uuid);
    return new Qr("wx:" + uuid, "", image, System.currentTimeMillis() + 180000);
  }

  record WechatState(int status, String code) {}

  static WechatState parseWechat(String text) throws java.io.IOException {
    Matcher status = Pattern.compile("wx_errcode\\s*=\\s*(\\d+)").matcher(text);
    require(status.find(), "微信二维码状态无法解析");
    Matcher code = Pattern.compile("wx_code\\s*=\\s*['\"]([^'\"]*)['\"]").matcher(text);
    return new WechatState(Integer.parseInt(status.group(1)), code.find() ? code.group(1) : "");
  }

  private Login pollWechat(Qr qr) throws Exception {
    String text;
    try {
      text =
          new String(
              wxRequest(
                  "https://lp.open.weixin.qq.com/connect/l/qrconnect?"
                      + form(
                          Map.of("uuid", qr.key().substring(3), "_", System.currentTimeMillis())),
                  Duration.ofSeconds(38)),
              java.nio.charset.StandardCharsets.UTF_8);
    } catch (java.net.http.HttpTimeoutException e) {
      return new Login("等待微信扫码", false, false);
    }
    WechatState status = parseWechat(text);
    if (status.status() == 405) {
      require(!status.code().isEmpty(), "微信确认未返回授权码");
      JsonObject result =
          rpc(
              "music.login.LoginServer",
              "Login",
              Map.of("code", status.code(), "strAppid", "wx48db31d50e334801"),
              Map.of("tmeLoginType", 1));
      require(!s(result, "musickey").isEmpty(), "微信登录未返回音乐凭证");
      commitLogin(result, 1, qr.key());
      return new Login("微信登录成功", true, false);
    }
    return switch (status.status()) {
      case 404 -> new Login("已扫码，请在微信确认", false, false);
      case 402 -> new Login("微信二维码已过期", false, true);
      case 403 -> new Login("已取消微信登录", false, true);
      case 408 -> new Login("等待微信扫码", false, false);
      default -> throw new java.io.IOException("未知微信二维码状态 " + status.status());
    };
  }

  TrackRef track(JsonElement t) {
    String mid = s(t, "mid");
    if (mid.isEmpty()) mid = s(t, "songmid");
    String title = s(t, "title");
    if (title.isEmpty()) title = s(t, "name");
    String albumMid = s(t, "album.mid");
    if (albumMid.isEmpty()) albumMid = s(t, "albummid");
    return new TrackRef(
        id(),
        mid,
        title,
        names(a(t, "singer")),
        s(t, "album.name"),
        albumMid.isEmpty()
            ? ""
            : "https://y.gtimg.cn/music/photo_new/T002R300x300M000" + albumMid + ".jpg",
        n(t, "interval") * 1000);
  }

  public Page<TrackRef> search(String q, int page) throws Exception {
    JsonObject r =
        rpc(
            "music.search.SearchCgiService",
            "DoSearchForQQMusicDesktop",
            Map.of(
                "search_type",
                0,
                "query",
                q,
                "num_per_page",
                50,
                "page_num",
                page,
                "highlight",
                0,
                "grp",
                1,
                "searchid",
                Long.toUnsignedString(new Random().nextLong() & 0x7ffffffffffffffL)));
    JsonArray songs = a(r, "body.item_song");
    if (songs.isEmpty()) songs = a(r, "body.song.list");
    if (songs.isEmpty()) songs = a(r, "song.list");
    List<TrackRef> out = new ArrayList<>();
    for (JsonElement s : songs) out.add(track(s));
    return new Page<>(out, songs.size() == 50);
  }

  public String cover(TrackRef t) throws Exception {
    JsonObject detail =
        rpc("music.pf_song_detail_svr", "get_song_detail_yqq", Map.of("song_mid", t.id()));
    JsonElement info = at(detail, "track_info");
    if (!s(info, "mid").equals(t.id()) && !s(info, "songmid").equals(t.id())) return "";
    return track(info).cover();
  }

  public Page<Playlist> playlists(int page) throws Exception {
    require(!s(auth, "uid").isEmpty(), "请先使用 QQ 扫码登录");
    List<Playlist> out = new ArrayList<>();
    if (page == 1) {
      JsonObject r =
          rpc(
              "music.musicasset.PlaylistBaseRead",
              "GetPlaylistByUin",
              Map.of("uin", s(auth, "uid")));
      for (JsonElement p : a(r, "v_playlist"))
        out.add(new Playlist(s(p, "tid"), s(p, "dirName"), (int) n(p, "songNum")));
    }
    JsonObject fav =
        rpc(
            "music.musicasset.PlaylistFavRead",
            "CgiGetPlaylistFavInfo",
            Map.of("uin", s(auth, "encryptUin"), "offset", (page - 1) * 50, "size", 50));
    JsonArray list = a(fav, "v_list");
    if (list.isEmpty()) list = a(fav, "v_playlist");
    for (JsonElement p : list) {
      String id = s(p, "tid");
      if (id.isEmpty()) id = s(p, "dissid");
      if (!id.isEmpty())
        out.add(
            new Playlist(
                id,
                s(p, "dissname").isEmpty() ? s(p, "dirName") : s(p, "dissname"),
                (int) n(p, "songnum")));
    }
    return new Page<>(out, n(fav, "hasmore") != 0);
  }

  public Page<TrackRef> tracks(String playlist, int page) throws Exception {
    JsonObject r =
        rpc(
            "music.srfDissInfo.DissInfo",
            "CgiGetDiss",
            Map.of(
                "disstid",
                Long.parseLong(playlist),
                "dirid",
                0,
                "song_begin",
                (page - 1) * 100,
                "song_num",
                100,
                "tag",
                1,
                "userinfo",
                1,
                "orderlist",
                1));
    List<TrackRef> out = new ArrayList<>();
    for (JsonElement t : a(r, "songlist")) out.add(track(t));
    return new Page<>(out, n(r, "hasmore") != 0);
  }

  protected Source resolveSource(TrackRef t) throws Exception {
    String media = t.id() + t.id();
    try {
      JsonObject detail =
          rpc("music.pf_song_detail_svr", "get_song_detail_yqq", Map.of("song_mid", t.id()));
      String resolved = s(detail, "track_info.file.media_mid");
      if (!resolved.isEmpty()) media = resolved;
    } catch (java.io.IOException ignored) {
    }
    List<String> formats =
        compactRequested()
            ? List.of("M800.mp3")
            : List.of("RS01.flac", "F000.flac", "M800.mp3", "M500.mp3");
    List<String> filenames = new ArrayList<>();
    for (String f : formats) filenames.add(f.substring(0, 4) + media + f.substring(4));
    JsonObject result =
        rpc(
            "music.vkey.GetVkey",
            "UrlGetVkey",
            Map.of(
                "uin",
                s(auth, "uid"),
                "filename",
                filenames,
                "guid",
                UUID.randomUUID().toString().replace("-", ""),
                "songmid",
                Collections.nCopies(formats.size(), t.id()),
                "songtype",
                Collections.nCopies(formats.size(), 0),
                "ctx",
                0));
    JsonArray info = a(result, "midurlinfo"), sip = a(result, "sip");
    for (JsonElement item : info) {
      String path = s(item, "purl");
      if (!path.isEmpty())
        try {
          return checkedSource(
              (sip.isEmpty() ? "https://isure.stream.qqmusic.qq.com/" : sip.get(0).getAsString())
                  + path,
              t.durationMs(),
              path.contains(".flac"));
        } catch (java.io.IOException ignored) {
        }
    }
    throw new java.io.IOException("QQ 未授权原始无损或MP3完整音源，请检查版权与会员权限");
  }

  public Lyrics lyrics(TrackRef t) throws Exception {
    // Request unencrypted LRC fallback if the upstream QRC format cannot be decoded.
    String lyric = "", trans = "";
    try {
      JsonObject r =
          rpc(
              "music.musichallSong.PlayLyricInfo",
              "GetPlayLyricInfo",
              Map.of(
                  "songMid", t.id(), "crypt", 1, "lrc_t", 0, "qrc", 1, "qrc_t", 0, "trans", 1,
                  "trans_t", 0, "roma", 0, "type", 1),
              Map.of("ct", "11", "cv", "20090008", "v", "20090008"));
      lyric = Lyrics.decodeQq(s(r, "lyric"));
      trans = Lyrics.decodeQq(s(r, "trans"));
    } catch (java.io.IOException ignored) {
    }
    if (lyric.isEmpty()) {
      JsonObject l =
          parse(
              request(
                  "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?"
                      + form(Map.of("songmid", t.id(), "format", "json", "nobase64", 1)),
                  "GET",
                  "",
                  Map.of("Referer", "https://y.qq.com/")));
      lyric = s(l, "lyric");
      trans = s(l, "trans");
    }
    return Lyrics.parse(lyric, trans, lyric.contains("LyricContent=") ? "qrc" : "lrc");
  }

  protected boolean validateAccount() throws Exception {
    try {
      JsonObject result = rpc("music.UserInfo.userInfoServer", "GetLoginUserInfo", Map.of());
      return n(result, "code") == 0;
    } catch (java.io.IOException e) {
      if (e.getMessage().contains("1000")
          || e.getMessage().contains("104401")
          || e.getMessage().contains("104400")) return false;
      throw e;
    }
  }
}
