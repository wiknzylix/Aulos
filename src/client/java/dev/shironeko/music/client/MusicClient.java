package dev.shironeko.music.client;

import com.google.gson.*;
import com.mojang.blaze3d.platform.InputConstants;
import dev.shironeko.music.*;
import dev.shironeko.music.core.*;
import dev.shironeko.music.net.*;
import dev.shironeko.music.provider.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.client.rendering.v1.hud.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public final class MusicClient implements ClientModInitializer {
  public static final Path FOLDER = FabricLoader.getInstance().getConfigDir().resolve("aulos"),
      SETTINGS = FOLDER.resolve("settings.json");
  public static ClientConfig config;
  public static Map<String, MusicProvider> providers;
  private static final ThreadPoolExecutor WORK =
      new ThreadPoolExecutor(
          4,
          4,
          30,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(64),
          r -> {
            Thread t = new Thread(r, "Aulos IO");
            t.setDaemon(true);
            return t;
          });
  private static QueueEngine solo;
  private static volatile SessionSnapshot remote;
  public static boolean together = true,
      wantsTogether = true,
      serverAvailable = false,
      roomActive = false;
  public static Map<String, String> roster = new LinkedHashMap<>();
  public static AudioEngine audio;
  public static volatile Lyrics lyrics = Lyrics.empty();
  private static UUID lyricTrack;
  private static long lyricGeneration, offset, bestRtt = Long.MAX_VALUE, clockAt = 0;
  private static final ArrayDeque<long[]> samples = new ArrayDeque<>();
  private static final ArrayDeque<JsonObject> operations = new ArrayDeque<>();
  private static boolean awaiting = false, keyDown = false;
  private static String awaitingOperation = "";
  private static String pendingMode = "";
  private static long sentAt = 0, remoteRevision = -1;
  private static String assemblyId = "";
  private static long assemblyRevision = -1;
  private static String[] parts;
  private static int localFailed = 0;
  private static boolean clockReady;
  private static long transportGeneration;
  public static volatile String feedback = "";
  public static int feedbackColour = 0xFFAAAAAA;
  public static long accountsRevision;
  private static long feedbackAt, bufferSince;

  public static long now() {
    return MusicTogether.now();
  }

  public static <T> java.util.concurrent.Future<?> async(Callable<T> work, Consumer<T> success) {
    try {
      return WORK.submit(
          () -> {
            try {
              T value = work.call();
              Minecraft.getInstance().execute(() -> success.accept(value));
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } catch (Exception e) {
              Minecraft.getInstance()
                  .execute(
                      () ->
                          message(
                              e instanceof java.io.IOException
                                      || e instanceof IllegalArgumentException
                                  ? e.getMessage()
                                  : "操作失败：" + e.getClass().getSimpleName()));
            }
          });
    } catch (RejectedExecutionException e) {
      message("后台任务较多，请稍后重试");
      return java.util.concurrent.CompletableFuture.failedFuture(e);
    }
  }

  public static void message(String text) {
    feedbackColour =
        text != null
                && (text.contains("失败")
                    || text.contains("无效")
                    || text.contains("冲突")
                    || text.contains("不可")
                    || text.contains("过期")
                    || text.contains("超时")
                    || text.contains("权限")
                    || text.contains("错误"))
            ? 0xFFFF5555
            : 0xFFAAAAAA;
    feedback = Objects.toString(text, "");
    feedbackAt = now();
  }

  public static SessionSnapshot state() {
    return together ? remote : solo == null ? null : solo.snapshot();
  }

  public static long position() {
    SessionSnapshot s = state();
    return s == null || together && !clockReady
        ? 0
        : audio != null && !Boolean.getBoolean("aulos.test.noAudio")
            ? audio.visiblePosition(s.current())
            : s.timeline().position(now() + (together ? offset : 0));
  }

  public static String mode() {
    return together ? "一起听" : "单人";
  }

  public static void accountChanged(MusicProvider provider) {
    ++accountsRevision;
    if (RelayClient.sources != null) RelayClient.sources.clear();
    RelayClient.repository().invalidate(provider.id());
    ++transportGeneration;
    MusicScreen.clearCache();
    SessionSnapshot snapshot = state();
    if (snapshot != null
        && snapshot.currentEntry() != null
        && snapshot.currentEntry().track().platform().equals(provider.id())) {
      lyricTrack = null;
    }
  }

  @Override
  public void onInitializeClient() {
    config = ClientConfig.load(SETTINGS);
    providers = new LinkedHashMap<>();
    for (MusicProvider p : List.of(new Netease(FOLDER), new QQ(FOLDER), new Kugou(FOLDER)))
      providers.put(p.id(), p);
    RelayClient.initialize();
    ClientPlayNetworking.registerGlobalReceiver(RelayPacket.TYPE, (packet, ctx) -> {});
    ClientPlayNetworking.registerGlobalReceiver(
        MusicPacket.TYPE, (packet, ctx) -> ctx.client().execute(() -> receive(packet.json())));
    ClientPlayConnectionEvents.JOIN.register(
        (handler, sender, client) -> {
          solo = new QueueEngine(client.player.getUUID());
          serverAvailable = ClientPlayNetworking.canSend(MusicPacket.TYPE);
          RelayClient.connect(handler, client.player.getUUID());
          together = serverAvailable;
          wantsTogether = serverAvailable;
          sentAt = now();
          clockReady = false;
          samples.clear();
          offset = 0;
          ++transportGeneration;
          if (serverAvailable) {
            raw(Map.of("type", "clock", "time", now()));
            clockAt = now();
            raw(Map.of("type", "hello"));
          } else message("服务器未安装兼容版本 Aulos，当前使用单人模式");
        });
    ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    ClientTickEvents.END_CLIENT_TICK.register(MusicClient::tick);
    ClientLifecycleEvents.CLIENT_STOPPING.register(
        c -> {
          if (audio != null) audio.close();
          RelayClient.close();
          WORK.shutdownNow();
          config.save(SETTINGS);
        });
    HudElementRegistry.attachElementBefore(
        VanillaHudElements.CHAT,
        Identifier.fromNamespaceAndPath("aulos", "music"),
        (g, d) -> {
          if (!Minecraft.getInstance().options.hideGui && Minecraft.getInstance().level != null)
            MusicHud.render(g, false);
        });
  }

  private static void tick(Minecraft c) {
    if (c.level == null) return;
    if (solo == null) solo = new QueueEngine(c.player.getUUID());
    if (!together) solo.tick(now());
    if (!Boolean.getBoolean("aulos.test.noAudio")) {
      if (audio == null)
        audio =
            new AudioEngine(
                providers::get,
                (id, epoch, ok, generation) ->
                    c.execute(
                        () -> {
                          if (generation == transportGeneration) ready(id, epoch, ok);
                        }),
                FOLDER.resolve("cache"),
                RelayClient.repository());
      audio.device(c.options.soundDevice().get());
      audio.update(
          together && !clockReady ? null : state(),
          together ? offset : 0,
          config.muted ? 0 : config.volume,
          transportGeneration);
      if (together && !clockReady) audio.status = "正在校准共听时钟";
      SessionSnapshot active = state();
      if (together
          && audio.buffering
          && active != null
          && active.current() != null
          && RelayClient.repository().forwarded(active.current())) {
        if (bufferSince == 0) bufferSince = now();
        else if (now() - bufferSince > 1800 && RelayClient.retryCompact(active.current())) {
          ++transportGeneration;
          bufferSince = 0;
          message("网络较慢，尝试高码率MP3转发");
        }
      } else bufferSince = 0;
    }
    SessionSnapshot s = state();
    UUID id = s == null ? null : s.current();
    if (!Objects.equals(id, lyricTrack)) {
      lyricTrack = id;
      lyrics = Lyrics.empty();
      long gen = ++lyricGeneration;
      QueueEntry entry = s == null ? null : s.currentEntry();
      if (entry != null && !Boolean.getBoolean("aulos.test.noAudio"))
        async(
            () -> providers.get(entry.track().platform()).lyrics(entry.track()),
            value -> {
              if (gen == lyricGeneration) lyrics = value;
            });
    }
    if (serverAvailable && now() - clockAt > (clockReady ? 2000 : 200)) {
      clockAt = now();
      raw(Map.of("type", "clock", "time", now()));
    }
    if (serverAvailable && !pendingMode.isEmpty() && now() - sentAt > 150) {
      raw(Map.of("type", pendingMode));
      pendingMode = "";
      sentAt = now();
    }
    if (together && !awaiting && !operations.isEmpty() && now() - sentAt > 130 && remote != null) {
      JsonObject op = operations.removeFirst();
      op.addProperty("session", remote.sessionId().toString());
      op.addProperty("revision", remote.revision());
      awaitingOperation = UUID.randomUUID().toString();
      op.addProperty("operation", awaitingOperation);
      ClientPlayNetworking.send(new MusicPacket(MusicTogether.JSON.toJson(op)));
      awaiting = true;
      sentAt = now();
    }
    if (awaiting && now() - sentAt > 10000) {
      awaiting = false;
      operations.clear();
      message("共听操作超时，请重试");
    }
    boolean down = InputConstants.isKeyDown(c.getWindow(), config.key);
    int mods =
        (held(c, GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT) ? 1 : 0)
            | (held(c, GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL) ? 2 : 0)
            | (held(c, GLFW.GLFW_KEY_LEFT_ALT, GLFW.GLFW_KEY_RIGHT_ALT) ? 4 : 0);
    if (down && !keyDown && mods == config.modifiers && c.screen == null)
      c.setScreen(new MusicScreen(null));
    keyDown = down;
    if (now() - feedbackAt > 15000) feedback = "";
  }

  private static boolean held(Minecraft c, int a, int b) {
    return InputConstants.isKeyDown(c.getWindow(), a) || InputConstants.isKeyDown(c.getWindow(), b);
  }

  private static void ready(UUID id, long epoch, boolean ok) {
    SessionSnapshot s = state();
    if (s == null || !Objects.equals(s.current(), id)) return;
    if (together) raw(Map.of("type", "ready", "entry", id.toString(), "epoch", epoch, "ok", ok));
    else if (s.timeline().epochMs() != epoch || !s.timeline().status().equals("PREPARING")) return;
    else if (ok) {
      solo.ready(now(), 100);
      localFailed = 0;
    } else if (++localFailed >= s.queue().size()) {
      solo.stop();
      message("队列歌曲均无法播放，已停止");
    } else solo.next(now());
  }

  private static void raw(Object value) {
    if (ClientPlayNetworking.canSend(MusicPacket.TYPE))
      ClientPlayNetworking.send(new MusicPacket(MusicTogether.JSON.toJson(value)));
  }

  public static void enter(boolean create) {
    if (!serverAvailable) {
      message("服务器未安装 Aulos，当前可使用单人模式");
      return;
    }
    wantsTogether = true;
    together = true;
    RelayClient.receiving(true);
    ++transportGeneration;
    raw(Map.of("type", "clock", "time", now()));
    if (solo != null) solo.freeze(now());
    pendingMode = "join";
  }

  public static void toggleMode() {
    if (together || wantsTogether) leave();
    else enter(false);
  }

  public static void refreshRoster() {
    raw(Map.of("type", "roster"));
  }

  public static void leave() {
    wantsTogether = false;
    if (together) pendingMode = "leave";
    resumeSolo();
  }

  private static void resumeSolo() {
    together = false;
    RelayClient.receiving(false);
    ++transportGeneration;
    remote = null;
    operations.clear();
    awaiting = false;
    lyricTrack = null;
    if (solo != null && solo.snapshot().timeline().status().equals("PAUSED")) solo.toggle(now());
  }

  private static void receive(String text) {
    try {
      JsonObject j = JsonParser.parseString(text).getAsJsonObject();
      switch (Web.s(j, "type")) {
        case "relayPolicy" -> {
          QueueEntry[] entries = MusicTogether.JSON.fromJson(j.get("entries"), QueueEntry[].class);
          if (entries.length <= 2)
            RelayClient.policy(
                UUID.fromString(Web.s(j, "session")), List.of(entries), wantsTogether);
        }
        case "hello" -> {
          serverAvailable = true;
          roomActive = Web.at(j, "active").getAsBoolean();
        }
        case "clock" -> {
          long time = now(),
              echo = Web.n(j, "echo"),
              rtt = time - echo,
              estimate = Web.n(j, "server") - (echo + rtt / 2);
          if (rtt >= 0 && rtt < 10000) {
            samples.add(new long[] {rtt, estimate});
            while (samples.size() > 20) samples.removeFirst();
            long[] best = samples.stream().min(Comparator.comparingLong(a -> a[0])).orElseThrow();
            long previous = offset;
            offset = best[1];
            if (!clockReady || Math.abs(previous - offset) > 180) ++transportGeneration;
            clockReady = true;
            bestRtt = best[0];
          }
        }
        case "ended" -> {
          resumeSolo();
          roomActive = false;
          message("一起听已结束");
        }
        case "left" -> {
          if (!wantsTogether) resumeSolo();
        }
        case "roster" -> {
          Map<String, String> people = new LinkedHashMap<>();
          var object = Web.at(j, "people");
          if (object.isJsonObject())
            for (var e : object.getAsJsonObject().entrySet())
              people.put(e.getKey(), e.getValue().getAsString());
          roster = people;
        }
        case "error" -> {
          operations.clear();
          awaiting = false;
          message(Web.s(j, "message"));
        }
        case "ack" -> {
          if (awaitingOperation.equals(Web.s(j, "operation"))) awaiting = false;
        }
        case "state" -> {
          String session = Web.s(j, "session");
          long revision = Web.n(j, "revision");
          int total = (int) Web.n(j, "total"), part = (int) Web.n(j, "part");
          if (total < 1 || total > 1000 || part < 0 || part >= total) return;
          if (remote != null
              && remote.sessionId().toString().equals(session)
              && revision < remote.revision()) return;
          if (!assemblyId.equals(session) || assemblyRevision != revision) {
            assemblyId = session;
            assemblyRevision = revision;
            parts = new String[total];
          }
          if (parts.length != total) return;
          parts[part] = Web.s(j, "data");
          if (Arrays.stream(parts).anyMatch(Objects::isNull)) return;
          SessionSnapshot next =
              MusicTogether.JSON.fromJson(String.join("", parts), SessionSnapshot.class);
          if (!next.sessionId().toString().equals(session)
              || next.revision() != revision
              || next.queue().size() > 10000) return;
          if (!wantsTogether) return;
          remote = next;
          together = true;
          roomActive = true;
        }
      }
    } catch (Exception e) {
      message("共听协议数据无效");
    }
  }

  public static void op(String type) {
    op(type, Map.of());
  }

  public static void op(String type, Map<String, ?> data) {
    JsonObject j = MusicTogether.JSON.toJsonTree(data).getAsJsonObject();
    j.addProperty("type", type);
    if ((type.equals("prioritize") || type.equals("remove"))
        && (!j.has("ids") || !j.get("ids").isJsonArray() || j.getAsJsonArray("ids").isEmpty())) {
      message("请选择一首歌曲");
      return;
    }
    if (together) {
      if (operations.size() >= 10000) {
        message("待提交操作过多");
        return;
      }
      operations.add(j);
      return;
    }
    if (solo == null) {
      message("请先进入世界");
      return;
    }
    try {
      long time = now();
      switch (type) {
        case "add", "addNext" -> {
          message("已将歌曲加入队列");
          feedbackColour = 0xFF55FF55;
          solo.add(
              List.of(MusicTogether.JSON.fromJson(j.get("tracks"), TrackRef[].class)),
              type.equals("addNext"),
              time);
          localFailed = 0;
        }
        case "prioritize" ->
            solo.prioritize(List.of(MusicTogether.JSON.fromJson(j.get("ids"), UUID[].class)));
        case "remove" -> {
          solo.remove(List.of(MusicTogether.JSON.fromJson(j.get("ids"), UUID[].class)), time);
          message("已从队列移除歌曲");
          feedbackColour = 0xFFFF5555;
        }
        case "play" -> {
          solo.play(UUID.fromString(Web.s(j, "id")), time);
          localFailed = 0;
        }
        case "next" -> {
          solo.next(time);
          message("已切歌");
          feedbackColour = 0xFFFFFF55;
          localFailed = 0;
        }
        case "previous" -> solo.previous(time);
        case "toggle" -> solo.toggle(time);
        case "clear" -> solo.clear();
        case "repeat" -> solo.repeat(Web.s(j, "mode"));
      }
    } catch (Exception e) {
      message(e.getMessage());
    }
  }

  public static void add(List<TrackRef> tracks, boolean next) {
    if (tracks.isEmpty()) {
      message("请选择一首歌曲");
      return;
    }
    if (tracks.size() > 10000) {
      message("一次最多添加10000首");
      return;
    }
    if (together) {
      List<List<TrackRef>> chunks = new ArrayList<>();
      List<TrackRef> chunk = new ArrayList<>();
      for (TrackRef track : tracks) {
        chunk.add(track);
        if (chunk.size() > 12
            || MusicTogether.JSON
                    .toJson(chunk)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    .length
                > 22000) {
          chunk.removeLast();
          chunks.add(List.copyOf(chunk));
          chunk.clear();
          chunk.add(track);
        }
      }
      if (!chunk.isEmpty()) chunks.add(List.copyOf(chunk));
      if (chunks.size() + operations.size() >= 100000) {
        message("待提交操作过多，请稍后");
        return;
      }
      String batch = UUID.randomUUID().toString();
      for (int i = 0; i < chunks.size(); i++)
        op(
            next ? "addNext" : "add",
            Map.of("tracks", chunks.get(i), "batch", batch, "index", i, "total", chunks.size()));
    } else op(next ? "addNext" : "add", Map.of("tracks", tracks));
  }

  public static String keyName() {
    String key = GLFW.glfwGetKeyName(config.key, 0);
    if (key == null) key = "键 " + config.key;
    return ((config.modifiers & 2) != 0 ? "Ctrl+" : "")
        + ((config.modifiers & 4) != 0 ? "Alt+" : "")
        + ((config.modifiers & 1) != 0 ? "Shift+" : "")
        + key.toUpperCase(Locale.ROOT);
  }

  private static void clear() {
    MusicScreen.clearCache();
    RelayClient.disconnect();
    solo = null;
    remote = null;
    together = true;
    wantsTogether = true;
    roster = new LinkedHashMap<>();
    serverAvailable = false;
    roomActive = false;
    pendingMode = "";
    operations.clear();
    awaiting = false;
    parts = null;
    assemblyId = "";
    samples.clear();
    clockReady = false;
    ++transportGeneration;
    offset = 0;
    lyricTrack = null;
    ++lyricGeneration;
    lyrics = Lyrics.empty();
    if (audio != null) audio.clear();
    MusicHud.clear();
  }
}
