package dev.shironeko.music.client;

import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class ActionScreen extends Screen {
  public record Action(String text, Runnable run) {}

  private final Screen parent;
  private final List<Action> actions;
  private final SmoothScroll scroll = new SmoothScroll();
  private final List<FlatButton> buttons = new ArrayList<>();
  private int top, bottom;

  public ActionScreen(Screen parent, String title, List<Action> actions) {
    super(Component.literal(title));
    this.parent = parent;
    this.actions = actions;
  }

  protected void init() {
    clearWidgets();
    buttons.clear();
    int w = Math.min(320, width - 30);
    top = Math.max(35, height / 2 - actions.size() * 10);
    bottom = height - 14;
    scroll.limit(actions.size() * 21 - (bottom - top));
    for (int i = 0; i < actions.size(); i++) {
      var a = actions.get(i);
      FlatButton b =
          addRenderableWidget(
              a.text().startsWith("立即播放")
                  ? new ConfirmButton((width - w) / 2, top + i * 21, w, a.text(), a.run())
                  : new FlatButton((width - w) / 2, top + i * 21, w, a.text(), a.run()));
      b.setHeight(18);
      buttons.add(b);
    }
  }

  public void render(GuiGraphics g, int x, int y, float dt) {
    ConfirmButton.expire(children());
    scroll.animate();
    int textWidth = Math.min(width - 30, font.width(title));
    Marquee.draw(
        g, font, title.getString(), (width - textWidth) / 2, top - 20, textWidth, 0xFFFFFFFF);
    g.enableScissor(0, top, width, bottom);
    for (int i = 0; i < buttons.size(); i++)
      buttons.get(i).setY(top + i * 21 - (int) scroll.value());
    super.render(g, x, y, dt);
    g.disableScissor();
  }

  public boolean mouseScrolled(double x, double y, double h, double v) {
    scroll.move(-v * 30);
    return true;
  }

  public boolean mouseClicked(MouseButtonEvent e, boolean twice) {
    for (FlatButton b : buttons)
      if (b instanceof ConfirmButton c && !c.isMouseOver(e.x(), e.y())) c.cancel();
    return e.y() >= top && e.y() < bottom && super.mouseClicked(e, twice);
  }

  public void onClose() {
    boolean cancelled = false;
    for (FlatButton b : buttons) if (b instanceof ConfirmButton c) cancelled |= c.cancel();
    if (cancelled) return;
    minecraft.setScreen(parent);
  }

  public boolean isPauseScreen() {
    return false;
  }
}
