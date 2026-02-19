package app.fishbattery.capebridge.mixin.client;

import app.fishbattery.capebridge.LauncherCapeRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Local-player cape overrides for AbstractClientPlayerEntity paths.
// We hook both legacy cape getters and modern skin-composite getters so launcher
// capes continue to work across mapping/version differences.
@Mixin(targets = "net.minecraft.client.network.AbstractClientPlayerEntity")
abstract class AbstractClientPlayerEntityCapeMixin {

  @Inject(method = "getCapeTexture", at = @At("HEAD"), cancellable = true, require = 0)
  private void fishbattery$replaceCapeTexture(CallbackInfoReturnable<Object> cir) {
    // Legacy path: directly replace Identifier/ResourceLocation cape texture.
    final Object cape = LauncherCapeRuntime.tryGetCapeTextureIdForLocalPlayer(this);
    if (cape != null) cir.setReturnValue(cape);
  }

  @Inject(method = "getSkinTextures", at = @At("RETURN"), cancellable = true, require = 0)
  private void fishbattery$replaceCapeOnGetSkinTextures(CallbackInfoReturnable<Object> cir) {
    // Modern path: rewrite cape field on composite skin payload.
    final Object newCape = LauncherCapeRuntime.tryGetCapeTextureForLocalPlayer(this);
    if (newCape == null) return;

    final Object skin = cir.getReturnValue();
    if (skin == null) return;

    final Object replaced = LauncherCapeRuntime.tryReplaceCapeOnSkin(skin, newCape);
    if (replaced != null) cir.setReturnValue(replaced);
  }
}
