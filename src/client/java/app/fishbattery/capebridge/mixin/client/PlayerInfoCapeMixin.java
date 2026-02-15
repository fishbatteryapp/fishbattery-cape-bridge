package app.fishbattery.capebridge.mixin.client;

import app.fishbattery.capebridge.LauncherCapeRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.multiplayer.PlayerInfo")
abstract class PlayerInfoCapeMixin {
  @Inject(method = "getCapeTexture", at = @At("HEAD"), cancellable = true, require = 0)
  private void fishbattery$replaceCapeTexture(CallbackInfoReturnable<Object> cir) {
    Object texture = LauncherCapeRuntime.tryGetCapeTextureForLocalPlayer(this);
    if (texture != null) cir.setReturnValue(texture);
  }
}
