package dev.shironeko.music.provider;

import com.google.gson.*;
import dev.shironeko.music.MusicTogether;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/** One cookie jar per provider. Used exclusively on background workers. */
public abstract class Web implements MusicProvider {
  protected final CookieManager jar = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
  protected final HttpClient http =
      HttpClient.newBuilder()
          .cookieHandler(jar)
          .connectTimeout(Duration.ofSeconds(12))
          .followRedirects(HttpClient.Redirect.NORMAL)
          .build();
  protected final Path file;
  protected volatile JsonObject auth = new JsonObject();
  private volatile boolean expired;

  private record CachedSource(Source source, long at) {}

  private final Map<String, CachedSource> sources = new LinkedHashMap<>();
  private long accountGeneration;
  private static final ThreadLocal<Long> SOURCE_DEADLINE = new ThreadLocal<>();

  protected Web(Path folder, String id) {
    file = folder.resolve(id + "-account.json");
    try {
      if (Files.exists(file)) {
        JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        auth = saved.getAsJsonObject("auth");
        for (JsonElement e : saved.getAsJsonArray("cookies")) {
          JsonObject c = e.getAsJsonObject();
          HttpCookie ck = new HttpCookie(s(c, "name"), s(c, "value"));
          ck.setVersion(0);
          ck.setDomain(s(c, "domain"));
          ck.setPath("/");
          jar.getCookieStore()
              .add(URI.create("https://" + s(c, "domain").replaceFirst("^\\.", "")), ck);
        }
      }
    } catch (Exception ignored) {
      auth = new JsonObject();
    }
  }

  public String account() {
    if (expired) return "登录已过期";
    return s(auth, "name").isEmpty() ? (s(auth, "uid").isEmpty() ? "未登录" : "已登录") : s(auth, "name");
  }

