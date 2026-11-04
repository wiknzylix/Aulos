package dev.shironeko.music.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class VolumeScreen extends Screen {
  private final Screen parent;

  public VolumeScreen(Screen parent) {
    super(Component.literal("本机音量"));
    this.parent = parent;
  }

  protected void init() {
    int w = Math.min(260, width - 30), x = (width - w) / 2, y = height / 2 - 25;
    addRenderableWidget(
        new ValueSlider(
            x, y, w, "音量", MusicClient.config.volume, 0, 1, v -> MusicClient.config.volume = v));
    addRenderableWidget(
        new FlatButton(
            x,
            y + 26,
            w,
            MusicClient.config.muted ? "取消静音" : "静音",
            () -> {
              MusicClient.config.muted = !MusicClient.config.muted;
              MusicClient.config.save(MusicClient.SETTINGS);
              rebuildWidgets();
            }));
    addRenderableWidget(new FlatButton(x, y + 52, w, "返回", this::onClose));
  }

  public void render(GuiGraphics g, int x, int y, float dt) {

    g.drawCenteredString(font, title, width / 2, height / 2 - 52, 0xFFE6E9E7);
    super.render(g, x, y, dt);
  }

  public void onClose() {
    MusicClient.config.save(MusicClient.SETTINGS);
    minecraft.setScreen(parent);
  }

  public boolean isPauseScreen() {
    return false;
  }
}
