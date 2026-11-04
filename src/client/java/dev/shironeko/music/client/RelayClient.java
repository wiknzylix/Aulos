package dev.shironeko.music.client;

import dev.shironeko.music.core.*;
import dev.shironeko.music.net.*;
import java.util.*;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;

public final class RelayClient {
  private static volatile Object connection;
  private static volatile RelayPeer peer;
  private static volatile UUID self;

  public static UUID self() {
    return self;
  }

  private static AudioRepository repository;
  public static LocalSources sources;

  public static void initialize() {
    sources = new LocalSources(() -> MusicClient.providers);
    repository =
        new AudioRepository(
            e ->
                (MusicClient.together || peer != null && peer.sharedEntry(e.id()))
                    ? sources.open(e)
                    : AudioRepository.direct(MusicClient.providers.get(e.track().platform()), e),
            e -> {
              RelayPeer p = peer;
              return p == null ? null : p.open(e);
            },
            MusicClient.FOLDER.resolve("cache"),
            e -> sources.compact(e));
  }

  public static boolean needsRelay(QueueEntry e) {
    return repository != null && repository.forwarded(e.id())
        || sources != null && sources.needsRelay(e.track());
  }

  public static AudioRepository repository() {
    return repository;
  }

  public static void connect(ClientPacketListener listener, UUID self) {
    disconnect();
    RelayClient.self = self;
    connection = listener;
    peer =
        new RelayPeer(
            self,
            f -> listener.send(new ServerboundCustomPayloadPacket(new RelayPacket(f))),
            repository);
  }

  public static void receive(Object listener, RelayFrame frame) {
    RelayPeer p = peer;
    if (listener == connection && p != null) p.receive(frame);
  }

  public static void policy(UUID session, List<QueueEntry> entries, boolean receiving) {
    RelayPeer p = peer;
    if (p != null) p.policy(session, entries, receiving);
  }

  public static boolean retryCompact(UUID id) {
    RelayPeer p = peer;
    if (p == null || !p.preferCompact(id)) return false;
    repository.invalidateEntry(id);
    return true;
  }

  public static void receiving(boolean enabled) {
    RelayPeer p = peer;
    if (p != null) p.receiving(enabled);
  }

  public static void disconnect() {
    connection = null;
    RelayPeer old = peer;
    peer = null;
    if (old != null) old.close();
    if (repository != null) repository.clear();
    if (sources != null) sources.clear();
  }

  public static void close() {
    disconnect();
    if (repository != null) repository.close();
    if (sources != null) sources.close();
  }

  private RelayClient() {}
}