  protected synchronized void save() throws Exception {
    sources.clear();
    accountGeneration++;
    expired = false;
    Files.createDirectories(file.getParent());
    JsonObject root = new JsonObject();
    root.add("auth", auth);
    JsonArray cs = new JsonArray();
    for (HttpCookie c : jar.getCookieStore().getCookies()) {
      JsonObject j = new JsonObject();
      j.addProperty("name", c.getName());
      j.addProperty("value", c.getValue());
      j.addProperty("domain", c.getDomain());
      cs.add(j);
    }
    root.add("cookies", cs);
    Path temp = file.resolveSibling(file.getFileName() + ".tmp");
    Files.writeString(temp, MusicTogether.JSON.toJson(root));
    try {
      Files.setPosixFilePermissions(
          temp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException ignored) {
    }
    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
  }

  public synchronized void logout() throws Exception {
    sources.clear();
    accountGeneration++;
    expired = false;
    auth = new JsonObject();
    jar.getCookieStore().removeAll();
    Files.deleteIfExists(file);
  }

  public boolean loggedIn() {
    return !s(auth, "uid").isEmpty() && !expired;
  }

  protected abstract boolean validateAccount() throws Exception;

  public final AccountStatus refreshAccount() throws Exception {
    if (s(auth, "uid").isEmpty()) return new AccountStatus(false, "未登录");
    boolean valid = validateAccount();
    expired = !valid;
    if (!valid)
      synchronized (this) {
        sources.clear();
        accountGeneration++;
      }
    return new AccountStatus(valid, valid ? "已登录 " + account() : "登录已过期，请重新扫码");
  }

  private final ThreadLocal<Boolean> compact = ThreadLocal.withInitial(() -> false);

  protected boolean compactRequested() {
    return compact.get();
  }

  public final Source compactSource(dev.shironeko.music.core.TrackRef track) throws Exception {
    compact.set(true);
    try {
      return source(track);
    } finally {
      compact.remove();
    }
  }

  protected abstract Source resolveSource(dev.shironeko.music.core.TrackRef track) throws Exception;

  public final Source source(dev.shironeko.music.core.TrackRef track) throws Exception {
    String cacheId = track.id() + (compactRequested() ? ":compact" : "");
    long stamp;
    synchronized (this) {
      CachedSource cached = sources.get(cacheId);
      if (cached != null && System.nanoTime() / 1000000 - cached.at < 120000) return cached.source;
      stamp = accountGeneration;
    }
    SOURCE_DEADLINE.set(System.nanoTime() / 1000000 + 6000);
    try {
      Source result = resolveSource(track);
      synchronized (this) {
        if (stamp == accountGeneration) {
          sources.put(cacheId, new CachedSource(result, System.nanoTime() / 1000000));
          while (sources.size() > 4) sources.remove(sources.keySet().iterator().next());
        }
      }
      return result;
    } finally {
      SOURCE_DEADLINE.remove();
    }
  }

  public synchronized void invalidateSource(dev.shironeko.music.core.TrackRef track) {
    sources.remove(track.id());
    sources.remove(track.id() + ":compact");
  }

  private static Duration timeout(long normal) throws java.io.IOException {
    Long deadline = SOURCE_DEADLINE.get();
    long left =
        deadline == null ? normal : Math.min(normal, deadline - System.nanoTime() / 1000000);
    if (left <= 0) throw new java.io.IOException("音源解析超时，请重试");
    return Duration.ofMillis(left);
  }

  protected String cookie(String name) {
    return jar.getCookieStore().getCookies().stream()
        .filter(c -> c.getName().equals(name))
        .map(HttpCookie::getValue)
        .findFirst()
        .orElse("");
  }

  protected byte[] request(String url, String method, String body, Map<String, String> headers)
      throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout(20000))
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/123.0.0.0"
                    + " Safari/537.36");
    headers.forEach(b::header);
    if (method.equals("POST")) b.POST(HttpRequest.BodyPublishers.ofString(body));
    else b.GET();
    HttpResponse<java.io.InputStream> r =
        http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
    try (var in = r.body()) {
      if (r.statusCode() < 200 || r.statusCode() >= 300)
        throw new java.io.IOException("平台 HTTP " + r.statusCode());
      byte[] data = in.readNBytes(8 * 1024 * 1024 + 1);
      if (data.length > 8 * 1024 * 1024) throw new java.io.IOException("平台响应过大");
      return data;
    }
  }

  protected JsonObject get(String url) throws Exception {
    return parse(request(url, "GET", "", Map.of()));
  }

  protected static JsonObject parse(byte[] bytes) throws Exception {
    try {
      return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
    } catch (Exception e) {
      throw new java.io.IOException("平台返回非 JSON，可能需要验证或接口已变化");
    }
  }

  protected static String form(Map<String, ?> map) {
    return map.entrySet().stream()
        .map(e -> enc(e.getKey()) + "=" + enc(String.valueOf(e.getValue())))
        .collect(java.util.stream.Collectors.joining("&"));
  }

  protected static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  public static JsonElement at(JsonElement e, String path) {
    for (String key : path.split("\\.")) {
      if (e == null || !e.isJsonObject()) return JsonNull.INSTANCE;
      e = e.getAsJsonObject().get(key);
    }
    return e == null ? JsonNull.INSTANCE : e;
  }

  public static String s(JsonElement e, String path) {
    try {
      return at(e, path).getAsString();
    } catch (Exception ignored) {
      return "";
    }
  }

  public static long n(JsonElement e, String path) {
    try {
      return at(e, path).getAsLong();
    } catch (Exception ignored) {
      return 0;
    }
  }

  public static JsonArray a(JsonElement e, String path) {
    JsonElement x = at(e, path);
    return x.isJsonArray() ? x.getAsJsonArray() : new JsonArray();
  }

  public static String names(JsonArray a) {
    return java.util.stream.StreamSupport.stream(a.spliterator(), false)
        .map(e -> s(e, "name"))
        .collect(java.util.stream.Collectors.joining(" / "));
  }

  protected static void require(boolean ok, String message) throws java.io.IOException {
    if (!ok) throw new java.io.IOException(message);
  }

  protected static Source sourceUrl(String url, long duration) throws Exception {
    require(url.startsWith("http"), "未取得完整音源：账号权限不足或接口已变化");
    URI uri = URI.create(url.replaceFirst("^http:", "https:"));
    return new Source(uri, duration);
  }

  protected Source checkedSource(String url, long duration) throws Exception {
    return checkedSource(url, duration, false);
  }

  protected Source checkedSource(String url, long duration, boolean mustBeFlac) throws Exception {
    Source src = sourceUrl(url, duration);
    var response =
        http.send(
            HttpRequest.newBuilder(src.uri())
                .timeout(timeout(1200))
                .header("Range", compactRequested() ? "bytes=0-65535" : "bytes=0-63")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());
    try (var input = new java.io.BufferedInputStream(response.body())) {
      input.mark(65536);
      require(
          response.statusCode() == 200 || response.statusCode() == 206,
          "音源不可用 HTTP " + response.statusCode());
      byte[] magic = input.readNBytes(4);
      boolean flac =
          magic.length == 4
              && magic[0] == 'f'
              && magic[1] == 'L'
              && magic[2] == 'a'
              && magic[3] == 'C';
      boolean mp3 =
          magic.length == 4
              && ((magic[0] == 'I' && magic[1] == 'D' && magic[2] == '3')
                  || ((magic[0] & 255) == 255 && (magic[1] & 224) == 224));
      require(flac || mp3, "平台返回加密或非原始FLAC/MP3音源");
      require(!mustBeFlac || flac, "平台未返回所请求的原始无损格式");
      if (compactRequested()) {
        require(mp3, "平台未返回原始MP3");
        input.reset();
        var frames = new javazoom.jl.decoder.Bitstream(input);
        var header = frames.readFrame();
        require(header != null && header.bitrate() >= 192000, "没有192kbps以上的原始MP3");
        frames.close();
      }
      return src;
    }
  }
}
