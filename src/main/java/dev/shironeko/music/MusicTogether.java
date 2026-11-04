package dev.shironeko.music;

import com.google.gson.*;
import dev.shironeko.music.core.*;
import dev.shironeko.music.net.*;
import java.util.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class MusicTogether implements ModInitializer {
  public static final Gson JSON = new Gson();
  public static final String VERSION =
      net.fabricmc.loader.api.FabricLoader.getInstance()
          .getModContainer("aulos")
          .map(c -> c.getMetadata().getVersion().getFriendlyString())
          .orElse("dev");
  private static final Map<MinecraftServer, Room> ROOMS = new IdentityHashMap<>();
  private static ServerConfig config;

  public static long now() {
    return System.nanoTime() / 1_000_000;
  }

  private static final class Room {
    QueueEngine engine;
    long sent = -1, prepareAt, prepareEpoch = Long.MIN_VALUE;
    UUID preparing;
    int failed;
    final Map<UUID, Boolean> ready = new HashMap<>();
    final LinkedHashSet<String> seen = new LinkedHashSet<>();
    final Map<UUID, Rate> rate = new HashMap<>();
    final Map<UUID, Batch> batches = new HashMap<>();
  }

  private static final class Rate {
    double tokens = 8;
    long time = now();

    boolean accept(long at) {
      tokens = Math.min(8, tokens + (at - time) / 100.0);
      time = at;
      if (tokens < 1) return false;
      tokens--;
      return true;
    }
  }

  private static final class Batch {
    String id, type;
    int total, index;
    long touched;
    final List<TrackRef> tracks = new ArrayList<>();
  }

  @Override
  public void onInitialize() {
    config = ServerConfig.load();
    PayloadTypeRegistry.playC2S().register(MusicPacket.TYPE, MusicPacket.CODEC);
    PayloadTypeRegistry.playS2C().register(MusicPacket.TYPE, MusicPacket.CODEC);
    PayloadTypeRegistry.playC2S().register(RelayPacket.TYPE, RelayPacket.CODEC);
    PayloadTypeRegistry.playS2C().register(RelayPacket.TYPE, RelayPacket.CODEC);
    // Advertise support. The audio-only mixin handles this before main-thread dispatch.
    ServerPlayNetworking.registerGlobalReceiver(RelayPacket.TYPE, (packet, ctx) -> {});
    ServerPlayNetworking.registerGlobalReceiver(
        MusicPacket.TYPE,
        (packet, ctx) ->
            ctx.server().execute(() -> receive(ctx.server(), ctx.player(), packet.json())));
    ServerTickEvents.END_SERVER_TICK.register(MusicTogether::tick);
    ServerPlayConnectionEvents.DISCONNECT.register(
        (handler, server) -> disconnect(server, handler.player));
    ServerLifecycleEvents.SERVER_STOPPED.register(
        s -> {
          ROOMS.remove(s);
          ServerRelay.stop(s);
        });
  }

  private static void send(ServerPlayer p, Object value) {
    if (ServerPlayNetworking.canSend(p, MusicPacket.TYPE))
      ServerPlayNetworking.send(p, new MusicPacket(JSON.toJson(value)));
  }

  private static void error(ServerPlayer p, String msg) {
    send(p, Map.of("type", "error", "message", msg));
  }

  private static void receive(MinecraftServer server, ServerPlayer player, String text) {
    try {
      JsonObject j = JsonParser.parseString(text).getAsJsonObject();
      String op = j.get("type").getAsString();
      if (op.equals("clock")) {
        send(player, Map.of("type", "clock", "echo", j.get("time").getAsLong(), "server", now()));
        return;
      }
      Room r = ROOMS.computeIfAbsent(server, s -> new Room());
      if (op.equals("roster")) {
        sendRoster(server, r, player);
        return;
      }
      if (op.equals("hello")) {
        if (ServerPlayNetworking.canSend(player, RelayPacket.TYPE))
          ServerRelay.bind(server, player.connection);
        if (r.engine == null) {
          UUID owner =
              server.isSingleplayer() ? server.getSingleplayerProfile().id() : config.host();
          r.engine = new QueueEngine(owner);
          if (!owner.equals(player.getUUID())) r.engine.leave(owner);
          r.sent = -1;
        }
        r.engine.join(player.getUUID());
        send(player, Map.of("type", "hello", "active", true));
        notice(server, r, player, "加入了一起听");
        flush(server, r);
        relayPolicy(server, r);
        return;
      }
      long time = now();
      UUID who = player.getUUID();
      if (op.equals("ready")) {
        if (r.engine != null
            && r.engine.member(who)
            && j.get("epoch").getAsLong() == r.prepareEpoch
            && Objects.equals(r.preparing, UUID.fromString(j.get("entry").getAsString())))
          r.ready.put(who, j.get("ok").getAsBoolean());
        return;
      }
      if (op.equals("leave")) {
        leave(server, player);
        return;
      }
      if (!r.rate.computeIfAbsent(who, id -> new Rate()).accept(time)) {
        error(player, "操作太快，请稍后");
        return;
      }
      if (op.equals("create")) {
        if (r.engine != null) throw new IllegalArgumentException("已有共听，请加入");
        r.engine = new QueueEngine(who);
        r.sent = -1;
        r.failed = 0;
        notice(server, r, player, "创建了一起听");
        flush(server, r);
        return;
      }
      if (r.engine == null) throw new IllegalArgumentException("当前没有一起听会话");
      if (op.equals("join")) {
        r.engine.join(who);
        r.sent = -1;
        notice(server, r, player, "加入了一起听");
        flush(server, r);
        return;
      }
      if (op.equals("leave")) {
        leave(server, player);
        return;
      }
      if (!r.engine.member(who)) throw new IllegalArgumentException("请先加入一起听");
      SessionSnapshot before = r.engine.snapshot();
      if (!before.sessionId().toString().equals(j.get("session").getAsString()))
        throw new IllegalArgumentException("会话已变化");
      String opId = j.get("operation").getAsString();
      String key = who + ":" + opId;
      if (!r.seen.add(key)) {
        send(player, Map.of("type", "ack", "operation", opId));
        return;
      }
      if (r.seen.size() > 4096) r.seen.remove(r.seen.iterator().next());
      if (j.get("revision").getAsLong() != before.revision()
          && !Set.of("add", "addNext", "prioritize", "repeat").contains(op)) {
        flushOne(player, before);
        throw new IllegalArgumentException("队列已更新，请重试");
      }
      String note;
      switch (op) {
        case "add", "addNext" -> {
          TrackRef[] tracks = JSON.fromJson(j.get("tracks"), TrackRef[].class);
          if (tracks.length > 12) throw new IllegalArgumentException("单次批次过大");
          List<TrackRef> all = List.of(tracks);
          if (j.has("batch")) {
            String batchId = j.get("batch").getAsString();
            int index = j.get("index").getAsInt(), total = j.get("total").getAsInt();
            if (total < 1 || total > 10000 || index < 0 || index >= total)
              throw new IllegalArgumentException("批量导入信息无效");
            Batch batch = r.batches.get(who);
            if (index == 0) {
              batch = new Batch();
              batch.id = batchId;
              batch.type = op;
              batch.total = total;
              r.batches.put(who, batch);
            }
            if (batch == null
                || !batch.id.equals(batchId)
                || !batch.type.equals(op)
                || batch.index != index
                || batch.total != total
                || time - batch.touched > 30000 && index > 0)
              throw new IllegalArgumentException("批量导入已取消，请重试");
            batch.tracks.addAll(all);
            batch.index++;
            batch.touched = time;
            if (batch.tracks.size() > QueueEngine.LIMIT) {
              r.batches.remove(who);
              throw new IllegalArgumentException("队列最多10000首");
            }
            if (batch.index < total) {
              send(player, Map.of("type", "ack", "operation", opId));
              return;
            }
            all = List.copyOf(batch.tracks);
            r.batches.remove(who);
          }
          r.engine.add(all, op.equals("addNext"), time, who);
          r.failed = 0;
          note = "添加了 " + all.size() + " 首歌曲" + (op.equals("addNext") ? "，安排下一首播放" : "");
        }
        case "prioritize" -> {
          r.engine.prioritize(ids(j));
          note = "调整了下一首播放";
        }
        case "remove" -> {
          r.engine.remove(ids(j), time);
          note = "移除了 " + ids(j).size() + " 首歌曲";
        }
        case "play" -> {
          r.engine.play(UUID.fromString(j.get("id").getAsString()), time);
          r.failed = 0;
          note = "立即播放了所选歌曲";
        }
        case "next" -> {
          r.engine.next(time);
          r.failed = 0;
          note = "切到下一首";
        }
        case "previous" -> {
          r.engine.previous(time);
          r.failed = 0;
          note = "切到上一首";
        }
        case "toggle" -> {
          r.engine.toggle(time + 350);
          note = "切换了暂停／播放";
        }
        case "clear" -> {
          r.engine.clear();
          r.failed = 0;
          note = "停止播放并清空了队列";
        }
        case "repeat" -> {
          r.engine.repeat(j.get("mode").getAsString());
          note = "切换了循环模式";
        }
        default -> throw new IllegalArgumentException("不支持的操作");
      }
      if (r.engine.snapshot().revision() != before.revision()) {
        notice(server, r, player, note);
        flush(server, r);
      }
      send(player, Map.of("type", "ack", "operation", opId));
    } catch (Exception e) {
      error(
          player,
          e instanceof IllegalArgumentException
              ? Objects.toString(e.getMessage(), "无效请求")
              : "请求格式错误");
    }
  }

  private static List<UUID> ids(JsonObject j) {
    UUID[] ids = JSON.fromJson(j.get("ids"), UUID[].class);
    if (ids.length > 10000) throw new IllegalArgumentException("批次过大");
    return List.of(ids);
  }

  private static void leave(MinecraftServer server, ServerPlayer p) {
    Room r = ROOMS.get(server);
    if (r == null || r.engine == null || !r.engine.member(p.getUUID())) return;
    r.batches.remove(p.getUUID());
    notice(server, r, p, "切换到单人模式，离开了一起听");
    r.engine.leave(p.getUUID());
    send(p, Map.of("type", "left"));
    flush(server, r);
    sendRoster(server, r, p);
  }

  private static void disconnect(MinecraftServer server, ServerPlayer p) {
    ServerRelay.disconnect(p.connection);
    Room r = ROOMS.get(server);
    if (r == null || r.engine == null) return;
    if (r.engine.snapshot().owner().equals(p.getUUID())) {
      notice(server, r, p, "作为服主退出，停止并清空了共听队列");
      r.engine.clear();
      r.batches.clear();
    }
    if (r.engine.member(p.getUUID())) {
      if (!r.engine.snapshot().owner().equals(p.getUUID())) notice(server, r, p, "退出了游戏，离开了一起听");
      r.engine.leave(p.getUUID());
    }
    r.batches.remove(p.getUUID());
    flush(server, r);
    relayPolicy(server, r);
  }

  private static void sendRoster(MinecraftServer server, Room r, ServerPlayer viewer) {
    Map<String, String> people = new LinkedHashMap<>();
    if (r.engine != null)
      for (UUID id : r.engine.snapshot().members()) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (p != null) people.put(id.toString(), p.getName().getString());
      }
    send(viewer, Map.of("type", "roster", "people", people));
  }

  private static void notice(MinecraftServer server, Room r, ServerPlayer actor, String msg) {
    for (UUID id : r.engine.snapshot().members()) {
      ServerPlayer p = server.getPlayerList().getPlayer(id);
      if (p != null)
        p.sendSystemMessage(
            Component.literal("[一起听] ")
                .withStyle(ChatFormatting.GRAY)
                .append(
                    Component.literal(actor.getName().getString() + " " + msg)
                        .withStyle(
                            msg.contains("添加")
                                ? ChatFormatting.GREEN
                                : msg.contains("移除") || msg.contains("清空")
                                    ? ChatFormatting.RED
                                    : msg.contains("切到")
                                            || msg.contains("播放了")
                                            || msg.contains("下一首")
                                        ? ChatFormatting.YELLOW
                                        : ChatFormatting.GRAY)));
    }
  }

  private static void tick(MinecraftServer s) {
    Room r = ROOMS.get(s);
    if (r == null || r.engine == null) return;
    long time = now();
    r.engine.tick(time);
    SessionSnapshot state = r.engine.snapshot();
    if (state.timeline().status().equals("PREPARING")) {
      if (!Objects.equals(state.current(), r.preparing)
          || state.timeline().epochMs() != r.prepareEpoch) {
        r.preparing = state.current();
        r.prepareEpoch = state.timeline().epochMs();
        r.prepareAt = time;
        r.ready.clear();
      }
      boolean allResponded =
          !state.members().isEmpty() && state.members().stream().allMatch(r.ready::containsKey);
      boolean anyReady = r.ready.values().stream().anyMatch(Boolean::booleanValue);
      if (PreparationGate.start(state.members(), r.ready, time - r.prepareAt)) {
        r.engine.ready(time, 400);
        QueueEntry current = state.currentEntry();
        if (current != null)
          for (UUID id : state.members()) {
            ServerPlayer p = s.getPlayerList().getPlayer(id);
            if (p != null)
              p.sendSystemMessage(
                  Component.literal("▶ 正在播放：" + current.track().label())
                      .withStyle(ChatFormatting.YELLOW));
          }
        r.failed = 0;
      } else if (time - r.prepareAt >= 5000 || allResponded) {
        if (++r.failed >= state.queue().size()) {
          r.engine.stop();
          for (UUID id : state.members()) {
            ServerPlayer p = s.getPlayerList().getPlayer(id);
            if (p != null)
              p.sendSystemMessage(
                  Component.literal("[一起听] 所有歌曲均未能准备播放，已停止").withStyle(ChatFormatting.GRAY));
          }
        } else r.engine.next(time);
      }
    }
    flush(s, r);
  }

  private static void flush(MinecraftServer server, Room r) {
    SessionSnapshot state = r.engine.snapshot();
    if (r.sent == state.revision()) return;
    r.sent = state.revision();
    relayPolicy(server, r);
    // Set prepare identity before READY can be received, including one-player LAN.
    if (state.timeline().status().equals("PREPARING")
        && (!Objects.equals(state.current(), r.preparing)
            || state.timeline().epochMs() != r.prepareEpoch)) {
      r.preparing = state.current();
      r.prepareEpoch = state.timeline().epochMs();
      r.prepareAt = now();
      r.ready.clear();
    }
    String serialized = JSON.toJson(state);
    List<MusicPacket> packets = packets(state, serialized);
    for (UUID id : state.members()) {
      ServerPlayer p = server.getPlayerList().getPlayer(id);
      if (p != null) for (MusicPacket packet : packets) ServerPlayNetworking.send(p, packet);
    }
    for (ServerPlayer p : server.getPlayerList().getPlayers())
      if (ServerPlayNetworking.canSend(p, MusicPacket.TYPE)) sendRoster(server, r, p);
  }

  private static void flushOne(ServerPlayer p, SessionSnapshot state) {
    for (MusicPacket packet : packets(state, JSON.toJson(state)))
      ServerPlayNetworking.send(p, packet);
  }

  private static void relayPolicy(MinecraftServer server, Room r) {
    if (r.engine == null) return;
    SessionSnapshot state = r.engine.snapshot();
    ServerRelay.policy(server, state);
    List<QueueEntry> entries = new ArrayList<>();
    if (!state.timeline().status().equals("STOPPED"))
      for (QueueEntry entry : Arrays.asList(state.currentEntry(), state.nextEntry()))
        if (entry != null && entries.stream().noneMatch(e -> e.id().equals(entry.id())))
          entries.add(entry);
    for (ServerPlayer p : server.getPlayerList().getPlayers())
      if (ServerPlayNetworking.canSend(p, MusicPacket.TYPE))
        send(
            p,
            Map.of(
                "type",
                "relayPolicy",
                "session",
                state.sessionId().toString(),
                "entries",
                entries));
  }

  private static List<MusicPacket> packets(SessionSnapshot state, String text) {
    int size = 14000, total = (text.length() + size - 1) / size;
    List<MusicPacket> result = new ArrayList<>();
    for (int i = 0; i < total; i++)
      result.add(
          new MusicPacket(
              JSON.toJson(
                  Map.of(
                      "type",
                      "state",
                      "session",
                      state.sessionId().toString(),
                      "revision",
                      state.revision(),
                      "part",
                      i,
                      "total",
                      total,
                      "data",
                      text.substring(i * size, Math.min(text.length(), (i + 1) * size))))));
    return result;
  }
}
