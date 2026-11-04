package dev.shironeko.music;

import dev.shironeko.music.client.*;
import dev.shironeko.music.core.*;
import dev.shironeko.music.net.*;
import dev.shironeko.music.provider.*;
import io.netty.buffer.Unpooled;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.MinecraftServer;

/** Real game connection in both directions with one synthetic peer, plus packet codec checks. */
final class RelayWireTest {
  static void run(ClientGameTestContext ctx) {
    MinecraftServer server = ctx.computeOnClient(c -> c.getSingleplayerServer());
    UUID client = ctx.computeOnClient(c -> c.player.getUUID()), fake = UUID.randomUUID();
    byte[] bytes = new byte[RelayFrame.CHUNK * 2 + 731];
    new Random(991).nextBytes(bytes);
    Path file = Path.of("aulos-wire-fixture.bin");
    Map<String, MusicProvider> originals = new LinkedHashMap<>(MusicClient.providers);
    MusicProvider original = MusicClient.providers.get("qq");
    try {
      Files.write(file, bytes);
      MusicProvider provider =
          (MusicProvider)
              java.lang.reflect.Proxy.newProxyInstance(
                  MusicProvider.class.getClassLoader(),
                  new Class[] {MusicProvider.class},
                  (o, m, args) -> {
                    if (m.getName().equals("id")) return "qq";
                    if (m.getName().equals("search"))
                      return new MusicProvider.Page<>(List.of(), false);
                    if (m.getName().equals("source")) {
                      TrackRef t = (TrackRef) args[0];
                      if (t.id().equals("wire-upload"))
                        return new MusicProvider.Source(file.toAbsolutePath().toUri(), 60000);
                      throw new java.io.IOException("Fixture has no playback permission");
                    }
                    if (m.getName().equals("lyrics")) return Lyrics.empty();
                    return null;
                  });
      ctx.runOnClient(
          c -> {
            MusicClient.providers.put("qq", provider);
            for (String id : List.of("netease", "kugou"))
              MusicClient.providers.put(
                  id,
                  (MusicProvider)
                      java.lang.reflect.Proxy.newProxyInstance(
                          MusicProvider.class.getClassLoader(),
                          new Class[] {MusicProvider.class},
                          (o, m, args) -> {
                            if (m.getName().equals("id")) return id;
                            if (m.getName().equals("search"))
                              return new MusicProvider.Page<>(List.of(), false);
                            if (m.getName().equals("lyrics")) return Lyrics.empty();
                            if (m.getName().equals("source"))
                              throw new java.io.IOException("No fixture source");
                            return null;
                          }));
            RelayClient.sources.clear();
          });
      AtomicReference<QueueEntry> upload = new AtomicReference<>();
      CompletableFuture<byte[]> sent = new CompletableFuture<>();
      Set<String> serverThreads = ConcurrentHashMap.newKeySet();
      Map<Long, byte[]> chunks = new ConcurrentHashMap<>();
      server.execute(
          () -> {
            try {
              QueueEngine engine = engine(server);
              engine.join(fake);
              engine.add(
                  List.of(
                      new TrackRef(
                          "qq", "wire-upload", "Upload transport fixture", "", "", "", 60000)),
                  false,
                  MusicTogether.now(),
                  client);
              engine.freeze(MusicTogether.now());
              upload.set(engine.snapshot().currentEntry());
              RelayRouter router = router(server);
              router.peer(
                  fake,
                  f -> {
                    serverThreads.add(Thread.currentThread().getName());
                    if (f.kind() == RelayFrame.ERROR) {
                      sent.completeExceptionally(new AssertionError(f.error()));
                      return;
                    }
                    chunks.put(f.offset(), f.data());
                    if (chunks.size() == 3) {
                      var out = new java.io.ByteArrayOutputStream();
                      for (long at = 0; at < bytes.length; at += RelayFrame.CHUNK)
                        out.writeBytes(chunks.get(at));
                      sent.complete(out.toByteArray());
                    }
                  });
              flush(server);
            } catch (Exception e) {
              sent.completeExceptionally(e);
            }
          });
      ctx.waitFor(c -> upload.get() != null && MusicClient.state().current() != null);
      ctx.waitTicks(3);
      server.execute(
          () -> {
            try {
              SessionSnapshot state = engine(server).snapshot();
              for (long at = 0; at < bytes.length; at += RelayFrame.CHUNK)
                router(server)
                    .receive(fake, RelayFrame.request(state.sessionId(), upload.get().id(), at));
            } catch (Exception e) {
              sent.completeExceptionally(e);
            }
          });
      ctx.waitFor(c -> sent.isDone(), 1200);
      if (!Arrays.equals(bytes, sent.join())) throw new AssertionError("Game upload bytes changed");
      if (serverThreads.stream()
          .anyMatch(t -> t.equals("Server thread") || t.equals("Render thread")))
        throw new AssertionError("Audio data reached a game main thread: " + serverThreads);

      AtomicReference<QueueEntry> download = new AtomicReference<>();
      AtomicReference<Throwable> setupFailure = new AtomicReference<>();
      server.execute(
          () -> {
            try {
              QueueEngine engine = engine(server);
              engine.clear();
              engine.add(
                  List.of(
                      new TrackRef(
                          "qq", "wire-download", "Download transport fixture", "", "", "", 60000)),
                  false,
                  MusicTogether.now(),
                  fake);
              engine.freeze(MusicTogether.now());
              download.set(engine.snapshot().currentEntry());
              RelayRouter router = router(server);
              router.peer(
                  fake,
                  f -> {
                    int count =
                        (int) Math.min(RelayFrame.CHUNK, Math.max(0, bytes.length - f.offset()));
                    if (f.offset() > bytes.length) {
                      router.receive(fake, f.failure("End of fixture"));
                      return;
                    }
                    router.receive(
                        fake,
                        new RelayFrame(
                            RelayFrame.DATA,
                            f.session(),
                            f.entry(),
                            f.request(),
                            f.offset(),
                            bytes.length,
                            f.offset() + count == bytes.length,
                            Arrays.copyOfRange(bytes, (int) f.offset(), (int) f.offset() + count),
                            ""));
                  });
              flush(server);
            } catch (Exception e) {
              setupFailure.set(e);
            }
          });
      ctx.waitFor(
          c ->
              setupFailure.get() != null
                  || download.get() != null
                      && MusicClient.state().current().equals(download.get().id()));
      if (setupFailure.get() != null) throw new AssertionError(setupFailure.get());
      ctx.waitTicks(3);
      CompletableFuture<byte[]> received =
          CompletableFuture.supplyAsync(
              () -> {
                try (var source = RelayClient.repository().open(download.get())) {
                  return source.input().readAllBytes();
                } catch (Exception e) {
                  throw new CompletionException(e);
                }
              });
      ctx.waitFor(c -> received.isDone(), 1200);
      if (!Arrays.equals(bytes, received.join())
          || !RelayClient.repository().forwarded(download.get().id()))
        throw new AssertionError("Automatic fallback failed on game connection");
      ctx.runOnClient(
          c -> {
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), c.level.registryAccess());
            try {
              RelayFrame f =
                  new RelayFrame(
                      RelayFrame.DATA,
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      0,
                      bytes.length,
                      false,
                      Arrays.copyOf(bytes, RelayFrame.CHUNK),
                      "");
              RelayPacket.CODEC.encode(buffer, new RelayPacket(f));
              RelayFrame decoded = RelayPacket.CODEC.decode(buffer).frame();
              if (!f.matches(decoded)
                  || !Arrays.equals(f.data(), decoded.data())
                  || buffer.isReadable()) throw new AssertionError("Relay codec round trip failed");
            } finally {
              buffer.release();
            }
          });
      Files.writeString(
          Path.of("aulos-relay-wire.json"),
          MusicTogether.JSON.toJson(
              Map.of(
                  "bytes_each_direction",
                  bytes.length,
                  "upload_exact",
                  true,
                  "fallback_download_exact",
                  true,
                  "codec_round_trip",
                  true,
                  "server_audio_threads",
                  serverThreads,
                  "scope",
                  "One real Minecraft client plus a synthetic peer over integrated game"
                      + " connection")));
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      ctx.runOnClient(
          c -> {
            MusicClient.providers.clear();
            MusicClient.providers.putAll(originals);
            RelayClient.sources.clear();
          });
      CompletableFuture<Void> cleared = new CompletableFuture<>();
      server.execute(
          () -> {
            try {
              engine(server).clear();
              engine(server).leave(fake);
              router(server).remove(fake);
              flush(server);
              cleared.complete(null);
            } catch (Exception e) {
              cleared.completeExceptionally(e);
            }
          });
      ctx.waitFor(c -> cleared.isDone());
      cleared.join();
      try {
        Files.deleteIfExists(file);
      } catch (Exception ignored) {
      }
    }
  }

  private static Object room(MinecraftServer server) throws Exception {
    var f = MusicTogether.class.getDeclaredField("ROOMS");
    f.setAccessible(true);
    return ((Map<?, ?>) f.get(null)).get(server);
  }

  private static QueueEngine engine(MinecraftServer server) throws Exception {
    Object room = room(server);
    var f = room.getClass().getDeclaredField("engine");
    f.setAccessible(true);
    return (QueueEngine) f.get(room);
  }

  private static RelayRouter router(MinecraftServer server) throws Exception {
    var f = ServerRelay.class.getDeclaredField("ROOMS");
    f.setAccessible(true);
    return (RelayRouter) ((Map<?, ?>) f.get(null)).get(server);
  }

  private static void flush(MinecraftServer server) throws Exception {
    Object room = room(server);
    var method =
        MusicTogether.class.getDeclaredMethod("flush", MinecraftServer.class, room.getClass());
    method.setAccessible(true);
    method.invoke(null, server, room);
  }
}
