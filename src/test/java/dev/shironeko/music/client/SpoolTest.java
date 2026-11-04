package dev.shironeko.music.client;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpoolTest {
  @TempDir Path folder;

  @Test
  void growingEndWaitsForNetworkRatherThanReportingEarlyEof() throws Exception {
    try (var spool = new AudioSpool(folder);
        var worker = Executors.newSingleThreadExecutor();
        var out = Files.newOutputStream(spool.path)) {
      var read = worker.submit(() -> spool.read());
      assertThrows(TimeoutException.class, () -> read.get(40, TimeUnit.MILLISECONDS));
      out.write(17);
      out.flush();
      spool.written(1);
      assertEquals(17, read.get(1, TimeUnit.SECONDS));
      spool.finish(null);
      assertEquals(-1, spool.read());
    }
  }
}
