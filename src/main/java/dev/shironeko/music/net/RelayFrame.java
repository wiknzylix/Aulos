package dev.shironeko.music.net;

import java.util.*;

/** Bounded requests for original encoded bytes. Never contains a URL or account credential. */
public record RelayFrame(
    int kind,
    UUID session,
    UUID entry,
    UUID request,
    long offset,
    long total,
    boolean eof,
    byte[] data,
    String error,
    int quality) {
  public RelayFrame(
      int kind,
      UUID session,
      UUID entry,
      UUID request,
      long offset,
      long total,
      boolean eof,
      byte[] data,
      String error) {
    this(kind, session, entry, request, offset, total, eof, data, error, 0);
  }

  public static final int REQUEST = 0, DATA = 1, ERROR = 2, CANCEL = 3;
  public static final int CHUNK = 24 * 1024, WINDOW = 8;
  public static final long MAX_FILE = 256L * 1024 * 1024;

  public RelayFrame {
    Objects.requireNonNull(session);
    Objects.requireNonNull(entry);
    Objects.requireNonNull(request);
    Objects.requireNonNull(data);
    Objects.requireNonNull(error);
    if (quality < 0
        || quality > 1
        || kind < REQUEST
        || kind > CANCEL
        || offset < 0
        || offset >= MAX_FILE
        || total < -1
        || total > MAX_FILE
        || data.length > CHUNK
        || error.length() > 160
        || kind != DATA && data.length != 0
        || kind == DATA && !eof && data.length != CHUNK
        || offset + data.length > MAX_FILE
        || kind == DATA && eof && total != offset + data.length)
      throw new IllegalArgumentException("无效音频分片");
  }

  public static RelayFrame request(UUID session, UUID entry, long offset) {
    return request(session, entry, offset, 0);
  }

  public static RelayFrame request(UUID session, UUID entry, long offset, int quality) {
    return new RelayFrame(
        REQUEST, session, entry, UUID.randomUUID(), offset, -1, false, new byte[0], "", quality);
  }

  public RelayFrame failure(String reason) {
    return new RelayFrame(
        ERROR, session, entry, request, offset, -1, false, new byte[0], reason, quality);
  }

  public boolean matches(RelayFrame other) {
    return session.equals(other.session)
        && entry.equals(other.entry)
        && request.equals(other.request)
        && offset == other.offset
        && quality == other.quality;
  }
}
