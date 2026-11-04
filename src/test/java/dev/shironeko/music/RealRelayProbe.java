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

/** Opt-in own-account audio check; never exports a title, identity, URL, cookie, or music file. */
public final class RealRelayProbe {
  public static void main(String[] args) throws Exception {
    Path accounts = Path.of(args[0]), output = Path.of(args[1]);
    Files.createDirectories(output);
    QQ owner = new QQ(accounts), anonymous = new QQ(output.resolve("anonymous"));
    if (!owner.loggedIn()) throw new IOException("本机测试账号尚未登录");
    TrackRef track = null;
    for (var playlist : owner.playlists(1).items()) {
      var tracks = owner.tracks(playlist.id(), 1);
      if (!tracks.items().isEmpty()) {
        track = tracks.items().getFirst();
        break;
      }
    }
    if (track == null) throw new IOException("测试账号没有可读取的歌单曲目");
    boolean canResolve;
    try {
      anonymous.source(track);
      canResolve = true;
    } catch (Exception e) {
      canResolve = false;
    }
    final boolean injectFailure = canResolve;
    UUID a = UUID.randomUUID(), b = UUID.randomUUID();
    QueueEngine queue = new QueueEngine(a);
    queue.join(b);
    queue.add(List.of(track), false, MusicTogether.now(), a);
    QueueEntry entry = queue.snapshot().currentEntry();
    RelayRouter router = new RelayRouter(MusicTogether::now);
    RelayPeer[] peers = new RelayPeer[2];
    AtomicInteger fallbacks = new AtomicInteger();
    Map<String, Object> report = new LinkedHashMap<>();
    long start = MusicTogether.now();
    try (var network = Executors.newSingleThreadExecutor();
        AudioRepository source =
            new AudioRepository(
                e -> AudioRepository.direct(owner, e), null, output.resolve("source-cache"));
        AudioRepository listener =
            new AudioRepository(
                e -> {
                  if (injectFailure)
                    throw new IOException(
                        "Injected direct-source failure for a publicly playable track");
                  return AudioRepository.direct(anonymous, e);
                },
                e -> {
                  fallbacks.incrementAndGet();
                  return peers[1].open(e);
                },
                output.resolve("listener-cache"))) {
      try {
        peers[0] = new RelayPeer(a, f -> network.execute(() -> router.receive(a, f)), source);
        peers[1] = new RelayPeer(b, f -> network.execute(() -> router.receive(b, f)), listener);
        for (int i = 0; i < 2; i++) {
          int index = i;
          UUID id = i == 0 ? a : b;
          router.peer(id, f -> network.execute(() -> peers[index].receive(f)));
          peers[i].policy(queue.snapshot().sessionId(), List.of(entry), true);
        }
        router.policy(queue.snapshot());
        byte[] received;
        try (var input = listener.open(entry)) {
          received = input.input().readAllBytes();
        }
        byte[] original;
        try (var input = source.open(entry)) {
          original = input.input().readAllBytes();
        }
        if (!Arrays.equals(original, received)) throw new AssertionError("原始音频字节不一致");
        Map<String, Object> format = new LinkedHashMap<>();
        long decoded =
            AudioDecoder.decode(
                new ByteArrayInputStream(received),
                received.length,
                entry.track().durationMs(),
                new AudioDecoder.Sink() {
                  public void format(int rate, int channels, int bits, String codec) {
                    format.put("codec", codec);
                    format.put("sample_rate", rate);
                    format.put("bits", bits);
                    format.put("channels", channels);
                  }

                  public void pcm(float[] samples, int rate, int channels, long frame) {}
                });
        report.put("platform", "qq");
        report.put("restored_owner_login", true);
        report.put("listener_logged_in", anonymous.loggedIn());
        report.put("fallbacks", fallbacks.get());
        report.put("listener_forwarded", listener.forwarded(entry.id()));
        report.put("direct_failure_injected", injectFailure);
        report.put("anonymous_could_resolve_original_track", canResolve);
        report.put("bytes_equal", true);
        report.put("encoded_bytes", received.length);
        report.put(
            "encoded_sha256",
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(received)));
        report.put("metadata_ms", entry.track().durationMs());
        report.put("decoded_ms", decoded);
        report.put("format", format);
        report.put("total_check_ms", MusicTogether.now() - start);
        report.put(
            "scope",
            "Own live QQ WeChat account, full real platform audio decode and encoded relay; when"
                + " direct_failure_injected=true this is failure injection, not a real VIP-denial"
                + " acceptance test");
        if (fallbacks.get() != 1 || !listener.forwarded(entry.id()))
          throw new AssertionError("This track did not exercise the expected relay fallback");
      } finally {
        for (RelayPeer p : peers) if (p != null) p.close();
      }
    }
    Files.writeString(output.resolve("results.json"), MusicTogether.JSON.toJson(report));
    System.out.println(MusicTogether.JSON.toJson(report));
  }
}
