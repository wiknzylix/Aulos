package dev.shironeko.music.core;

import java.util.*;

/** Single owner thread. No I/O, game world access or wall clock. */
public final class QueueEngine {
  public static final int LIMIT = 10000;
  private static final int RECENT_LIMIT = 5;
  private final UUID session = UUID.randomUUID(), owner;
  private final List<QueueEntry> queue = new ArrayList<>();
  private final Deque<UUID> priority = new ArrayDeque<>(), history = new ArrayDeque<>();
  private final Random random;
  private final CooldownShuffle shuffle = new CooldownShuffle();
  // Queue entry IDs only; this window includes the current song, without extra audio preloads.
  private final List<UUID> recent = new ArrayList<>(RECENT_LIMIT);
  private final LinkedHashSet<UUID> members = new LinkedHashSet<>();
  // Keep the random frontier reservation while 'next' temporarily points into replay history.
  private UUID current, next, shuffledNext;
  private int recentAt = -1;
  private String repeat = "LIST";
  private PlaybackTimeline timeline = new PlaybackTimeline("STOPPED", 0, 0);
  private long revision;
  private SessionSnapshot cached;

  public QueueEngine(UUID owner) {
    this(owner, new Random());
  }

  public QueueEngine(UUID owner, Random random) {
    this.owner = owner;
    this.random = random;
    members.add(owner);
  }

  public SessionSnapshot snapshot() {
    if (cached == null || cached.revision() != revision)
      cached =
          new SessionSnapshot(
              session,
              revision,
              owner,
              List.copyOf(members),
              List.copyOf(queue),
              current,
              next,
              repeat,
              !priority.isEmpty() || repeat.equals("SHUFFLE") && hasForward(),
              timeline);
    return cached;
  }

  public boolean member(UUID id) {
    return members.contains(id);
  }

  public void join(UUID id) {
    if (members.add(id)) revision++;
  }

  public void leave(UUID id) {
    if (members.remove(id)) revision++;
  }

  public void add(List<TrackRef> tracks, boolean first, long now) {
    add(tracks, first, now, owner);
  }

  public void add(List<TrackRef> tracks, boolean first, long now, UUID requestedBy) {
    if (tracks.isEmpty()) return;
    if (tracks.size() + queue.size() > LIMIT) throw new IllegalArgumentException("队列最多 10000 首");
    boolean wasSingleton = queue.size() == 1;
    List<UUID> ids = new ArrayList<>();
    for (TrackRef t : tracks) {
      QueueEntry e = QueueEntry.of(t, requestedBy);
      queue.add(e);
      shuffle.add(e.id());
      ids.add(e.id());
    }
    if (first) prepend(ids);
    revision++;
    if (current == null) {
      UUID id =
          first
              ? priority.removeFirst()
              : repeat.equals("SHUFFLE") ? shuffle.draw(random) : queue.getFirst().id();
      select(id, now, false);
    } else {
      if (wasSingleton) shuffle.played(current);
      chooseNext();
    }
  }

  public void prioritize(List<UUID> ids) {
    if (ids.isEmpty()) return;
    for (UUID id : ids) if (find(id) == null) throw new IllegalArgumentException("歌曲已移除");
    priority.removeAll(ids);
    prepend(ids);
    revision++;
    chooseNext();
  }

  private void prepend(List<UUID> ids) {
    for (int i = ids.size() - 1; i >= 0; i--) priority.addFirst(ids.get(i));
  }

  public void play(UUID id, long now) {
    if (find(id) == null) throw new IllegalArgumentException("歌曲已移除");
    priority.remove(id);
    select(id, now, true);
  }

  private void select(UUID id, long now, boolean remember) {
    if (remember && current != null && !repeat.equals("SHUFFLE")) {
      history.addLast(current);
      if (history.size() > 1000) history.removeFirst();
    }
    shuffle.played(id);
    if (id.equals(shuffledNext)) shuffledNext = null;
    if (repeat.equals("SHUFFLE")) {
      recent.subList(recentAt + 1, recent.size()).clear();
      recent.add(id);
      if (recent.size() > RECENT_LIMIT) recent.removeFirst();
      recentAt = recent.size() - 1;
    }
    activate(id, now);
  }

  private void activate(UUID id, long now) {
    current = id;
    timeline = new PlaybackTimeline("PREPARING", 0, now);
    revision++;
    chooseNext();
  }

  public void ready(long now, long delay) {
    if (current != null && timeline.status().equals("PREPARING")) {
      timeline = new PlaybackTimeline("PLAYING", 0, now + delay);
      revision++;
    }
  }

  public void next(long now) {
    if (repeat.equals("SHUFFLE") && priority.isEmpty() && hasForward()) {
      activate(recent.get(++recentAt), now);
      return;
    }
    if (next == null) {
      clear();
      return;
    }
    UUID target = next;
    priority.removeFirstOccurrence(target);
    select(target, now, true);
  }

