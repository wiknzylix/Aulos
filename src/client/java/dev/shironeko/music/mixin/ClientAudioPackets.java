package dev.shironeko.music.mixin;

import dev.shironeko.music.client.RelayClient;
import dev.shironeko.music.net.RelayPacket;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Keep encoded-byte processing ahead of Fabric's main-thread payload scheduling.
@Mixin(value = ClientCommonPacketListenerImpl.class, priority = 998)
public abstract class ClientAudioPackets {
  @Inject(
      method =
          "handleCustomPayload(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V",
      at = @At("HEAD"),
      cancellable = true)
  private void aulos$audio(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
    if (packet.payload() instanceof RelayPacket audio) {
      RelayClient.receive(this, audio.frame());
      ci.cancel();
    }
  }
}
