package app.fishbattery.capebridge.mixin.client;

import app.fishbattery.capebridge.LauncherCapeRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.multiplayer.PlayerInfo")
abstract class PlayerInfoSkinMixin {
  private void fishbattery$tryReplace(CallbackInfoReturnable<Object> cir) {
    final Object capeTexture = LauncherCapeRuntime.tryGetCapeTextureForLocalPlayer(this);
    if (capeTexture == null) return;
    final Object currentSkin = cir.getReturnValue();
    if (currentSkin == null) return;
    final Object replacedSkin = LauncherCapeRuntime.tryReplaceCapeOnSkin(currentSkin, capeTexture);
    if (replacedSkin != null) cir.setReturnValue(replacedSkin);
  }

  @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true, require = 0)
  private void fishbattery$replaceCapeOnGetSkin(CallbackInfoReturnable<Object> cir) {
    fishbattery$tryReplace(cir);
  }

}
