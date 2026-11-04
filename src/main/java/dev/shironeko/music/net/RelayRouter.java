package dev.shironeko.music.net;

import dev.shironeko.music.core.*;
import java.util.*;
import java.util.function.*;

/** No game objects, disk, decoding, or blocking calls. Called on network threads. */
public final class RelayRouter {
  private record Flow(UUID receiver, UUID producer, RelayFrame request, long at) {}

  private static final class Budget {
    double bytes = 1024 * 1024;
    long at;

    boolean take(int count, long now) {
      bytes = Math.min(1024 * 1024, bytes + Math.max(0, now - at) * 8192.0);
      at = now;
      if (bytes < count) return false;
      bytes -= count;
      return true;
    }
  }

  private final LongSupplier clock;
  private final Map<UUID, Consumer<RelayFrame>> peers = new HashMap<>();
  private final Map<UUID, Flow> pending = new HashMap<>();
  private final Map<UUID, Budget> budgets = new HashMap<>();
  private UUID session;
  private Set<UUID> members = Set.of();
  private Map<UUID, UUID> producers = Map.of();
  private long expiredAt;

  public RelayRouter(LongSupplier clock) {
    this.clock = clock;
  }

  public synchronized void peer(UUID player, Consumer<RelayFrame> sender) {
    peers.put(player, sender);
  }

  public synchronized void remove(UUID player) {
    peers.remove(player);
    budgets.remove(player);
    pending
        .values()
        .removeIf(
            f -> {
              if (f.receiver.equals(player) || f.producer.equals(player)) {
                if (!f.receiver.equals(player))
                  send(f.receiver, f.request.failure("点歌人已离线，无法继续转发"));
                return true;
              }
              return false;
            });
  }

  public synchronized void policy(SessionSnapshot state) {
    session = state.sessionId();
    members = Set.copyOf(state.members());
    Map<UUID, UUID> allowed = new HashMap<>();
    if (!state.timeline().status().equals("STOPPED"))
      for (QueueEntry e : Arrays.asList(state.currentEntry(), state.nextEntry()))
        if (e != null && e.requestedBy() != null) allowed.put(e.id(), e.requestedBy());
    producers = Map.copyOf(allowed);
    expire();
  }

  public synchronized void expire() {
    long now = clock.getAsLong();
    pending
        .values()
        .removeIf(
            f -> {
              if (!valid(f.receiver, f.request) || now - f.at > 35000) {
                send(f.receiver, f.request.failure("共听音频请求已取消或超时"));
                return true;
              }
              return false;
            });
  }

  private boolean valid(UUID who, RelayFrame f) {
    return Objects.equals(session, f.session())
        && members.contains(who)
        && producers.containsKey(f.entry())
        && peers.containsKey(who)
        && peers.containsKey(producers.get(f.entry()));
  }

  public synchronized void receive(UUID who, RelayFrame f) {
    if (!peers.containsKey(who)) return;
    long now = clock.getAsLong();
    if (now - expiredAt > 1000) {
      expiredAt = now;
      expire();
    }
    if (f.kind() == RelayFrame.REQUEST) {
      if (!valid(who, f) || who.equals(producers.get(f.entry()))) {
        send(who, f.failure("点歌人不在线或当前没有可转发的音源"));
        return;
      }
      if (pending.containsKey(f.request())) return;
      if (pending.size() >= 512
          || pending.values().stream().filter(p -> p.receiver.equals(who)).count() >= 16
          || !budgets.computeIfAbsent(who, k -> new Budget()).take(RelayFrame.CHUNK, now)) {
        send(who, f.failure("音频转发繁忙或达到带宽上限"));
        return;
      }
      UUID producer = producers.get(f.entry());
      pending.put(f.request(), new Flow(who, producer, f, now));
      send(producer, f);
    } else {
      Flow flow = pending.get(f.request());
      if (flow == null || !flow.request.matches(f)) return;
      if (f.kind() == RelayFrame.CANCEL && flow.receiver.equals(who)) {
        pending.remove(f.request());
        send(flow.producer, f);
        return;
      }
      if (!flow.producer.equals(who) || !valid(flow.receiver, f)) return;
      if (f.kind() == RelayFrame.DATA || f.kind() == RelayFrame.ERROR) {
        pending.remove(f.request());
        send(flow.receiver, f.kind() == RelayFrame.ERROR ? f.failure("点歌人无法提供完整音源或转发已中断") : f);
      }
    }
  }

  private void send(UUID who, RelayFrame f) {
    Consumer<RelayFrame> sender = peers.get(who);
    if (sender != null) sender.accept(f);
  }

  public synchronized int pendingCount() {
    return pending.size();
  }

  public synchronized void clear() {
    pending.clear();
    peers.clear();
    budgets.clear();
    producers = Map.of();
    members = Set.of();
  }
}
