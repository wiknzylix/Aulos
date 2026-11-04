package dev.shironeko.music.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.shironeko.music.core.*;
import dev.shironeko.music.net.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class RelayTest {
  @TempDir Path folder;

  @Test
  void slowOriginalSwitchesToSeparateCompactBytesWithoutMixingFiles() throws Exception {
    byte[] original = new byte[100000], compact = new byte[RelayFrame.CHUNK * 3 + 79];
    new Random(3).nextBytes(compact);
    UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    QueueEngine q = new QueueEngine(a);
    q.join(b);
    q.add(List.of(track("adaptive")), false, 1);
    QueueEntry entry = q.snapshot().currentEntry();
    RelayRouter router = new RelayRouter(() -> System.nanoTime() / 1000000);
    RelayPeer[] peers = new RelayPeer[2];
    AtomicInteger compactCalls = new AtomicInteger();
    try (var network = Executors.newSingleThreadExecutor();
        AudioRepository owner =
            new AudioRepository(
                e -> {
                  Thread.sleep(1600);
                  return new AudioRepository.Source(
                      new ByteArrayInputStream(original), original.length, false);
                },
                null,
                folder.resolve("owner"),
                e -> {
                  compactCalls.incrementAndGet();
                  return new AudioRepository.Source(
                      new ByteArrayInputStream(compact), compact.length, false);
                });
        AudioRepository receiver =
            new AudioRepository(
                e -> {
                  throw new IOException("Denied");
                },
                e -> peers[1].open(e),
                folder.resolve("receiver"))) {
      try {
        peers[0] = new RelayPeer(a, f -> network.execute(() -> router.receive(a, f)), owner);
        peers[1] = new RelayPeer(b, f -> network.execute(() -> router.receive(b, f)), receiver);
        router.peer(a, f -> network.execute(() -> peers[0].receive(f)));
        router.peer(b, f -> network.execute(() -> peers[1].receive(f)));
        for (RelayPeer peer : peers) peer.policy(q.snapshot().sessionId(), List.of(entry), true);
        router.policy(q.snapshot());
        try (var input = receiver.open(entry)) {
          assertArrayEquals(compact, input.input().readAllBytes());
        }
        assertEquals(1, compactCalls.get());
      } finally {
        for (RelayPeer p : peers) if (p != null) p.close();
      }
    }
  }

  @Test
  void cacheStartupRemovesOnlyOwnedOrphansAndHonorsAnotherLiveRepositoryLock() throws Exception {
    Path orphan = folder.resolve("aulos-orphan.audio"), keep = folder.resolve("account.json");
    Files.write(orphan, new byte[] {1});
    Files.writeString(keep, "keep");
    QueueEntry entry = QueueEntry.of(track("cache"));
    AudioRepository.Opener source =
        e -> new AudioRepository.Source(new ByteArrayInputStream(new byte[] {3}), 1, false);
    try (AudioRepository first = new AudioRepository(source, null, folder)) {
      first.retain(List.of(entry.id()));
      try (var input = first.open(entry)) {
        assertEquals(3, input.input().read());
      }
      assertFalse(Files.exists(orphan));
      assertTrue(Files.exists(keep));
      long active;
      try (var files = Files.newDirectoryStream(folder, "aulos-*.audio")) {
        active = java.util.stream.StreamSupport.stream(files.spliterator(), false).count();
      }
      assertEquals(1, active);
      try (AudioRepository second = new AudioRepository(source, null, folder);
          var input = second.open(QueueEntry.of(track("other")))) {
        assertEquals(3, input.input().read());
      }
      try (var input = first.open(entry)) {
        assertEquals(3, input.input().read());
      }
    }
    assertEquals("keep", Files.readString(keep));
  }

  TrackRef track(String id) {
    return new TrackRef("qq", id, id, "artist", "album", "", 60000);
  }

  @Test
  void submitterSurvivesReorderingAndHistory() {
    UUID host = UUID.randomUUID(), sender = UUID.randomUUID();
    QueueEngine q = new QueueEngine(host);
    q.add(List.of(track("a"), track("b")), false, 1, sender);
    QueueEntry b = q.snapshot().nextEntry();
    q.prioritize(List.of(b.id()));
    q.next(2);
    q.previous(3);
    assertEquals(sender, q.snapshot().currentEntry().requestedBy());
    assertEquals(sender, q.snapshot().nextEntry().requestedBy());
  }

  @Test
  void routingRejectsForeignMembersForgedProducerAndStaleEntries() {
    UUID owner = UUID.randomUUID(), b = UUID.randomUUID(), attacker = UUID.randomUUID();
    QueueEngine q = new QueueEngine(owner);
    q.join(b);
    q.add(List.of(track("a")), false, 1);
    RelayRouter router = new RelayRouter(() -> 1000L);
    List<RelayFrame> received = new ArrayList<>(),
        requests = new ArrayList<>(),
        rejected = new ArrayList<>();
    router.peer(owner, requests::add);
    router.peer(b, received::add);
    router.peer(attacker, rejected::add);
    router.policy(q.snapshot());
    RelayFrame request = RelayFrame.request(q.snapshot().sessionId(), q.snapshot().current(), 0);
    router.receive(attacker, request);
    assertEquals(RelayFrame.ERROR, rejected.getFirst().kind());
    assertTrue(requests.isEmpty());
    router.receive(b, request);
    assertEquals(1, requests.size());
    RelayFrame data =
        new RelayFrame(
            RelayFrame.DATA,
            request.session(),
            request.entry(),
            request.request(),
            0,
            1,
            true,
            new byte[] {7},
            "");
    router.receive(attacker, data);
    assertTrue(received.isEmpty());
    assertEquals(1, router.pendingCount());
    router.receive(owner, data);
    assertEquals(7, received.getFirst().data()[0]);
    RelayFrame old = RelayFrame.request(request.session(), request.entry(), 0);
    router.receive(b, old);
    q.clear();
    router.policy(q.snapshot());
    int before = received.size();
    router.receive(
        owner,
        new RelayFrame(
            RelayFrame.DATA,
            old.session(),
            old.entry(),
            old.request(),
            0,
            1,
            true,
            new byte[] {9},
            ""));
    assertEquals(before, received.size());
    assertEquals(0, router.pendingCount());
  }

  @Test
  void producerMayServeWhileInSoloButReceiverMustRemainTogether() {
    UUID owner = UUID.randomUUID(), b = UUID.randomUUID();
    QueueEngine q = new QueueEngine(owner);
    q.join(b);
    q.add(List.of(track("a")), false, 1);
    q.leave(owner);
    RelayRouter router = new RelayRouter(() -> 1000L);
    List<RelayFrame> asks = new ArrayList<>(), replies = new ArrayList<>();
    router.peer(owner, asks::add);
    router.peer(b, replies::add);
    router.policy(q.snapshot());
    RelayFrame f = RelayFrame.request(q.snapshot().sessionId(), q.snapshot().current(), 0);
    router.receive(b, f);
    assertEquals(1, asks.size());
    q.leave(b);
    router.policy(q.snapshot());
    assertEquals(0, router.pendingCount());
    assertEquals(RelayFrame.ERROR, replies.getLast().kind());
  }

  @Test
  void boundedInflightAndDisconnectFailPromptly() {
    UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    QueueEngine q = new QueueEngine(a);
    q.join(b);
    q.add(List.of(track("a")), false, 1);
    RelayRouter router = new RelayRouter(() -> 1000L);
    List<RelayFrame> answers = new ArrayList<>();
    router.peer(a, f -> {});
    router.peer(b, answers::add);
    router.policy(q.snapshot());
    for (int i = 0; i < 30; i++)
      router.receive(
          b,
          RelayFrame.request(
              q.snapshot().sessionId(), q.snapshot().current(), i * RelayFrame.CHUNK));
    assertEquals(16, router.pendingCount());
    assertEquals(14, answers.size());
    router.remove(a);
    assertEquals(0, router.pendingCount());
    assertEquals(30, answers.size());
  }

  @Test
  void invalidOrOversizedFramesAreRejectedBeforeAllocation() {
    RelayFrame f = RelayFrame.request(UUID.randomUUID(), UUID.randomUUID(), 0);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RelayFrame(
                RelayFrame.DATA,
                f.session(),
                f.entry(),
                f.request(),
                0,
                -1,
                false,
                new byte[RelayFrame.CHUNK + 1],
                ""));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RelayFrame(
                RelayFrame.DATA,
                f.session(),
                f.entry(),
                f.request(),
                0,
                100,
                true,
                new byte[7],
                ""));
  }

  @Test
  void fullOriginalBytesReachTwoUnlicensedPeersAndCacheSurvivesSolo() throws Exception {
    byte[] original = new byte[RelayFrame.CHUNK * 25 + 1337];
    new Random(83).nextBytes(original);
    UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
    QueueEngine q = new QueueEngine(a);
    q.join(b);
    q.join(c);
    q.add(List.of(track("a")), false, 1);
    SessionSnapshot snapshot = q.snapshot();
    QueueEntry entry = snapshot.currentEntry();
    AtomicInteger opened = new AtomicInteger(), blocked = new AtomicInteger();
    RelayRouter router = new RelayRouter(() -> System.nanoTime() / 1_000_000);
    try (var network = Executors.newSingleThreadExecutor();
        AudioRepository source =
            new AudioRepository(
                e -> {
                  opened.incrementAndGet();
                  return new AudioRepository.Source(
                      new ByteArrayInputStream(original), original.length, false);
                },
                null,
                folder.resolve("a"))) {
      RelayPeer[] peers = new RelayPeer[3];
      AudioRepository[] receivers = new AudioRepository[2];
      try {
        peers[0] = new RelayPeer(a, f -> network.execute(() -> router.receive(a, f)), source);
        for (int i = 0; i < 2; i++) {
          int index = i;
          UUID id = i == 0 ? b : c;
          receivers[i] =
              new AudioRepository(
                  e -> {
                    blocked.incrementAndGet();
                    throw new IOException("未登录/没有会员");
                  },
                  e -> peers[index + 1].open(e),
                  folder.resolve("r" + i));
          peers[i + 1] =
              new RelayPeer(id, f -> network.execute(() -> router.receive(id, f)), receivers[i]);
        }
        List<UUID> ids = List.of(a, b, c);
        for (int i = 0; i < 3; i++) {
          int index = i;
          router.peer(ids.get(i), f -> network.execute(() -> peers[index].receive(f)));
          peers[i].policy(snapshot.sessionId(), List.of(entry), i != 0);
        }
        router.policy(snapshot);
        try (var workers = Executors.newFixedThreadPool(2)) {
          Future<byte[]> r1 =
              workers.submit(
                  () -> {
                    try (var input = receivers[0].open(entry)) {
                      return input.input().readAllBytes();
                    }
                  });
          Future<byte[]> r2 =
              workers.submit(
                  () -> {
                    try (var input = receivers[1].open(entry)) {
                      return input.input().readAllBytes();
                    }
                  });
          assertArrayEquals(original, r1.get(10, TimeUnit.SECONDS));
          assertArrayEquals(original, r2.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, opened.get());
        assertEquals(2, blocked.get());
        assertTrue(receivers[0].forwarded(entry.id()));
        assertTrue(receivers[1].forwarded(entry.id()));
        peers[1].receiving(false);
        peers[1].receiving(true);
        try (var reused = receivers[0].open(entry)) {
          assertArrayEquals(original, reused.input().readAllBytes());
        }
        assertEquals(1, opened.get());
      } finally {
        for (RelayPeer p : peers) if (p != null) p.close();
        for (AudioRepository r : receivers) if (r != null) r.close();
      }
    }
  }

  @Test
  void directAudioDoesNotRequestRelayAndCanBePreciselyReread() throws Exception {
    byte[] original = new byte[90000];
    new Random(1).nextBytes(original);
    AtomicInteger fallback = new AtomicInteger(), fetches = new AtomicInteger();
    QueueEntry entry = QueueEntry.of(track("a"));
    try (AudioRepository repo =
        new AudioRepository(
            e -> {
              fetches.incrementAndGet();
              return new AudioRepository.Source(
                  new ByteArrayInputStream(original), original.length, false);
            },
            e -> {
              fallback.incrementAndGet();
              throw new IOException();
            },
            folder)) {
      repo.retain(List.of(entry.id()));
      try (var first = repo.open(entry)) {
        assertArrayEquals(original, first.input().readAllBytes());
      }
      try (var second = repo.open(entry)) {
        assertEquals(65536, second.input().skip(65536));
        assertArrayEquals(
            Arrays.copyOfRange(original, 65536, original.length), second.input().readAllBytes());
      }
      assertEquals(1, fetches.get());
      assertEquals(0, fallback.get());
    }
  }

  @Test
  void brokenSourceIsReportedWithoutForwardingCredentials() throws Exception {
    UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    QueueEngine q = new QueueEngine(a);
    q.join(b);
    q.add(List.of(track("a")), false, 1);
    RelayRouter router = new RelayRouter(() -> 1000);
    List<RelayFrame> responses = new ArrayList<>();
    router.peer(a, f -> router.receive(a, f.failure("private cookie=SECRET")));
    router.peer(b, responses::add);
    router.policy(q.snapshot());
    router.receive(b, RelayFrame.request(q.snapshot().sessionId(), q.snapshot().current(), 0));
    assertFalse(responses.getFirst().error().contains("SECRET"));
  }

  @Test
  void accountChangeCanRetryAnEntryWhosePermissionPreviouslyFailed() throws Exception {
    AtomicInteger opened = new AtomicInteger();
    QueueEntry entry = QueueEntry.of(track("retry"));
    try (AudioRepository repo =
        new AudioRepository(
            e -> {
              if (opened.getAndIncrement() == 0) throw new IOException("No playback permission");
              return new AudioRepository.Source(
                  new ByteArrayInputStream(new byte[] {17}), 1, false);
            },
            null,
            folder)) {
      repo.retain(List.of(entry.id()));
      assertThrows(IOException.class, () -> repo.open(entry));
      repo.invalidate("qq");
      try (var allowed = repo.open(entry)) {
        assertEquals(17, allowed.input().read());
        assertEquals(-1, allowed.input().read());
      }
      assertEquals(2, opened.get());
    }
  }
}
