package app.fishbattery.capebridge.mixin.client;

import app.fishbattery.capebridge.LauncherCapeRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.network.PlayerListEntry")
abstract class PlayerListEntrySkinMixin {
<<<<<<< HEAD
  private static boolean fishbattery$loggedCapeTextureHook = false;

  @Inject(method = "getCapeTexture", at = @At("HEAD"), cancellable = true, require = 0)
  private void fishbattery$replaceCapeTexture(CallbackInfoReturnable<Object> cir) {
    final Object cape = LauncherCapeRuntime.tryGetCapeTextureIdForLocalPlayer(this);
    if (cape != null) {
      if (!fishbattery$loggedCapeTextureHook) {
        fishbattery$loggedCapeTextureHook = true;
        System.err.println("[fishbattery_cape_bridge] getCapeTexture hook returned launcher texture id");
      }
      cir.setReturnValue(cape);
    }
=======
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
>>>>>>> origin/main
  }

  @Inject(method = "getSkinTextures", at = @At("RETURN"), cancellable = true, require = 0)
  private void fishbattery$replaceCapeOnGetSkinTextures(CallbackInfoReturnable<Object> cir) {
<<<<<<< HEAD
    final Object newCape = LauncherCapeRuntime.tryGetCapeTextureForLocalPlayer(this);
    if (newCape == null) return;

    final Object skin = cir.getReturnValue();
    if (skin == null) return;

    final Object replaced = LauncherCapeRuntime.tryReplaceCapeOnSkin(skin, newCape);
    if (replaced != null) cir.setReturnValue(replaced);
  }

  // Keep getSkin() too, for versions/mods that still use it
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
=======
    fishbattery$tryReplace(cir);
  }
}

>>>>>>> origin/main
