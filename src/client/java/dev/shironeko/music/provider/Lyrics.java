package dev.shironeko.music.provider;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import java.util.zip.InflaterInputStream;

public record Lyrics(List<Line> lines) {
  public record Word(String text, long start, long duration) {}

  public record Line(long start, long end, String text, String translation, List<Word> words) {
    public double fraction(long time) {
      if (words.isEmpty()) return time >= start ? 1 : 0;
      double done = 0, total = 0;
      for (Word w : words) {
        int len = w.text().codePointCount(0, w.text().length());
        total += len;
        done += len * Math.clamp((double) (time - w.start()) / Math.max(1, w.duration()), 0, 1);
      }
      return total == 0 ? 0 : done / total;
    }
  }

  public static Lyrics empty() {
    return new Lyrics(List.of());
  }

  public int index(long time) {
    int lo = 0, hi = lines.size() - 1, result = -1;
    while (lo <= hi) {
      int mid = (lo + hi) >>> 1;
      if (lines.get(mid).start() <= time) {
        result = mid;
        lo = mid + 1;
      } else hi = mid - 1;
    }
    return result;
  }

  private static final Pattern LRC = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\]"),
      TIMED = Pattern.compile("^\\[(\\d+),(\\d+)\\](.*)$"),
      YWORD = Pattern.compile("\\((\\d+),(\\d+),\\d+\\)([^()]*)"),
      KWORD = Pattern.compile("<(\\d+),(\\d+),\\d+>([^<]*)"),
      QWORD = Pattern.compile("([^()]+)\\((\\d+),(\\d+)\\)");

  public static Lyrics parse(String raw, String translation, String format) {
    if (raw == null || raw.isBlank()) return empty();
    if (raw.length() > 2_000_000) throw new IllegalArgumentException("歌词过大");
    if (format.equals("qrc")) {
      Matcher xml = Pattern.compile("LyricContent=\"([\\s\\S]*?)\"\\s*/?>").matcher(raw);
      if (xml.find())
        raw =
            xml.group(1)
                .replace("&#10;", "\n")
                .replace("&#13;", "")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }
    List<Line> out = new ArrayList<>();
    Map<Long, String> translations = new HashMap<>();
    for (Line l : lrc(translation)) translations.put(l.start(), l.text());
    if (format.equals("lrc")) out.addAll(lrc(raw));
    else
      for (String row : raw.split("\\r?\\n")) {
        Matcher m = TIMED.matcher(row);
        if (!m.matches()) continue;
        long start = Long.parseLong(m.group(1)), end = start + Long.parseLong(m.group(2));
        String content = m.group(3);
        List<Word> words = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        Matcher w =
            (format.equals("krc") ? KWORD : format.equals("qrc") ? QWORD : YWORD).matcher(content);
        while (w.find()) {
          String text;
          long at, duration;
          if (format.equals("qrc")) {
            text = w.group(1);
            at = Long.parseLong(w.group(2));
            duration = Long.parseLong(w.group(3));
          } else {
            text = w.group(3);
            at = Long.parseLong(w.group(1)) + (format.equals("krc") ? start : 0);
            duration = Long.parseLong(w.group(2));
          }
          plain.append(text);
          words.add(new Word(text, at, duration));
        }
        if (!plain.isEmpty())
          out.add(new Line(start, end, plain.toString(), "", List.copyOf(words)));
      }
    out.sort(Comparator.comparingLong(Line::start));
    List<String> ktrans = new ArrayList<>();
    if (format.equals("krc")) {
      try {
        Matcher language = Pattern.compile("\\[language:([^]]+)]").matcher(raw);
        if (language.find()) {
          JsonObject j =
              JsonParser.parseString(
                      new String(
                          Base64.getDecoder().decode(language.group(1)), StandardCharsets.UTF_8))
                  .getAsJsonObject();
          for (JsonElement e : Web.a(j, "content"))
            if (Web.n(e, "type") == 1)
              for (JsonElement row : Web.a(e, "lyricContent")) {
                StringJoiner text = new StringJoiner(" ");
                for (JsonElement part : row.getAsJsonArray()) text.add(part.getAsString());
                ktrans.add(text.toString());
              }
        }
      } catch (Exception ignored) {
      }
    }
    for (int i = 0; i < out.size(); i++) {
      Line l = out.get(i);
      String tr = translations.getOrDefault(l.start(), "");
      if (tr.isEmpty() && i < ktrans.size()) tr = ktrans.get(i);
      long end =
          l.end() == Long.MAX_VALUE
              ? (i + 1 < out.size() ? out.get(i + 1).start() : Long.MAX_VALUE)
              : l.end();
      out.set(i, new Line(l.start(), end, l.text(), tr, l.words()));
    }
    return new Lyrics(List.copyOf(out));
  }

  private static List<Line> lrc(String raw) {
    List<Line> out = new ArrayList<>();
    if (raw == null) return out;
    long offset = 0;
    Matcher off = Pattern.compile("\\[offset:([+-]?\\d+)]").matcher(raw);
    if (off.find()) offset = Long.parseLong(off.group(1));
    for (String row : raw.split("\\r?\\n")) {
      Matcher m = LRC.matcher(row);
      List<Long> times = new ArrayList<>();
      int last = 0;
      while (m.find()) {
        times.add(
            Math.max(
                0,
                Long.parseLong(m.group(1)) * 60000
                    + Math.round(Double.parseDouble(m.group(2)) * 1000)
                    + offset));
        last = m.end();
      }
      String text = row.substring(last).trim();
      if (!text.isEmpty())
        for (long t : times) out.add(new Line(t, Long.MAX_VALUE, text, "", List.of()));
    }
    out.sort(Comparator.comparingLong(Line::start));
    return out;
  }

  public static String inflate(byte[] bytes) throws IOException {
    try (var in = new InflaterInputStream(new ByteArrayInputStream(bytes))) {
      byte[] out = in.readNBytes(2_000_001);
      if (out.length > 2_000_000) throw new IOException("歌词过大");
      return new String(out, StandardCharsets.UTF_8);
    }
  }

  public static String decodeKrc(String data) throws Exception {
    byte[] bytes = Base64.getDecoder().decode(data);
    if (bytes.length < 4) return "";
    int[] key = {64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105};
    byte[] raw = Arrays.copyOfRange(bytes, 4, bytes.length);
    for (int i = 0; i < raw.length; i++) raw[i] ^= (byte) key[i % 16];
    return inflate(raw);
  }

  public static String decodeQq(String raw) {
    if (raw.isBlank()) return "";
    if (raw.startsWith("[") || raw.startsWith("<?") || raw.startsWith("<Qrc")) return raw;
    if (raw.matches("(?i)[0-9a-f]+") && raw.length() % 16 == 0)
      try {
        return QrcCrypto.decrypt(raw);
      } catch (Exception ignored) {
      }
    try {
      String text = new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8);
      if (text.contains("[") || text.contains("LyricContent")) return text;
    } catch (Exception ignored) {
    }
    return "";
  }
}
