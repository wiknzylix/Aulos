package dev.shironeko.music.core;

import java.text.Normalizer;
import java.util.*;

/** User-selected rule: both names >=99%, with at most five seconds duration difference. */
public final class TrackMatcher {
  public static String normalize(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .replaceAll("[\\p{P}\\p{Z}\\s]", "");
  }

  public static double similarity(String a, String b) {
    int[] x = normalize(a).codePoints().toArray(), y = normalize(b).codePoints().toArray();
    if (x.length == 0 || y.length == 0) return 0;
    int[] previous = new int[y.length + 1];
    for (int j = 0; j <= y.length; j++) previous[j] = j;
    for (int i = 1; i <= x.length; i++) {
      int[] row = new int[y.length + 1];
      row[0] = i;
      for (int j = 1; j <= y.length; j++)
        row[j] =
            Math.min(
                Math.min(row[j - 1] + 1, previous[j] + 1),
                previous[j - 1] + (x[i - 1] == y[j - 1] ? 0 : 1));
      previous = row;
    }
    return 1.0 - (double) previous[y.length] / Math.max(x.length, y.length);
  }

  public static boolean same(TrackRef a, TrackRef b) {
    if (a.platform().equals(b.platform()) && a.id().equals(b.id())) return true;
    return Math.abs(a.durationMs() - b.durationMs()) <= 5000
        && similarity(a.title(), b.title()) >= .99
        && similarity(a.artist(), b.artist()) >= .99;
  }

  public static Optional<TrackRef> best(TrackRef track, List<TrackRef> results) {
    return results.stream()
        .filter(t -> same(track, t))
        .min(Comparator.comparingLong(t -> Math.abs(t.durationMs() - track.durationMs())));
  }

  private TrackMatcher() {}
}
