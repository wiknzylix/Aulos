package dev.shironeko.music;

import dev.shironeko.music.client.AudioEngine;
import dev.shironeko.music.core.*;
import dev.shironeko.music.provider.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Same-host dual OpenAL output probe. Gain is zero; this does NOT measure acoustic latency. */
public final class AudioProbe {
  private static double p(List<Double> values, double percentile) {
    if (values.isEmpty()) return -1;
    var sorted = values.stream().sorted().toList();
    return sorted.get(Math.min(sorted.size() - 1, (int) (sorted.size() * percentile)));
  }

  public static void main(String[] args) throws Exception {
    Path folder = Path.of(args[0]);
    Files.createDirectories(folder);
    long seconds = Long.parseLong(args[1]);
    MusicProvider provider = new Netease(folder.resolve("isolated"));
    QueueEngine q = new QueueEngine(UUID.randomUUID());
    q.add(
        List.of(new TrackRef("netease", "26705082", "Audio probe", "Beethoven", "", "", 428653)),
        false,
        MusicTogether.now());
    q.repeat("ONE");
    AtomicIntegerArray ready = new AtomicIntegerArray(2);
    AtomicLongArray epochs = new AtomicLongArray(2);
    AudioEngine[] audio = new AudioEngine[2];
    for (int i = 0; i < 2; i++) {
      int index = i;
      audio[i] =
          new AudioEngine(
              id -> provider,
              (id, epoch, ok, generation) -> {
                epochs.set(index, epoch);
                ready.set(index, ok ? 1 : -1);
              });
    }
    List<Double> difference = new ArrayList<>(), drift = new ArrayList<>();
    long start = MusicTogether.now(), lastReport = start;
    String failure = "";
    long barrierAt = 0;
    int earlyStarts = 0;
    try {
      while (MusicTogether.now() - start < seconds * 1000) {
        long now = MusicTogether.now();
        q.tick(now);
        SessionSnapshot s = q.snapshot();
        for (var engine : audio) engine.update(s, 0, 0);
        if (s.timeline().status().equals("PREPARING")) {
          boolean ok = true;
          for (int i = 0; i < 2; i++) {
            if (epochs.get(i) != s.timeline().epochMs() || ready.get(i) != 1) ok = false;
            if (epochs.get(i) == s.timeline().epochMs() && ready.get(i) == -1)
              failure = audio[i].status;
          }
          for (var engine : audio)
            if (engine.firstOutputAt != 0) {
              earlyStarts++;
              throw new AssertionError("Output before scheduled timeline start");
            }
          if (ok) {
            if (barrierAt == 0) barrierAt = now;
            if (now - barrierAt >= 800) {
              q.ready(now, 400);
              barrierAt = 0;
            }
          } else if (now - s.timeline().epochMs() > 25000) {
            failure = "Audio prepare timeout: " + audio[0].status + " / " + audio[1].status;
            break;
          }
        } else if (now > s.timeline().epochMs() + 2000
            && audio[0].status.isEmpty()
            && audio[1].status.isEmpty()) {
          difference.add(Math.abs(audio[0].audiblePositionMs - audio[1].audiblePositionMs));
          for (var engine : audio) drift.add(Math.abs(engine.driftMs));
        }
        if (now - lastReport >= 5000) {
          lastReport = now;
          String line =
              MusicTogether.JSON.toJson(
                  Map.of(
                      "elapsed_s",
                      (now - start) / 1000,
                      "status",
                      List.of(audio[0].status, audio[1].status),
                      "position",
                      List.of(audio[0].audiblePositionMs, audio[1].audiblePositionMs),
                      "samples",
                      difference.size(),
                      "pair_p95_ms",
                      p(difference, .95),
                      "timeline_p95_ms",
                      p(drift, .95)));
          System.out.println(line);
          Files.writeString(folder.resolve("progress.json"), line);
        }
        Thread.sleep(50);
      }
    } finally {
      for (var engine : audio) engine.close();
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put(
        "measurement",
        "same host, two independent muted OpenAL devices; not acoustic or two-machine measurement");
    result.put("elapsed_s", (MusicTogether.now() - start) / 1000);
    result.put("samples", difference.size());
    result.put("pair_p95_ms", p(difference, .95));
    result.put("pair_p99_ms", p(difference, .99));
    result.put("timeline_p95_ms", p(drift, .95));
    result.put("failure", failure);
    result.put("early_start_violations", earlyStarts);
    result.put("formats", List.of(audio[0].format, audio[1].format));
    String json = MusicTogether.JSON.toJson(result);
    Files.writeString(folder.resolve("results.json"), json);
    System.out.println(json);
    if (!failure.isEmpty()) throw new IllegalStateException(failure);
  }
}
