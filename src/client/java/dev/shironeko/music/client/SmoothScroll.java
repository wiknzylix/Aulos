package dev.shironeko.music.client;

/** Pixel scroll with a time-based critically damped approach; independent of render frame rate. */
public final class SmoothScroll {
  private double value, target, max;
  private long at;

  public void limit(double maximum) {
    max = Math.max(0, maximum);
    target = Math.clamp(target, 0, max);
    value = Math.clamp(value, 0, max);
  }

  public double value() {
    return value;
  }

  public double target() {
    return target;
  }

  public void jump(double next) {
    target = value = Math.clamp(next, 0, max);
    at = System.nanoTime();
  }

  public void move(double delta) {
    target = Math.clamp(target + delta, 0, max);
  }

  public void animate() {
    long now = System.nanoTime();
    double dt = at == 0 ? 0 : Math.min(.1, (now - at) / 1e9);
    at = now;
    value += (target - value) * (1 - Math.exp(-18 * dt));
    if (Math.abs(target - value) < .05) value = target;
  }

  public void reset() {
    value = target = 0;
    at = System.nanoTime();
  }
}
