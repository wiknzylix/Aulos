package dev.shironeko.music.client;

import java.util.*;
import java.util.function.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class HudEditor extends Screen {
  private final Screen parent;
  private boolean lyric = false, showPanel = true;
  private int drag = 0;
  private double startX, startY, originX, originY, originScale, originWidth;
  private int panelScroll = 0;
  private final SmoothScroll panelMotion = new SmoothScroll();
  private int handleX, handleY;
  private double originalHeight;
  private FlatButton saveButton, panelButton;

  public HudEditor(Screen parent) {
    super(Component.literal("Aulos HUD 布局与颜色"));
    this.parent = parent;
  }

  private ClientConfig.HudLayout layout() {
    return lyric ? MusicClient.config.lyrics : MusicClient.config.card;
  }

  protected void init() {
    clearWidgets();
    saveButton =
        addRenderableWidget(new FlatButton(width - 202, height - 30, 90, "保存返回", this::onClose));
    panelButton =
        addRenderableWidget(
            new FlatButton(
                width - 106,
                height - 30,
                90,
                showPanel ? "隐藏设置" : "显示设置",
                () -> {
                  showPanel = !showPanel;
                  init();
                }));
    if (!showPanel) {
      saveButton.setY(8);
      panelButton.setY(8);
    }
    panelMotion.limit(Math.max(0, 394 - height));
    if (!showPanel) return;
    int x = width - 202, y = 36 - panelScroll;
    addRenderableWidget(
        new FlatButton(
            x,
            y,
            90,
            "音乐卡片",
            () -> {
              lyric = false;
              init();
            }));
    addRenderableWidget(
        new FlatButton(
            x + 96,
            y,
            90,
            "歌词",
            () -> {
              lyric = true;
              init();
            }));
    y += 25;
    addRenderableWidget(
        new ValueSlider(
            x,
            y,
            186,
            "缩放",
            layout().scale,
            .4,
            3,
            v -> {
              layout().scale = v;
              clamp();
            }));
    y += 25;
    addRenderableWidget(
        new ValueSlider(
            x,
            y,
            186,
            "透明度",
            MusicClient.config.opacity,
            0,
            1,
            v -> MusicClient.config.opacity = v));
    y += 25;
    addRenderableWidget(
        new FlatButton(
            x,
            y,
            90,
            "区域变窄",
            () -> {
              layout().width = Math.max(160, layout().width - 20);
              clamp();
            }));
    addRenderableWidget(
        new FlatButton(
            x + 96,
            y,
            90,
            "区域变宽",
            () -> {
              layout().width = Math.min(1400, layout().width + 20);
              clamp();
            }));
    y += 24;
    addRenderableWidget(
        new FlatButton(
            x,
            y,
            186,
            "水平居中",
            () -> {
              layout().x = .5;
              clamp();
            }));
    y += 31;
    ClientConfig c = MusicClient.config;
    colour(x, y, "进度条", () -> c.progress, v -> c.progress = v);
    y += 34;
    colour(x, y, "进度条背景", () -> c.progressBackground, v -> c.progressBackground = v);
    y += 34;
    colour(x, y, "歌词已唱", () -> c.lyricActive, v -> c.lyricActive = v);
    y += 34;
    colour(x, y, "歌词未唱", () -> c.lyricBase, v -> c.lyricBase = v);
    y += 34;
    colour(x, y, "翻译", () -> c.translation, v -> c.translation = v);
    y += 32;
    addRenderableWidget(
        new FlatButton(
            x,
            y,
            186,
            "恢复布局和颜色默认值",
            () -> {
              ClientConfig fresh = new ClientConfig();
              c.card = fresh.card;
              c.lyrics = fresh.lyrics;
              c.progress = fresh.progress;
              c.progressBackground = fresh.progressBackground;
              c.lyricActive = fresh.lyricActive;
              c.lyricBase = fresh.lyricBase;
              c.translation = fresh.translation;
              c.opacity = fresh.opacity;
              init();
            }));
  }

  private void colour(int x, int y, String label, IntSupplier get, IntConsumer set) {
    EditBox field =
        addRenderableWidget(
            new EditBox(font, x + 82, y, 104, 20, Component.literal(label + " HEX")));
    field.setMaxLength(7);
    field.setValue(String.format(Locale.ROOT, "#%06X", get.getAsInt() & 0xFFFFFF));
    field.setResponder(
        s -> {
          if (s.matches("#?[0-9a-fA-F]{6}")) {
            set.accept(0xFF000000 | Integer.parseInt(s.replace("#", ""), 16));
            field.setTextColor(0xFFE6E9E7);
          } else field.setTextColor(0xFFE3A085);
        });
    field.setSuggestion(null);
  }

  private void clamp() {
    var l = layout();
    l.scale =
        Math.clamp(
            l.scale,
            .4,
            Math.max(
                .4, Math.min(3, Math.min(width / l.width, (double) height / (lyric ? 30 : 83)))));
    int[] b = MusicHud.bounds(l, width, height, lyric);
    l.x = Math.clamp(l.x, 0, 1);
    l.y = Math.clamp(l.y, 0, 1);
  }

  public void render(GuiGraphics g, int mx, int my, float dt) {
    g.fill(0, 0, width, height, 0xB0111415);
    panelMotion.animate();
    int nextScroll = (int) panelMotion.value();
    int delta = nextScroll - panelScroll;
    if (delta != 0) {
      for (var child : children())
        if (child instanceof net.minecraft.client.gui.components.AbstractWidget widget
            && widget != saveButton
            && widget != panelButton) widget.setY(widget.getY() - delta);
      panelScroll = nextScroll;
    }
    MusicHud.render(g, true);
    int[] centered = MusicHud.bounds(MusicClient.config.lyrics, width, height, true);
    if (Math.abs(centered[0] + centered[2] / 2.0 - width / 2.0) <= 1) {
      g.fill(width / 2, 0, width / 2 + 1, height, 0xFFFFFF55);
      g.drawCenteredString(
          font,
          "歌词已水平居中",
          width / 2,
          Math.min(height - 14, centered[1] + centered[3] + 5),
          0xFFFFFF55);
    }
    for (boolean lyrics : List.of(false, true)) {
      var l = lyrics ? MusicClient.config.lyrics : MusicClient.config.card;
      int[] b = MusicHud.bounds(l, width, height, lyrics);
      int color = lyrics == lyric ? 0xFF8BA38C : 0xFF66716A;
      g.renderOutline(b[0], b[1], b[2], b[3], color);
      for (int xx : new int[] {b[0], b[0] + b[2] / 2, b[0] + b[2]})
        for (int yy : new int[] {b[1], b[1] + b[3] / 2, b[1] + b[3]})
          if (xx != b[0] + b[2] / 2 || yy != b[1] + b[3] / 2)
            g.fill(xx - 2, yy - 2, xx + 3, yy + 3, color);
    }
    if (showPanel) {
      int x = width - 207;
      g.fill(x, 4, width - 10, height - 8, 0xF51A1C1D);
      Marquee.draw(g, font, title.getString(), x + 5, 13, 186, 0xFFE6E9E7);
      g.enableScissor(x, 30, width - 10, height - 38);
      String[] labels = {"进度条", "进度条背景", "歌词已唱", "歌词未唱", "翻译"};
      int[] colors = {
        MusicClient.config.progress,
        MusicClient.config.progressBackground,
        MusicClient.config.lyricActive,
        MusicClient.config.lyricBase,
        MusicClient.config.translation
      };
      int y = 36 - panelScroll + 130;
      for (int i = 0; i < labels.length; i++) {
        g.fill(x + 5, y + 3, x + 13, y + 13, colors[i]);
        g.drawString(font, labels[i], x + 18, y + 6, 0xFFE6E9E7, false);
        y += 34;
      }
      super.render(g, mx, my, dt);
      g.disableScissor();
    }
    g.fill(width - 207, saveButton.getY() - 5, width - 10, saveButton.getY() + 25, 0xA0000000);
    saveButton.render(g, mx, my, dt);
    panelButton.render(g, mx, my, dt);
  }

  public boolean mouseClicked(MouseButtonEvent e, boolean twice) {
    boolean footer = saveButton.isMouseOver(e.x(), e.y()) || panelButton.isMouseOver(e.x(), e.y());
    if ((showPanel && e.x() >= width - 207 && e.x() < width - 10 && e.y() >= 4) || footer)
      return super.mouseClicked(e, twice);
    if (e.button() != 0) return false;
    for (boolean lyrics : List.of(true, false)) {
      var l = lyrics ? MusicClient.config.lyrics : MusicClient.config.card;
      int[] b = MusicHud.bounds(l, width, height, lyrics);
      if (e.x() >= b[0] - 5
          && e.x() <= b[0] + b[2] + 5
          && e.y() >= b[1] - 5
          && e.y() <= b[1] + b[3] + 5) {
        lyric = lyrics;
        boolean edgeX = Math.abs(e.x() - b[0]) < 6 || Math.abs(e.x() - b[0] - b[2]) < 6,
            edgeY = Math.abs(e.y() - b[1]) < 6 || Math.abs(e.y() - b[1] - b[3]) < 6;
        drag = lyric && edgeX && !edgeY ? 3 : edgeX || edgeY ? 2 : 1;
        handleX = !edgeX ? 0 : e.x() < b[0] + b[2] / 2.0 ? -1 : 1;
        handleY = !edgeY ? 0 : e.y() < b[1] + b[3] / 2.0 ? -1 : 1;
        originalHeight = b[3];
        startX = e.x();
        startY = e.y();
        originX = b[0];
        originY = b[1];
        originScale = l.scale;
        originWidth = l.width;
        clearFocus();
        setDragging(true);
        return true;
      }
    }
    return super.mouseClicked(e, twice);
  }

  public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
    if (drag == 0) return super.mouseDragged(e, dx, dy);
    var l = layout();
    if (drag == 1) {
      int[] b = MusicHud.bounds(l, width, height, lyric);
      MusicHud.position(
          l, originX + e.x() - startX, originY + e.y() - startY, width, height, lyric);
      if (lyric && Math.abs(originX + e.x() - startX + b[2] / 2.0 - width / 2.0) < 6) l.x = .5;
    } else if (drag == 3) {
      double sign = startX < originX + originWidth * originScale / 2 ? -1 : 1;
      l.width = Math.clamp(originWidth + sign * (e.x() - startX) / originScale, 160, 1400);
    } else {
      double dyScale = handleY * (e.y() - startY) / Math.max(1, originalHeight);
      double dxScale = handleX * (e.x() - startX) / Math.max(1, originWidth * originScale);
      l.scale = originScale * (1 + (Math.abs(dxScale) > Math.abs(dyScale) ? dxScale : dyScale));
    }
    if (drag != 1) {
      double newW = l.width * l.scale, newH = (lyric ? 30 : 83) * l.scale;
      double left =
          handleX < 0
              ? originX + originWidth * originScale - newW
              : handleX == 0 ? originX + (originWidth * originScale - newW) / 2 : originX;
      double upper =
          handleY < 0
              ? originY + originalHeight - newH
              : handleY == 0 ? originY + (originalHeight - newH) / 2 : originY;
      MusicHud.position(l, left, upper, width, height, lyric);
    }
    clamp();
    return true;
  }

  public boolean mouseReleased(MouseButtonEvent e) {
    if (drag != 0) {
      drag = 0;
      setDragging(false);
      init();
      return true;
    }
    return super.mouseReleased(e);
  }

  public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
    if (showPanel && x > width - 210) {
      panelMotion.move(-vertical * 24);
      return true;
    }
    layout().scale += vertical * .05;
    clamp();
    init();
    return true;
  }

  public boolean keyPressed(KeyEvent e) {
    if (getFocused() instanceof EditBox) return super.keyPressed(e);
    int step = (e.modifiers() & 1) != 0 ? 10 : 1;
    var l = layout();
    int[] b = MusicHud.bounds(l, width, height, lyric);
    double unitX = (double) step / Math.max(1, width - b[2] - 6),
        unitY = (double) step / Math.max(1, height - b[3] - 6);
    switch (e.key()) {
      case GLFW.GLFW_KEY_LEFT -> l.x -= unitX;
      case GLFW.GLFW_KEY_RIGHT -> l.x += unitX;
      case GLFW.GLFW_KEY_UP -> l.y -= unitY;
      case GLFW.GLFW_KEY_DOWN -> l.y += unitY;
      default -> {
        return super.keyPressed(e);
      }
    }
    clamp();
    return true;
  }

  public void onClose() {
    MusicClient.config.save(MusicClient.SETTINGS);
    minecraft.setScreen(parent);
  }

  public boolean isPauseScreen() {
    return false;
  }
}
