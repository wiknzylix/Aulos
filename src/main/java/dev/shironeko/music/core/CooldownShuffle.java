package dev.shironeko.music.core;

import java.util.*;

/** An in-place eligible/cooling partition. Draws, swaps and FIFO cooldown updates are O(1). */
final class CooldownShuffle {
  private final ArrayList<UUID> slots = new ArrayList<>();
  private final Map<UUID, Integer> indexes = new HashMap<>();
  private final LinkedHashSet<UUID> cooling = new LinkedHashSet<>();
  private int eligible;

  void add(UUID id) {
    if (indexes.containsKey(id)) return;
    indexes.put(id, slots.size());
    slots.add(id);
    swap(slots.size() - 1, eligible++);
  }

  void remove(UUID id) {
    Integer index = indexes.get(id);
    if (index == null) return;
    if (index < eligible) swap(index, --eligible);
    int at = indexes.get(id);
    swap(at, slots.size() - 1);
    slots.removeLast();
    indexes.remove(id);
    cooling.remove(id);
    trim();
  }

  void played(UUID id) {
    Integer index = indexes.get(id);
    if (index == null) return;
    if (index < eligible) swap(index, --eligible);
    cooling.remove(id);
    cooling.add(id);
    trim();
  }

  // Round 30% upward; retain at least one eligible item, including for a one-song queue.
  private int capacity() {
    return Math.min(Math.max(0, slots.size() - 1), (slots.size() * 3 + 9) / 10);
  }

  private void trim() {
    while (cooling.size() > capacity()) {
      UUID id = cooling.getFirst();
      cooling.removeFirst();
      swap(indexes.get(id), eligible++);
    }
  }

  boolean eligible(UUID id) {
    Integer index = indexes.get(id);
    return index != null && index < eligible;
  }

  UUID draw(Random random) {
    return eligible == 0 ? null : slots.get(random.nextInt(eligible));
  }

  void clear() {
    slots.clear();
    indexes.clear();
    cooling.clear();
    eligible = 0;
  }

  private void swap(int a, int b) {
    if (a == b) return;
    UUID left = slots.get(a), right = slots.get(b);
    slots.set(a, right);
    slots.set(b, left);
    indexes.put(left, b);
    indexes.put(right, a);
  }
}
