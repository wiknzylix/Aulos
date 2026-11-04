package dev.shironeko.music.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.shironeko.music.core.*;
import dev.shironeko.music.provider.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

public final class MusicHud {
  private record Cover(Identifier texture, int width, int height) {}

  private record Upload(String url, NativeImage pixels) {}

  private static final LinkedHashMap<String, Cover> covers = new LinkedHashMap<>(16, .75f, true);
  private static final Set<String> pending = new HashSet<>();
  private static final LinkedHashMap<String, Artwork> artwork = new LinkedHashMap<>(32, .75f, true);

  private static final class Artwork {
    String url;
    int failures;
    long retryAt;

    Artwork(String url) {
      this.url = url;
    }
  }

  private static long generation;

  public static String time(long ms) {
    long seconds = Math.max(0, ms) / 1000;
    return seconds / 60 + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
  }

  public static int[] bounds(ClientConfig.HudLayout l, int sw, int sh, boolean lyrics) {
    double scale = renderScale(l, sw, sh, lyrics);
    int w = (int) Math.ceil(l.width * scale), h = (int) Math.ceil((lyrics ? 30 : 83) * scale);
    return new int[] {
      Math.clamp(3 + (int) Math.round(l.x * Math.max(0, sw - w - 6)), 3, Math.max(3, sw - w - 3)),
      Math.clamp(3 + (int) Math.round(l.y * Math.max(0, sh - h - 6)), 3, Math.max(3, sh - h - 3)),
      w,
      h
    };
  }

  public static void position(
      ClientConfig.HudLayout l, double x, double y, int sw, int sh, boolean lyrics) {
    int[] b = bounds(l, sw, sh, lyrics);
    l.x = Math.clamp((x - 3) / Math.max(1, sw - b[2] - 6), 0, 1);
    l.y = Math.clamp((y - 3) / Math.max(1, sh - b[3] - 6), 0, 1);
  }

  private static double renderScale(ClientConfig.HudLayout l, int sw, int sh, boolean lyrics) {
    return Math.max(
        .01,
        Math.min(l.scale, Math.min((sw - 6) / l.width, (double) (sh - 6) / (lyrics ? 30 : 83))));
  }

  public static void render(GuiGraphics g, boolean editing) {
    SessionSnapshot s = MusicClient.state();
    QueueEntry entry = s == null ? null : s.currentEntry();
    if (entry == null && !editing) return;
    TrackRef t =
        entry == null
            ? new TrackRef("netease", "preview", "Aulos HUD 预览", "歌手 / Artist", "", "", 201000)
            : entry.track();
    long pos = entry == null ? 31000 : MusicClient.position();
    renderCard(g, t, pos, s, editing);
    renderLyrics(g, pos, editing);
  }

  private static void begin(GuiGraphics g, ClientConfig.HudLayout l, boolean lyrics) {
    int[] b = bounds(l, g.guiWidth(), g.guiHeight(), lyrics);
    g.pose().pushMatrix();
    g.pose().translate(b[0], b[1]);
    float scale = (float) renderScale(l, g.guiWidth(), g.guiHeight(), lyrics);
    g.pose().scale(scale, scale);
  }

  private static void renderCard(
      GuiGraphics g, TrackRef t, long pos, SessionSnapshot s, boolean editing) {
    var c = MusicClient.config;
    var l = c.card;
    int w = (int) l.width;
    var font = Minecraft.getInstance().font;
    begin(g, l, false);
    int bg = ((int) (c.opacity * 255) << 24) | 0x111415;
    g.fill(0, 0, w, 83, bg);
    g.renderOutline(0, 0, w, 83, c.progress);
    Cover image = cover(t);
    if (image != null)
      g.blit(
          RenderPipelines.GUI_TEXTURED,
          image.texture(),
          7,
          8,
          0,
          0,
          48,
          48,
          image.width(),
          image.height(),
          image.width(),
          image.height());
    else {
      g.fill(7, 8, 55, 56, 0xFF35423B);
      g.drawCenteredString(font, "♪", 31, 27, c.progress);
    }
    scaledText(g, t.title(), 63, 8, w - 76, 1.12f, 0xFFE6E9E7);
    scaledText(g, t.artist(), 63, 22, w - 76, .9f, 0xFFB7C6BA);
    Marquee.draw(
        g,
        font,
        MusicClient.mode()
            + (MusicClient.audio == null || MusicClient.audio.format.isBlank()
                ? ""
                : " " + MusicClient.audio.format),
        63,
        34,
        w - 76,
        0xFFAAAAAA);
    int bar = w - 76;
    g.fill(63, 47, 63 + bar, 51, c.progressBackground);
    g.fill(
        63,
        47,
        63 + (int) (bar * Math.clamp((double) pos / Math.max(1, t.durationMs()), 0, 1)),
        51,
        c.progress);
    String clock = time(pos) + " / " + time(t.durationMs());
    g.drawString(font, clock, w - font.width(clock) - 8, 56, 0xFFE6E9E7, false);
    QueueEntry next = s == null ? null : s.nextEntry();
    String count = "队列 " + (s == null ? 3 : s.queue().size()) + " 首";
    int countWidth = font.width(count);
    g.drawString(font, count, w - countWidth - 7, 70, 0xFFB7C6BA, false);
    Marquee.draw(
        g,
        font,
        "下一首："
            + (s != null && s.randomNextHidden()
                ? "随机播放"
                : next == null ? (editing ? "下一首歌曲" : "无") : next.track().title()),
        7,
        70,
        w - countWidth - 25,
        0xFFB7C6BA);
    if (!editing && MusicClient.audio != null && !MusicClient.audio.status.isEmpty())
      g.drawString(
          font,
          font.plainSubstrByWidth(MusicClient.audio.status, w - 14),
          7,
          -12,
          MusicClient.audio.status.contains("失败") ? 0xFFFF5555 : 0xFFAAAAAA,
          false);
    g.pose().popMatrix();
  }

