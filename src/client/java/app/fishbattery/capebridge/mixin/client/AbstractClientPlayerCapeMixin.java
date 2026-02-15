package app.fishbattery.capebridge.mixin.client;

import app.fishbattery.capebridge.LauncherCapeRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.player.AbstractClientPlayer")
abstract class AbstractClientPlayerCapeMixin {
  @Inject(
    method = {
      "getCloakTextureLocation",
      "getCapeTextureLocation",
      "getLocationCape"
    },
    at = @At("HEAD"),
    cancellable = true,
    require = 0
  )
  private void fishbattery$overrideCapeTexture(CallbackInfoReturnable<Object> cir) {
    final Object texture = LauncherCapeRuntime.tryGetCapeTextureForLocalPlayerEntity(this);
    if (texture != null) cir.setReturnValue(texture);
  }
}

