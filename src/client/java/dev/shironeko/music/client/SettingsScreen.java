package dev.shironeko.music.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

public final class SettingsScreen extends Screen {
  private final Screen parent;
  private boolean recording;
  private FlatButton key;

  public SettingsScreen(Screen parent) {
    super(Component.literal("Aulos " + dev.shironeko.music.MusicTogether.VERSION));
    this.parent = parent;
  }

  protected void init() {
    clearWidgets();
    int w = Math.min(320, width - 30), x = (width - w) / 2, y = height / 2 - 45;
    addRenderableWidget(
        new FlatButton(x, y, w, "账号管理", () -> minecraft.setScreen(new AccountScreen(this))));
    addRenderableWidget(
        new FlatButton(x, y + 24, w, "HUD 布局与颜色", () -> minecraft.setScreen(new HudEditor(this))));
    key =
        addRenderableWidget(
            new FlatButton(
                x,
                y + 48,
                w,
                label(),
                () -> {
                  recording = true;
                  key.setMessage(Component.literal(label()));
                }));
    addRenderableWidget(new FlatButton(x, y + 77, w, "返回", this::onClose));
  }

  private String label() {
    return recording ? "请按打开快捷键（Esc 取消）" : "打开快捷键：" + MusicClient.keyName();
  }

  public boolean keyPressed(KeyEvent e) {
    if (recording) {
      if (e.key() != GLFW.GLFW_KEY_ESCAPE) {
        if (e.key() >= GLFW.GLFW_KEY_LEFT_SHIFT && e.key() <= GLFW.GLFW_KEY_RIGHT_SUPER)
          return true;
        MusicClient.config.key = e.key();
        MusicClient.config.modifiers = e.modifiers() & 7;
        MusicClient.config.save(MusicClient.SETTINGS);
        for (var binding : minecraft.options.keyMappings)
          if (binding.matches(e)) MusicClient.message("按键冲突：" + binding.getName());
      }
      recording = false;
      key.setMessage(Component.literal(label()));
      return true;
    }
    return super.keyPressed(e);
  }

  public void onClose() {
    if (recording) {
      recording = false;
      key.setMessage(Component.literal(label()));
    } else minecraft.setScreen(parent);
  }

  public void render(GuiGraphics g, int x, int y, float dt) {
    g.drawCenteredString(font, title, width / 2, 15, 0xFFFFFFFF);
    super.render(g, x, y, dt);
  }

  public boolean isPauseScreen() {
    return false;
  }
}
