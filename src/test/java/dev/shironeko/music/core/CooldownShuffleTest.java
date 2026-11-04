package dev.shironeko.music.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class CooldownShuffleTest {
  @Test
  void excludesThirtyPercentAndReservesWithoutConsuming() {
    CooldownShuffle pool = new CooldownShuffle();
    var ids = new ArrayList<UUID>();
    for (int i = 0; i < 10; i++) {
      UUID id = UUID.randomUUID();
      ids.add(id);
      pool.add(id);
    }
    pool.played(ids.get(0));
    pool.played(ids.get(1));
    pool.played(ids.get(2));
    Random random = new Random(71);
    for (int i = 0; i < 100; i++) assertFalse(ids.subList(0, 3).contains(pool.draw(random)));
    pool.played(ids.get(3));
    assertTrue(pool.eligible(ids.get(0)));
    assertFalse(pool.eligible(ids.get(1)));
  }

  @Test
  void randomizedMutationsMatchReferencePool() {
    var pool = new CooldownShuffle();
    var present = new HashSet<UUID>();
    var recent = new LinkedHashSet<UUID>();
    var random = new Random(173);
    var ids = new ArrayList<UUID>();
    for (int i = 0; i < 80; i++) ids.add(UUID.randomUUID());
    for (int step = 0; step < 20000; step++) {
      UUID id = ids.get(random.nextInt(ids.size()));
      switch (random.nextInt(3)) {
        case 0 -> {
          pool.add(id);
          present.add(id);
        }
        case 1 -> {
          pool.remove(id);
          present.remove(id);
          recent.remove(id);
        }
        case 2 -> {
          pool.played(id);
          if (present.contains(id)) {
            recent.remove(id);
            recent.add(id);
          }
        }
      }
      int cap = Math.min(Math.max(0, present.size() - 1), (present.size() * 3 + 9) / 10);
      while (recent.size() > cap) recent.removeFirst();
      for (UUID item : ids)
        assertEquals(present.contains(item) && !recent.contains(item), pool.eligible(item));
      UUID drawn = pool.draw(random);
      if (present.isEmpty()) assertNull(drawn);
      else assertTrue(present.contains(drawn) && !recent.contains(drawn));
    }
    pool.clear();
    assertNull(pool.draw(random));
  }
}
