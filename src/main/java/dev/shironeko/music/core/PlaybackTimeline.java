package dev.shironeko.music.core;

public record PlaybackTimeline(String status, long positionMs, long epochMs) {
  public long position(long now) {
    if (status.equals("PAUSING")) return Math.max(0, positionMs - Math.max(0, epochMs - now));
    return Math.max(0, positionMs + (status.equals("PLAYING") ? Math.max(0, now - epochMs) : 0));
  }
}