  private static void renderLyrics(GuiGraphics g, long pos, boolean editing) {
    var c = MusicClient.config;
    var l = c.lyrics;
    var font = Minecraft.getInstance().font;
    Lyrics lyric = MusicClient.lyrics;
    if (editing && lyric.lines().isEmpty())
      lyric =
          Lyrics.parse(
              "[0,200000](0,100000,0)让音乐(100000,100000,0)陪伴旅途\n[200000,5000](200000,5000,0)下一行歌词",
              "[00:00.00]Let music accompany your journey\n[03:20.00]The next lyric line",
              "yrc");
    int index = lyric.index(pos);
    begin(g, l, true);
    int width = (int) l.width;
    if (editing) g.fill(0, 0, width, 30, 0x80202524);
    if (lyric.lines().isEmpty()) {
      g.drawCenteredString(font, "暂无歌词", width / 2, 5, c.lyricBase);
      g.pose().popMatrix();
      return;
    }
    if (index < 0) index = 0;
    for (int row = 0; row < 2 && index + row < lyric.lines().size(); row++) {
      Lyrics.Line line = lyric.lines().get(index + row);
      int y = 3 + row * 12,
          original = font.width(line.text()),
          translated = line.translation().isEmpty() ? 0 : font.width(" / " + line.translation());
      int combined = original + translated, x = Math.max(0, (width - combined) / 2);
      // Keep original and translation on the same line; long lines scroll within the editable
      // region.
      if (combined > width && row == 0)
        x =
            -(int)
                ((combined - width)
                    * Math.clamp(
                        (double) (pos - line.start()) / Math.max(1, line.end() - line.start()),
                        0,
                        1));
      int[] screen = bounds(l, g.guiWidth(), g.guiHeight(), true);
      g.enableScissor(0, y, width, y + 12);
      int base = row == 0 ? c.lyricBase : (0xA0000000 | (c.lyricBase & 0xFFFFFF));
      g.drawString(font, line.text(), x, y, base, true);
      if (translated > 0)
        g.drawString(font, " / " + line.translation(), x + original, y, c.translation, true);
      if (row == 0) {
        double highlighted = 0;
        if (line.words().isEmpty()) highlighted = original;
        else
          for (Lyrics.Word word : line.words())
            highlighted +=
                font.width(word.text())
                    * Math.clamp(
                        (double) (pos - word.start()) / Math.max(1, word.duration()), 0, 1);
        int right = screen[0] + (int) ((x + highlighted) * l.scale);
        if (right > screen[0]) {
          g.enableScissor(0, y, Math.min(width, (int) (x + highlighted)), y + 12);
          g.drawString(font, line.text(), x, y, c.lyricActive, true);
          g.disableScissor();
        }
      }
      g.disableScissor();
    }
    g.pose().popMatrix();
  }

  private static void scaledText(
      GuiGraphics g, String text, int x, int y, int width, float scale, int colour) {
    g.pose().pushMatrix();
    g.pose().translate(x, y);
    g.pose().scale(scale, scale);
    Marquee.draw(g, Minecraft.getInstance().font, text, 0, 0, (int) (width / scale), colour);
    g.pose().popMatrix();
  }

  private static Cover cover(TrackRef track) {
    if (track.id().equals("preview")) return null;
    String key = track.platform() + ":" + track.id();
    Artwork state = artwork.computeIfAbsent(key, ignored -> new Artwork(track.cover()));
    Cover cached = covers.get(state.url);
    if (cached != null
        || pending.contains(key)
        || MusicClient.now() < state.retryAt
        || pending.size() >= 2) return cached;
    pending.add(key);
    long gen = generation;
    var provider = MusicClient.providers.get(track.platform());
    String known = state.url;
    MusicClient.async(
        () -> {
          var loaded = CoverArt.load(track, known, provider);
          if (loaded == null) return null;
          var image = loaded.pixels();
          NativeImage pixels = new NativeImage(128, 128, false);
          for (int y = 0; y < 128; y++)
            for (int x = 0; x < 128; x++)
              pixels.setPixel(
                  x, y, image.getRGB(x * image.getWidth() / 128, y * image.getHeight() / 128));
          return new Upload(loaded.url(), pixels);
        },
        result -> {
          if (gen != generation) {
            if (result != null) result.pixels().close();
            return;
          }
          pending.remove(key);
          if (result == null) {
            state.failures++;
            state.retryAt =
                MusicClient.now() + Math.min(60000, 2000L << Math.min(5, state.failures - 1));
            return;
          }
          Identifier id = Identifier.fromNamespaceAndPath("aulos", "cover/" + UUID.randomUUID());
          Minecraft.getInstance()
              .getTextureManager()
              .register(id, new DynamicTexture(() -> "Aulos cover", result.pixels()));
          state.url = result.url();
          state.failures = 0;
          state.retryAt = 0;
          Cover old = covers.put(state.url, new Cover(id, 128, 128));
          if (old != null) Minecraft.getInstance().getTextureManager().release(old.texture());
          while (covers.size() > 16) {
            String first = covers.keySet().iterator().next();
            Minecraft.getInstance().getTextureManager().release(covers.remove(first).texture());
          }
        });
    while (artwork.size() > 32) artwork.remove(artwork.keySet().iterator().next());
    return null;
  }

  public static void clear() {
    generation++;
    for (Cover c : covers.values())
      Minecraft.getInstance().getTextureManager().release(c.texture());
    covers.clear();
    pending.clear();
    artwork.clear();
  }
}