  public void previous(long now) {
    if (repeat.equals("SHUFFLE")) {
      if (recentAt > 0) activate(recent.get(--recentAt), now);
      return;
    }
    while (!history.isEmpty()) {
      UUID id = history.removeLast();
      if (find(id) != null) {
        select(id, now, false);
        return;
      }
    }
  }

  public void toggle(long now) {
    if (current == null || timeline.status().equals("PREPARING")) return;
    if (timeline.status().equals("PLAYING"))
      timeline = new PlaybackTimeline("PAUSING", timeline.position(now), now);
    else timeline = new PlaybackTimeline("PLAYING", timeline.positionMs(), now + 350);
    revision++;
  }

  public void freeze(long now) {
    if (current != null) {
      timeline = new PlaybackTimeline("PAUSED", timeline.position(now), now);
      revision++;
    }
  }

  public void remove(List<UUID> ids, long now) {
    if (ids.isEmpty()) return;
    for (UUID id : ids) if (find(id) == null) throw new IllegalArgumentException("歌曲已移除");
    UUID successor = next;
    boolean active = ids.contains(current);
    queue.removeIf(e -> ids.contains(e.id()));
    ids.forEach(shuffle::remove);
    priority.removeAll(ids);
    history.removeAll(ids);
    for (int i = recent.size() - 1; i >= 0; i--)
      if (ids.contains(recent.get(i))) {
        recent.remove(i);
        if (i <= recentAt) recentAt--;
      }
    revision++;
    if (queue.isEmpty()) {
      clear();
      return;
    }
    if (active && repeat.equals("SHUFFLE")) {
      chooseNext();
      next(now);
    } else if (active) {
      UUID target = find(successor) != null ? successor : queue.getFirst().id();
      priority.removeFirstOccurrence(target);
      select(target, now, false);
    } else chooseNext();
  }

  public void clear() {
    if (queue.isEmpty() && current == null && next == null && timeline.status().equals("STOPPED"))
      return;
    queue.clear();
    shuffle.clear();
    priority.clear();
    history.clear();
    recent.clear();
    recentAt = -1;
    shuffledNext = null;
    current = null;
    next = null;
    timeline = new PlaybackTimeline("STOPPED", 0, 0);
    revision++;
  }

  public void stop() {
    if (timeline.status().equals("STOPPED")) return;
    timeline = new PlaybackTimeline("STOPPED", 0, 0);
    revision++;
  }

  public void repeat(String mode) {
    if (!Set.of("LIST", "SHUFFLE", "ONE").contains(mode))
      throw new IllegalArgumentException("循环模式无效");
    if (repeat.equals(mode)) return;
    if (repeat.equals("SHUFFLE")) {
      history.clear();
      for (int i = 0; i < recentAt; i++) history.addLast(recent.get(i));
    }
    recent.clear();
    recentAt = -1;
    if (mode.equals("SHUFFLE") && current != null) {
      recent.addAll(history.stream().skip(Math.max(0, history.size() - RECENT_LIMIT + 1)).toList());
      recent.add(current);
      recentAt = recent.size() - 1;
      history.clear();
    }
    next = null;
    shuffledNext = null;
    repeat = mode;
    revision++;
    chooseNext();
  }

  public void tick(long now) {
    if (timeline.status().equals("PAUSING") && now >= timeline.epochMs()) {
      timeline = new PlaybackTimeline("PAUSED", timeline.positionMs(), timeline.epochMs());
      revision++;
    }
    QueueEntry e = find(current);
    if (e != null
        && timeline.status().equals("PLAYING")
        && e.track().durationMs() > 0
        && timeline.position(now) >= e.track().durationMs()) next(now);
  }

  private QueueEntry find(UUID id) {
    for (QueueEntry e : queue) if (e.id().equals(id)) return e;
    return null;
  }

  private boolean hasForward() {
    return recentAt + 1 < recent.size();
  }

  private void chooseNext() {
    priority.removeIf(id -> find(id) == null);
    if (!priority.isEmpty()) {
      next = priority.getFirst();
      return;
    }
    if (queue.isEmpty()) {
      next = null;
      return;
    }
    if (repeat.equals("ONE")) {
      next = current;
      return;
    }
    if (repeat.equals("SHUFFLE")) {
      if (hasForward()) {
        next = recent.get(recentAt + 1);
        return;
      }
      if (!shuffle.eligible(shuffledNext)) shuffledNext = shuffle.draw(random);
      next = shuffledNext;
      return;
    }
    int index = -1;
    for (int i = 0; i < queue.size(); i++) if (queue.get(i).id().equals(current)) index = i;
    next = queue.get((index + 1) % queue.size()).id();
  }
}
