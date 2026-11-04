package dev.shironeko.music.client;

import net.minecraft.network.chat.Component;

/** Confirmation stays in the original native button. Esc or another click cancels it. */
public final class ConfirmButton extends FlatButton {
  private final String label;
  private final Runnable action;
  private long armedUntil;

  public ConfirmButton(int x, int y, int w, String label, Runnable action) {
    super(x, y, w, label, () -> {});
    this.label = label;
    this.action = action;
  }

  public boolean cancel() {
    boolean armed = armedUntil > 0;
    armedUntil = 0;
    setMessage(Component.literal(label));
    return armed;
  }

  @Override
  public void onPress(net.minecraft.client.input.InputWithModifiers input) {
    if (armedUntil > MusicClient.now()) {
      cancel();
      action.run();
    } else {
      armedUntil = MusicClient.now() + 5000;
      setMessage(Component.literal("确认" + label.replace("…", "")));
    }
  }

  public static void expire(Iterable<?> widgets) {
    for (Object w : widgets)
      if (w instanceof ConfirmButton b && b.armedUntil > 0 && MusicClient.now() > b.armedUntil)
        b.cancel();
  }
}
