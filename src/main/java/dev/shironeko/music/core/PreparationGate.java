package dev.shironeko.music.core;

import java.util.*;

public final class PreparationGate {
  private PreparationGate() {}

  public static boolean start(
      Collection<UUID> members, Map<UUID, Boolean> readiness, long elapsed) {
    return members.stream().anyMatch(id -> Boolean.TRUE.equals(readiness.get(id)))
        && (elapsed >= 5000
            || !members.isEmpty() && members.stream().allMatch(readiness::containsKey));
  }
}
