package dev.shironeko.music;

import java.nio.file.*;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Dedicated servers may name their owner UUID. LAN owner is inferred from the integrated server.
 */
public final class ServerConfig {
  public String hostUuid = "";

  public static ServerConfig load() {
    Path path = FabricLoader.getInstance().getConfigDir().resolve("aulos-server.json");
    try {
      if (Files.exists(path))
        return MusicTogether.JSON.fromJson(Files.readString(path), ServerConfig.class);
      ServerConfig c = new ServerConfig();
      Files.createDirectories(path.getParent());
      Files.writeString(path, MusicTogether.JSON.toJson(c));
      return c;
    } catch (Exception e) {
      return new ServerConfig();
    }
  }

  public UUID host() {
    try {
      return UUID.fromString(hostUuid);
    } catch (Exception e) {
      return new UUID(0, 0);
    }
  }
}
