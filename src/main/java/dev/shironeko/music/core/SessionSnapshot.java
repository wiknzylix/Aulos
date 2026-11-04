package dev.shironeko.music.core;

import java.util.*;

public record SessionSnapshot(
    UUID sessionId,
    long revision,
    UUID owner,
    List<UUID> members,
    List<QueueEntry> queue,
    UUID current,
    UUID next,
    String repeat,
    boolean nextPinned,
    PlaybackTimeline timeline) {
  public boolean randomNextHidden() {
    return repeat.equals("SHUFFLE") && !nextPinned;
  }

  public QueueEntry currentEntry() {
    return find(current);
  }

  public QueueEntry nextEntry() {
    return find(next);
  }

  private QueueEntry find(UUID id) {
    return queue.stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
  }
}
