package dev.shironeko.music;

import static org.junit.jupiter.api.Assertions.*;

import dev.shironeko.music.core.*;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ShuffleNavigationTest {
  private static List<TrackRef> tracks(int count) {
    return IntStream.range(0, count)
        .mapToObj(i -> new TrackRef("netease", "song" + i, "Song " + i, "Artist", "", "", 60000))
        .toList();
  }

  private static final class CountingRandom extends Random {
    int calls;

    CountingRandom() {
      super(173);
    }

    public int nextInt(int bound) {
      calls++;
      return super.nextInt(bound);
    }
  }

  private static QueueEngine queue(Random random, int count) {
    var q = new QueueEngine(UUID.randomUUID(), random);
    q.repeat("SHUFFLE");
    q.add(tracks(count), false, 1);
    return q;
  }

  private static List<UUID> play(QueueEngine q, int count) {
    List<UUID> played = new ArrayList<>();
    played.add(q.snapshot().current());
    for (int i = 1; i < count; i++) {
      q.next(100 + i);
      played.add(q.snapshot().current());
    }
    return played;
  }

  @Test
  void emptyShuffleCanStartAtEveryQueuePosition() {
    for (int index = 0; index < 7; index++) {
      final int choice = index;
      var q =
          queue(
              new Random() {
                public int nextInt(int bound) {
                  return Math.min(choice, bound - 1);
                }
              },
              7);
      assertEquals("song" + index, q.snapshot().currentEntry().track().id());
      q.clear();
      q.add(tracks(7), false, 2);
      assertEquals("song" + index, q.snapshot().currentEntry().track().id());
    }
  }

  @Test
  void explicitNextBatchStillStartsInItsRequestedOrder() {
    var q = new QueueEngine(UUID.randomUUID(), new Random(71));
    q.repeat("SHUFFLE");
    q.add(tracks(7), true, 1);
    for (int i = 0; i < 7; i++) {
      assertEquals("song" + i, q.snapshot().currentEntry().track().id());
      if (i < 6) q.next(100 + i);
    }
  }

  @Test
  void backAndForwardReuseFiveEntriesAndPreservePendingRandomChoice() {
    var random = new CountingRandom();
    var q = queue(random, 30);
    var sequence = play(q, 5);
    UUID reserved = q.snapshot().next();
    int calls = random.calls;
    for (int i = 3; i >= 0; i--) {
      q.previous(200 + i);
      assertEquals(sequence.get(i), q.snapshot().current());
      assertEquals(sequence.get(i + 1), q.snapshot().next());
      assertFalse(q.snapshot().randomNextHidden());
    }
    long revision = q.snapshot().revision();
    q.previous(300);
    assertEquals(revision, q.snapshot().revision());
    for (int i = 1; i < 5; i++) {
      q.next(400 + i);
      assertEquals(sequence.get(i), q.snapshot().current());
    }
    assertEquals(reserved, q.snapshot().next());
    assertTrue(q.snapshot().randomNextHidden());
    assertEquals(calls, random.calls, "History navigation must not consume random draws");
    q.next(500);
    assertEquals(reserved, q.snapshot().current());
  }

  @Test
  void olderEntriesFallOutsideTheFiveSongWindow() {
    var q = queue(new Random(71), 100);
    var sequence = play(q, 12);
    for (int i = 0; i < 20; i++) q.previous(1000 + i);
    assertEquals(sequence.get(7), q.snapshot().current());
    for (int i = 8; i < 12; i++) {
      q.next(2000 + i);
      assertEquals(sequence.get(i), q.snapshot().current());
    }
  }

  @Test
  void priorityAndImmediatePlaybackCreateANewBranchFromTheCursor() {
    var q = queue(new Random(71), 30);
    var sequence = play(q, 5);
    q.previous(300);
    q.previous(301);
    UUID priority =
        q.snapshot().queue().stream()
            .map(QueueEntry::id)
            .filter(id -> !sequence.contains(id))
            .findFirst()
            .orElseThrow();
    q.prioritize(List.of(priority));
    assertEquals(priority, q.snapshot().next());
    q.next(302);
    assertEquals(priority, q.snapshot().current());
    q.previous(303);
    assertEquals(sequence.get(2), q.snapshot().current());
    q.next(304);
    assertEquals(priority, q.snapshot().current());
    UUID immediate = sequence.getFirst();
    q.previous(305);
    q.play(immediate, 306);
    q.previous(307);
    assertEquals(sequence.get(2), q.snapshot().current());
    q.next(308);
    assertEquals(immediate, q.snapshot().current());
  }

  @Test
  void removingCachedEntriesSkipsThemWithoutLosingTheForwardTrail() {
    var q = queue(new Random(71), 30);
    var sequence = play(q, 5);
    UUID reserved = q.snapshot().next();
    q.previous(300);
    q.previous(301);
    q.remove(List.of(sequence.get(3)), 302);
    assertEquals(sequence.get(4), q.snapshot().next());
    q.next(303);
    assertEquals(sequence.get(4), q.snapshot().current());
    q.previous(304);
    assertEquals(sequence.get(2), q.snapshot().current());
    q.remove(List.of(sequence.get(2)), 305);
    assertEquals(sequence.get(4), q.snapshot().current());
    assertEquals(reserved, q.snapshot().next());
    q.stop();
    q.remove(q.snapshot().queue().stream().map(QueueEntry::id).toList(), 306);
    assertNull(q.snapshot().current());
    assertNull(q.snapshot().next());
    long revision = q.snapshot().revision();
    q.previous(307);
    q.next(308);
    assertEquals(revision, q.snapshot().revision());
    q.add(tracks(7), false, 309);
    assertNotNull(q.snapshot().currentEntry());
  }

  @Test
  void changingRepeatModesKeepsTheCursorAndValidPreviousHistory() {
    var q = queue(new Random(71), 30);
    var sequence = play(q, 5);
    q.previous(300);
    q.previous(301);
    q.repeat("LIST");
    assertEquals(sequence.get(2), q.snapshot().current());
    q.previous(302);
    assertEquals(sequence.get(1), q.snapshot().current());
    q.repeat("SHUFFLE");
    q.previous(303);
    assertEquals(sequence.get(0), q.snapshot().current());
    q.next(304);
    assertEquals(sequence.get(1), q.snapshot().current());
    q.repeat("ONE");
    q.next(305);
    assertEquals(sequence.get(1), q.snapshot().current());
  }

  @Test
  void mixedNavigationAndManualSelectionsFollowABoundedReferenceHistory() {
    var q = queue(new Random(71), 30);
    var operations = new Random(901);
    var history = new ArrayList<UUID>();
    history.add(q.snapshot().current());
    int at = 0;
    for (int i = 0; i < 10000; i++) {
      int operation = operations.nextInt(5);
      if (operation == 0) {
        q.previous(1000 + i);
        at = Math.max(0, at - 1);
      } else if (operation == 1) {
        UUID selected = q.snapshot().queue().get(operations.nextInt(30)).id();
        q.play(selected, 1000 + i);
        history.subList(at + 1, history.size()).clear();
        history.add(selected);
        if (history.size() > 5) history.removeFirst();
        at = history.size() - 1;
      } else if (at + 1 < history.size()) {
        q.next(1000 + i);
        at++;
      } else {
        UUID expected = q.snapshot().next();
        q.next(1000 + i);
        history.add(expected);
        if (history.size() > 5) history.removeFirst();
        at = history.size() - 1;
      }
      assertEquals(history.get(at), q.snapshot().current());
      if (at + 1 < history.size()) assertEquals(history.get(at + 1), q.snapshot().next());
    }
  }
}
