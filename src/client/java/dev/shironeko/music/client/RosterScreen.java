package dev.shironeko.music.client;

import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class RosterScreen extends Screen {
  private final Screen parent;
  private boolean showHidden = false;
  private long requestAt;
  private int scroll;
  private String rendered = "";

  public RosterScreen(Screen parent) {
    super(Component.literal("Aulos 一起听名单"));
    this.parent = parent;
  }

  protected void init() {
    clearWidgets();
    MusicClient.refreshRoster();
    int width = Math.min(320, this.width - 28), x = (this.width - width) / 2, y = 47;
    int rows = Math.max(1, (height - 107) / 24);
    var hidden = MusicClient.config.hiddenPlayers;
    List<Map.Entry<String, String>> list =
        MusicClient.roster.entrySet().stream()
            .filter(e -> showHidden || !hidden.contains(e.getKey()))
            .toList();
    scroll = Math.clamp(scroll, 0, Math.max(0, list.size() - rows));
    for (int i = scroll; i < Math.min(list.size(), scroll + rows); i++) {
      var e = list.get(i);
      addRenderableWidget(
          new FlatButton(
              x,
              y,
              width,
              (hidden.contains(e.getKey()) ? "□ " : "☑ ") + e.getValue(),
              () -> {
                if (!hidden.add(e.getKey())) hidden.remove(e.getKey());
                init();
              }));
      y += 24;
    }
    int w = (width - 10) / 3;
    addRenderableWidget(
        new FlatButton(
            x,
            height - 54,
            w,
            showHidden ? "隐藏已屏蔽项" : "显示隐藏成员",
            () -> {
              showHidden = !showHidden;
              init();
            }));
    addRenderableWidget(
        new FlatButton(
            x + w + 5,
            height - 54,
            w,
            "全部显示",
            () -> {
              hidden.clear();
              init();
            }));
    addRenderableWidget(new FlatButton(x + 2 * (w + 5), height - 54, w, "返回", this::onClose));
    rendered = MusicClient.roster.toString();
  }

  public void tick() {
    if (MusicClient.now() - requestAt > 2000) {
      requestAt = MusicClient.now();
      MusicClient.refreshRoster();
    }
    if (!rendered.equals(MusicClient.roster.toString())) init();
  }

  public boolean mouseScrolled(double x, double y, double h, double v) {
    scroll = Math.max(0, scroll + (v < 0 ? 1 : -1));
    init();
    return true;
  }

  public void render(GuiGraphics g, int x, int y, float dt) {
    g.fill(0, 0, width, height, 0xF0111415);
    g.drawCenteredString(font, title, width / 2, 13, 0xFFE6E9E7);
    g.drawCenteredString(
        font, "正在一起听：" + MusicClient.roster.size() + " 人 点击名字切换本机显示", width / 2, 30, 0xFFB7C6BA);
    super.render(g, x, y, dt);
    g.drawCenteredString(font, "隐藏仅影响本机名单，不影响播放或成员资格", width / 2, height - 22, 0xFF9BA69F);
  }

  public void onClose() {
    MusicClient.config.save(MusicClient.SETTINGS);
    minecraft.setScreen(parent);
  }

  public boolean isPauseScreen() {
    return false;
  }
}
