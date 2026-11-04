package dev.shironeko.music;

import static org.junit.jupiter.api.Assertions.*;

import dev.shironeko.music.core.*;
import java.util.*;
import org.junit.jupiter.api.*;

class QueueTest {
  @Test
  void emptyOperationsDoNotBecomeEffectiveRevisions() {
    var q = engine();
    long version = q.snapshot().revision();
    q.prioritize(List.of());
    q.remove(List.of(), 1);
    q.add(List.of(), false, 1);
    q.repeat("LIST");
    q.previous(1);
    q.toggle(1); // No history, and preparing audio cannot be toggled yet.
    assertEquals(version, q.snapshot().revision());
    assertThrows(IllegalArgumentException.class, () -> q.remove(List.of(UUID.randomUUID()), 1));
    assertEquals(version, q.snapshot().revision());
    q.clear();
    version = q.snapshot().revision();
    q.clear();
    q.next(1);
    q.stop();
    assertEquals(version, q.snapshot().revision());
  }

  TrackRef t(String name) {
    return new TrackRef("netease", name, name, "artist", "album", "", 60000);
  }

  QueueEngine engine() {
    QueueEngine q = new QueueEngine(UUID.randomUUID(), new Random(71));
    q.add(List.of(t("a"), t("b"), t("c")), false, 1000);
    return q;
  }

  @Test
  void insertionOverridesEveryRepeatMode() {
    for (String mode : List.of("LIST", "SHUFFLE", "ONE")) {
      var q = engine();
      q.repeat(mode);
      q.add(List.of(t("x"), t("y")), true, 1100);
      q.next(1200);
      assertEquals("x", q.snapshot().currentEntry().track().title());
      q.next(1300);
      assertEquals("y", q.snapshot().currentEntry().track().title());
    }
  }

  @Test
  void newerBatchComesFirstButPreservesOrder() {
    var q = engine();
    q.add(List.of(t("x"), t("y")), true, 1100);
    q.add(List.of(t("z")), true, 1150);
    for (String id : List.of("z", "x", "y")) {
      q.next(1200);
      assertEquals(id, q.snapshot().currentEntry().track().id());
    }
  }

  @Test
  void duplicateTracksHaveIndependentIds() {
    var q = engine();
    q.add(List.of(t("a")), false, 1000);
    var ids =
        q.snapshot().queue().stream()
            .filter(e -> e.track().id().equals("a"))
            .map(QueueEntry::id)
            .toList();
    assertEquals(2, ids.size());
    assertNotEquals(ids.get(0), ids.get(1));
    q.remove(List.of(ids.get(0)), 1500);
    assertNotNull(
        q.snapshot().queue().stream()
            .filter(e -> e.id().equals(ids.get(1)))
            .findFirst()
            .orElse(null));
  }

  @Test
  void previousUsesActualShuffleHistory() {
    var q = engine();
    q.repeat("SHUFFLE");
    UUID first = q.snapshot().current();
    q.next(2000);
    UUID second = q.snapshot().current();
    q.next(3000);
    q.previous(4000);
    assertEquals(second, q.snapshot().current());
    q.previous(5000);
    assertEquals(first, q.snapshot().current());
  }

  @Test
  void removalAndClear() {
    var q = engine();
    UUID next = q.snapshot().next();
    q.remove(List.of(q.snapshot().current()), 1400);
    assertEquals(next, q.snapshot().current());
    q.clear();
    assertTrue(q.snapshot().queue().isEmpty());
    assertNull(q.snapshot().current());
    assertNull(q.snapshot().next());
    assertEquals("STOPPED", q.snapshot().timeline().status());
  }

  @Test
  void timelineUsesMonotonicSchedule() {
    var q = engine();
    q.ready(2000, 750);
    assertEquals(0, q.snapshot().timeline().position(2600));
    assertEquals(250, q.snapshot().timeline().position(3000));
    q.toggle(3500);
    assertEquals(500, q.snapshot().timeline().position(3250));
    q.tick(3500);
    assertEquals("PAUSED", q.snapshot().timeline().status());
    assertEquals(750, q.snapshot().timeline().position(10000));
  }

  @Test
  void snapshotsAreImmutableAndCached() {
    var q = engine();
    var old = q.snapshot();
    assertSame(old, q.snapshot());
    assertThrows(UnsupportedOperationException.class, () -> old.queue().clear());
    q.next(2000);
    assertNotSame(old, q.snapshot());
    assertNotEquals(old.current(), q.snapshot().current());
  }

  @Test
  void repeatOneCanActuallyRestartSameEntry() {
    var q = engine();
    q.repeat("ONE");
    q.ready(1000, 0);
    UUID id = q.snapshot().current();
    q.tick(61000);
    assertEquals(id, q.snapshot().current());
    assertEquals("PREPARING", q.snapshot().timeline().status());
    assertEquals(61000, q.snapshot().timeline().epochMs());
  }

  @Test
  void priorityMovesExistingEntryWithoutDuplicating() {
    var q = engine();
    var last = q.snapshot().queue().getLast();
    q.prioritize(List.of(last.id()));
    q.next(2000);
    assertEquals(last.id(), q.snapshot().current());
    assertEquals(3, q.snapshot().queue().size());
  }

  @Test
  void rejectsMalformedMetadata() {
    assertThrows(
        IllegalArgumentException.class, () -> new TrackRef("http", "x", "t", "a", "", "", 1));
    assertThrows(IllegalArgumentException.class, () -> new TrackRef("qq", "", "t", "a", "", "", 1));
  }

  @Test
  void fullQueueDoesNotGrowWithoutLimit() {
    var q = engine();
    q.add(Collections.nCopies(QueueEngine.LIMIT - 3, t("x")), false, 1000);
    assertThrows(IllegalArgumentException.class, () -> q.add(List.of(t("overflow")), false, 2000));
  }

  @Test
  void shuffleCoolsRecentThirtyPercentAndKeepsNextStable() {
    var q = engine();
    q.add(
        java.util.stream.IntStream.range(3, 100).mapToObj(i -> t("song" + i)).toList(),
        false,
        1000);
    q.repeat("SHUFFLE");
    var recent = new ArrayDeque<UUID>();
    for (int i = 0; i < 5000; i++) {
      UUID current = q.snapshot().current();
      assertFalse(recent.contains(current), "A random draw repeated within the 30-song cooldown");
      recent.addLast(current);
      while (recent.size() > 30) recent.removeFirst();
      UUID next = q.snapshot().next();
      q.repeat("SHUFFLE");
      q.join(
          UUID.nameUUIDFromBytes(
              ("member" + i % 5).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      assertEquals(next, q.snapshot().next());
      q.next(2000 + i);
    }
  }

  @Test
  void shuffleExplicitPriorityCanOverrideCooldownAndSingletonCanGrow() {
    var q = new QueueEngine(UUID.randomUUID(), new Random(17));
    q.add(List.of(t("a")), false, 0);
    q.repeat("SHUFFLE");
    UUID first = q.snapshot().current();
    assertEquals(first, q.snapshot().next());
    q.add(List.of(t("b"), t("c")), false, 1);
    assertNotEquals(first, q.snapshot().next());
    q.next(2);
    q.prioritize(List.of(first));
    assertEquals(first, q.snapshot().next());
    q.next(3);
    assertEquals(first, q.snapshot().current());
    q.remove(
        q.snapshot().queue().stream().map(QueueEntry::id).filter(id -> !id.equals(first)).toList(),
        4);
    assertEquals(first, q.snapshot().next());
    q.next(5);
    assertEquals(first, q.snapshot().current());
  }
}
