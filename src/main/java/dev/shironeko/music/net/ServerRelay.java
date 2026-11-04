package dev.shironeko.music.net;

import dev.shironeko.music.MusicTogether;
import dev.shironeko.music.core.SessionSnapshot;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** Main thread publishes authorization; network threads only route bounded encoded data. */
public final class ServerRelay {
  private static final Map<MinecraftServer, RelayRouter> ROOMS = new ConcurrentHashMap<>();
  private static final Map<ServerGamePacketListenerImpl, RelayRouter> CONNECTIONS =
      new ConcurrentHashMap<>();

  public static void bind(MinecraftServer server, ServerGamePacketListenerImpl listener) {
    RelayRouter router = ROOMS.computeIfAbsent(server, s -> new RelayRouter(MusicTogether::now));
    CONNECTIONS.put(listener, router);
    router.peer(
        listener.getOwner().id(),
        f -> listener.send(new ClientboundCustomPayloadPacket(new RelayPacket(f))));
  }

  public static void receive(ServerGamePacketListenerImpl listener, RelayFrame frame) {
    RelayRouter router = CONNECTIONS.get(listener);
    if (router != null) router.receive(listener.getOwner().id(), frame);
  }

  public static void policy(MinecraftServer server, SessionSnapshot state) {
    RelayRouter router = ROOMS.get(server);
    if (router != null) router.policy(state);
  }

  public static void disconnect(ServerGamePacketListenerImpl listener) {
    RelayRouter router = CONNECTIONS.remove(listener);
    if (router != null) router.remove(listener.getOwner().id());
  }

  public static void stop(MinecraftServer server) {
    RelayRouter router = ROOMS.remove(server);
    if (router != null) {
      CONNECTIONS.values().removeIf(value -> value == router);
      router.clear();
    }
  }

  private ServerRelay() {}
}
