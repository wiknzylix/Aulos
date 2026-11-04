package dev.shironeko.music.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record RelayPacket(RelayFrame frame) implements CustomPacketPayload {
  public static final Type<RelayPacket> TYPE =
      new Type<>(Identifier.fromNamespaceAndPath("aulos", "audio_v2"));
  public static final StreamCodec<RegistryFriendlyByteBuf, RelayPacket> CODEC =
      CustomPacketPayload.codec(
          (p, b) -> {
            RelayFrame f = p.frame;
            b.writeByte(f.kind());
            b.writeUUID(f.session());
            b.writeUUID(f.entry());
            b.writeUUID(f.request());
            b.writeLong(f.offset());
            b.writeLong(f.total());
            b.writeBoolean(f.eof());
            b.writeByteArray(f.data());
            b.writeUtf(f.error(), 160);
            b.writeByte(f.quality());
          },
          b ->
              new RelayPacket(
                  new RelayFrame(
                      b.readUnsignedByte(),
                      b.readUUID(),
                      b.readUUID(),
                      b.readUUID(),
                      b.readLong(),
                      b.readLong(),
                      b.readBoolean(),
                      b.readByteArray(RelayFrame.CHUNK),
                      b.readUtf(160),
                      b.readUnsignedByte())));

  @Override
  public Type<RelayPacket> type() {
    return TYPE;
  }
}
