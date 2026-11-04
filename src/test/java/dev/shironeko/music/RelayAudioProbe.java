package dev.shironeko.music;

import dev.shironeko.music.client.*;
import dev.shironeko.music.core.*;
import dev.shironeko.music.net.*;
import dev.shironeko.music.provider.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Three real muted OpenAL contexts with two denied direct sources and encoded-byte fallback. */
public final class RelayAudioProbe {
  public static void main(String[] args) throws Exception {
    Path folder = Path.of(args[0]);
    Files.createDirectories(folder);
    byte[] encoded = HiResTest.fixture(96000);
    UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
    List<UUID> ids = List.of(a, b, c);
    QueueEngine queue = new QueueEngine(a);
    queue.join(b);
    queue.join(c);
    long began = MusicTogether.now();
    queue.add(
        List.of(new TrackRef("qq", "relay-native", "Original fixture", "", "", "", 2000)),
        false,
        began,
        a);
    QueueEntry entry = queue.snapshot().currentEntry();
    RelayRouter router = new RelayRouter(MusicTogether::now);
    AtomicLongArray ready = new AtomicLongArray(3);
    AtomicInteger failed = new AtomicInteger(),
        direct = new AtomicInteger(),
        denied = new AtomicInteger();
    List<AudioRepository> repos = new ArrayList<>();
    List<RelayPeer> peers = new ArrayList<>();
    List<AudioEngine> engines = new ArrayList<>();
    Map<String, Object> report = new LinkedHashMap<>();
    try (var network = Executors.newScheduledThreadPool(2)) {
      try {
        for (int i = 0; i < 3; i++) {
          int index = i;
          AudioRepository repo =
              new AudioRepository(
                  e -> {
                    if (index != 0) {
                      denied.incrementAndGet();
                      throw new IOException("未登录/没有会员");
                    }
                    direct.incrementAndGet();
                    return new AudioRepository.Source(
                        new ByteArrayInputStream(encoded), encoded.length, false);
                  },
                  e -> peers.get(index).open(e),
                  folder.resolve("cache-" + i));
          repos.add(repo);
          peers.add(
              new RelayPeer(
                  ids.get(i),
                  f ->
                      network.schedule(
                          () -> router.receive(ids.get(index), f), 8, TimeUnit.MILLISECONDS),
                  repo));
          router.peer(
              ids.get(i),
              f -> network.schedule(() -> peers.get(index).receive(f), 8, TimeUnit.MILLISECONDS));
          peers.get(i).policy(queue.snapshot().sessionId(), List.of(entry), true);
          MusicProvider invalidator =
              (MusicProvider)
                  java.lang.reflect.Proxy.newProxyInstance(
                      MusicProvider.class.getClassLoader(),
                      new Class[] {MusicProvider.class},
                      (o, m, x) -> null);
          engines.add(
              new AudioEngine(
                  p -> invalidator,
                  (id, epoch, ok, g) -> {
                    if (ok) ready.set(index, MusicTogether.now());
                    else failed.incrementAndGet();
                  },
                  folder.resolve("unused-" + i),
                  repo));
        }
        router.policy(queue.snapshot());
        long deadline = began + 15000;
        while ((ready.get(0) == 0 || ready.get(1) == 0 || ready.get(2) == 0)
            && MusicTogether.now() < deadline
            && failed.get() == 0) {
          for (AudioEngine engine : engines) engine.update(queue.snapshot(), 0, 0, 1);
          Thread.sleep(5);
        }
        if (failed.get() != 0 || ready.get(0) == 0 || ready.get(1) == 0 || ready.get(2) == 0)
          throw new AssertionError(
              "Relay prepare failed: " + engines.stream().map(e -> e.status).toList());
        queue.ready(MusicTogether.now(), 400);
        router.policy(queue.snapshot());
        long scheduled = queue.snapshot().timeline().epochMs(), until = scheduled + 900;
        List<Double> spreads = new ArrayList<>();
        int early = 0;
        while (MusicTogether.now() < until) {
          for (AudioEngine engine : engines) {
            engine.update(queue.snapshot(), 0, 0, 1);
            if (engine.firstOutputAt > 0 && engine.firstOutputAt < scheduled) early++;
          }
          if (engines.stream().allMatch(e -> e.firstOutputAt > 0)) {
            double min = engines.stream().mapToDouble(e -> e.audiblePositionMs).min().orElseThrow();
            double max = engines.stream().mapToDouble(e -> e.audiblePositionMs).max().orElseThrow();
            spreads.add(max - min);
          }
          Thread.sleep(5);
        }
        if (early != 0 || spreads.isEmpty() || failed.get() != 0)
          throw new AssertionError("Relay playback failed");
        List<Object> listeners = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
          byte[] bytes;
          try (var source = repos.get(i).open(entry)) {
            bytes = source.input().readAllBytes();
          }
          if (!Arrays.equals(encoded, bytes)) throw new AssertionError("Encoded bytes differ");
          final long[] samples = {0};
          AudioDecoder.decode(
              new ByteArrayInputStream(bytes),
              bytes.length,
              2000,
              new AudioDecoder.Sink() {
                public void format(int r, int channels, int bits, String codec) {
                  if (r != 96000 || channels != 2 || bits != 24)
                    throw new AssertionError("Quality changed");
                }

                public void pcm(float[] pcm, int r, int channels, long frame) {
                  for (int j = 0; j < pcm.length; j++)
                    if (pcm[j] != HiResTest.sample(frame + j / 2, j % 2) / 8388608f)
                      throw new AssertionError("PCM altered");
                  samples[0] += pcm.length / 2;
                }
              });
          listeners.add(
              Map.of(
                  "forwarded",
                  repos.get(i).forwarded(entry.id()),
                  "ready_ms",
                  ready.get(i) - began,
                  "first_output_after_schedule_ms",
                  engines.get(i).firstOutputAt - scheduled,
                  "verified_samples_per_channel",
                  samples[0]));
        }
        Collections.sort(spreads);
        report.put("listeners", listeners);
        report.put(
            "encoded_sha256",
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded)));
        report.put("encoded_bytes", encoded.length);
        report.put("direct_downloads", direct.get());
        report.put("denied_direct_sources", denied.get());
        report.put("pair_cursor_p95_ms", spreads.get((int) (spreads.size() * .95)));
        report.put("early_start_violations", early);
        report.put(
            "scope",
            "Three muted OpenAL contexts on one host, delayed in-process transport; not two-machine"
                + " acoustic measurement");
      } finally {
        for (AudioEngine e : engines) e.close();
        for (RelayPeer p : peers) p.close();
        for (AudioRepository r : repos) r.close();
      }
    }
    Files.writeString(folder.resolve("results.json"), MusicTogether.JSON.toJson(report));
    System.out.println(MusicTogether.JSON.toJson(report));
  }
}
