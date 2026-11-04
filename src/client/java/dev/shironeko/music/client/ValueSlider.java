package dev.shironeko.music.client;

import java.util.function.DoubleConsumer;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

public final class ValueSlider extends AbstractSliderButton {
  private final String label;
  private final double min, max;
  private final DoubleConsumer consumer;

  public ValueSlider(
      int x,
      int y,
      int w,
      String label,
      double initial,
      double min,
      double max,
      DoubleConsumer consumer) {
    super(x, y, w, 20, Component.empty(), (initial - min) / (max - min));
    this.label = label;
    this.min = min;
    this.max = max;
    this.consumer = consumer;
    updateMessage();
  }

  protected void updateMessage() {
    setMessage(
        Component.literal(label + " " + Math.round((min + (max - min) * value) * 100) + "%"));
  }

  protected void applyValue() {
    consumer.accept(min + (max - min) * value);
  }

  @Override
  public void renderWidget(net.minecraft.client.gui.GuiGraphics g, int mx, int my, float dt) {
    super.renderWidget(g, mx, my, dt);
    // Preserve native sprites and input; restore the full upper bevel on the track and handle.
    int handle = getX() + (int) (value * (getWidth() - 8));
    int colour = active ? 0xFFC0C0C0 : 0xFF777777;
    g.fill(getX() + 1, getY(), getX() + getWidth() - 1, getY() + 1, 0xFF555555);
    g.fill(handle + 1, getY(), handle + 7, getY() + 1, colour);
  }
}
