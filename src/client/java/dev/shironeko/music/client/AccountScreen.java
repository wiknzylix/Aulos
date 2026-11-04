package dev.shironeko.music.client;

import dev.shironeko.music.provider.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Account operations for all platforms live here, independently of the selected music library. */
public final class AccountScreen extends Screen {
  private final Screen parent;
  private MusicProvider selected;
  private boolean busy;
  private String status = "";

  public AccountScreen(Screen parent) {
    super(Component.literal("Aulos 账号管理"));
    this.parent = parent;
  }

  protected void init() {
    clearWidgets();
    int w = Math.min(300, width - 30), x = (width - w) / 2, y = height / 2 - 52;
    if (selected == null) {
      for (MusicProvider p : MusicClient.providers.values()) {
        addRenderableWidget(
            new FlatButton(
                x,
                y,
                w,
                p.name() + " " + p.account(),
                () -> {
                  selected = p;
                  status = "";
                  init();
                }));
        y += 24;
      }
      addRenderableWidget(new FlatButton(x, y + 5, w, "返回", this::onClose));
      return;
    }
    MusicProvider p = selected;
    var login =
        addRenderableWidget(
            new FlatButton(x, y, w, "扫码登录", () -> minecraft.setScreen(new QrScreen(this, p))));
    login.active = !busy;
    y += 24;
    var refresh =
        addRenderableWidget(new FlatButton(x, y, w, busy ? "正在刷新…" : "刷新账号状态", () -> refresh(p)));
    refresh.active = !busy;
    y += 24;
    var logout = addRenderableWidget(new ConfirmButton(x, y, w, "退出登录", () -> logout(p)));
    logout.active = !busy;
    y += 24;
    addRenderableWidget(
        new FlatButton(
            x,
            y + 3,
            w,
            "返回账号列表",
            () -> {
              selected = null;
              status = "";
              init();
            }));
  }

  private void refresh(MusicProvider p) {
    busy = true;
    status = "正在向平台验证登录状态…";
    init();
    boolean previous = p.loggedIn();
    MusicClient.async(
        () -> {
          try {
            return p.refreshAccount();
          } finally {
            minecraft.execute(
                () -> {
                  busy = false;
                  if (minecraft.screen == this) init();
                });
          }
        },
        result -> {
          status = result.message();
          if (previous != result.loggedIn()) MusicClient.accountChanged(p);
          init();
        });
  }

  private void logout(MusicProvider p) {
    busy = true;
    status = "正在退出登录…";
    minecraft.setScreen(this);
    MusicClient.async(
        () -> {
          try {
            p.logout();
            return true;
          } finally {
            minecraft.execute(
                () -> {
                  busy = false;
                  if (minecraft.screen == this) init();
                });
          }
        },
        result -> {
          MusicClient.accountChanged(p);
          status = "已退出登录，本机凭证已删除";
          init();
        });
  }

  public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent e, boolean twice) {
    for (var child : children())
      if (child instanceof ConfirmButton b && !b.isMouseOver(e.x(), e.y())) b.cancel();
    return super.mouseClicked(e, twice);
  }

  public void render(GuiGraphics g, int mx, int my, float dt) {
    ConfirmButton.expire(children());
    g.drawCenteredString(font, title, width / 2, 12, 0xFFFFFFFF);
    if (selected != null) {
      Marquee.draw(
          g,
          font,
          selected.name() + " " + selected.account(),
          15,
          30,
          width - 30,
          selected.loggedIn() ? 0xFF55FF55 : 0xFFAAAAAA);
      if (!status.isEmpty()) Marquee.draw(g, font, status, 15, 44, width - 30, 0xFFAAAAAA);
    }
    if (!MusicClient.feedback.isEmpty())
      Marquee.draw(
          g, font, MusicClient.feedback, 15, height - 13, width - 30, MusicClient.feedbackColour);
    super.render(g, mx, my, dt);
  }

  public void onClose() {
    boolean cancelled = false;
    for (var child : children()) if (child instanceof ConfirmButton b) cancelled |= b.cancel();
    if (cancelled) return;
    if (selected != null) {
      selected = null;
      status = "";
      init();
    } else minecraft.setScreen(parent);
  }

  public boolean isPauseScreen() {
    return false;
  }
}
