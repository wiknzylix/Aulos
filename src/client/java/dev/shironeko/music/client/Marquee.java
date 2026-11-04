package dev.shironeko.music.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Pauses at both ends; one shared clock, no per-row timers or allocations. */
public final class Marquee {
  public static void draw(
      GuiGraphics g, Font font, String text, int x, int y, int width, int colour) {
    if (width <= 0) return;
    int excess = font.width(text) - width;
    if (excess <= 0) {
      g.drawString(font, text, x, y, colour, false);
      return;
    }
    int cycle = font.width(text) + 32;
    double shift = (System.nanoTime() / 1_000_000_000.0 * 22) % cycle;
    g.enableScissor(x, y - 1, x + width, y + 11);
    g.drawString(font, text, x - (int) shift, y, colour, false);
    g.drawString(font, text, x - (int) shift + cycle, y, colour, false);
    g.disableScissor();
  }
}
