package dev.shironeko.music.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

public class FlatButton extends Button.Plain {
  public FlatButton(int x, int y, int width, String text, Runnable action) {
    super(x, y, width, 20, Component.literal(text), b -> action.run(), DEFAULT_NARRATION);
  }
}
