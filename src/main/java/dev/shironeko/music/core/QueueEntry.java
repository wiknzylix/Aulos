package dev.shironeko.music.core;

import java.util.UUID;

public record QueueEntry(UUID id, TrackRef track, UUID requestedBy) {
  public QueueEntry(UUID id, TrackRef track) {
    this(id, track, null);
  }

  public QueueEntry {
    java.util.Objects.requireNonNull(id);
    java.util.Objects.requireNonNull(track);
  }

  public static QueueEntry of(TrackRef track) {
    return new QueueEntry(UUID.randomUUID(), track);
  }

  public static QueueEntry of(TrackRef track, UUID requestedBy) {
    return new QueueEntry(UUID.randomUUID(), track, requestedBy);
  }
}
