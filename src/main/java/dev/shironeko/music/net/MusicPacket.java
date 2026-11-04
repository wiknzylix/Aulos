package dev.shironeko.music.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record MusicPacket(String json) implements CustomPacketPayload {
  public static final Type<MusicPacket> TYPE =
      new Type<>(Identifier.fromNamespaceAndPath("aulos", "v4"));
  public static final StreamCodec<RegistryFriendlyByteBuf, MusicPacket> CODEC =
      CustomPacketPayload.codec(
          (p, b) -> b.writeUtf(p.json, 30000), b -> new MusicPacket(b.readUtf(30000)));

  public Type<MusicPacket> type() {
    return TYPE;
  }
}
