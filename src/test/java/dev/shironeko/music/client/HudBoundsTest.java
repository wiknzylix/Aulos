package dev.shironeko.music.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HudBoundsTest {
  @Test
  void fractionalScaledBottomBorderStaysInsideScreen() {
    for (int[] screen : new int[][] {{320, 180}, {427, 240}, {640, 360}, {1920, 1080}})
      for (double scale : new double[] {.4, .65, .85, 1, 1.15, 2.75}) {
        ClientConfig config = new ClientConfig();
        var l = config.card;
        l.width = 280;
        l.scale = scale;
        l.x = 1;
        l.y = 1;
        int[] b = MusicHud.bounds(l, screen[0], screen[1], false);
        assertTrue(b[0] >= 1 && b[1] >= 1);
        assertTrue(b[0] + b[2] <= screen[0] - 1);
        assertTrue(b[1] + b[3] <= screen[1] - 1);
        double effective =
            Math.min(scale, Math.min((screen[0] - 6) / l.width, (screen[1] - 6) / 83.0));
        assertTrue(b[1] + 83 * effective <= screen[1] - 1 + .00001);
      }
  }

  @Test
  void draggingCoordinatesRoundTripWithoutBorderDrift() {
    ClientConfig config = new ClientConfig();
    var l = config.card;
    l.scale = .65;
    l.x = .3;
    l.y = 1;
    int[] before = MusicHud.bounds(l, 427, 240, false);
    for (int i = 0; i < 20; i++) {
      int[] b = MusicHud.bounds(l, 427, 240, false);
      MusicHud.position(l, b[0], b[1], 427, 240, false);
    }
    assertArrayEquals(before, MusicHud.bounds(l, 427, 240, false));
  }
}
