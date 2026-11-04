package dev.shironeko.music.mixin;

import dev.shironeko.music.net.*;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// This HEAD hook must run before Fabric's networking hook (999); verified on the real wire.
@Mixin(value = ServerGamePacketListenerImpl.class, priority = 998)
public abstract class ServerAudioPackets {
  @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
  private void aulos$audio(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
    if (packet.payload() instanceof RelayPacket audio) {
      ServerRelay.receive((ServerGamePacketListenerImpl) (Object) this, audio.frame());
      ci.cancel();
    }
  }
}
