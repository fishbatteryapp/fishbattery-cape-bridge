package app.fishbattery.capebridge.mixin.client;

import app.fishbattery.capebridge.LauncherCapeRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.entity.ClientAvatarEntity")
abstract class ClientAvatarEntitySkinMixin {
  @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true, require = 0)
  private void fishbattery$replaceCapeOnGetSkin(CallbackInfoReturnable<Object> cir) {
    final Object newCape = LauncherCapeRuntime.tryGetCapeTextureForLocalPlayer(this);
    if (newCape == null) return;

    final Object skin = cir.getReturnValue();
    if (skin == null) return;

    final Object replaced = LauncherCapeRuntime.tryReplaceCapeOnSkin(skin, newCape);
    if (replaced != null) cir.setReturnValue(replaced);
  }
}
