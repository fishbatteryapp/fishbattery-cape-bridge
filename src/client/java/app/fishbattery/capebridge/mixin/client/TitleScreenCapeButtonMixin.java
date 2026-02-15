package app.fishbattery.capebridge.mixin.client;

import app.fishbattery.capebridge.CapeMenuBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.gui.screens.TitleScreen")
abstract class TitleScreenCapeButtonMixin {
  @Inject(method = "init", at = @At("TAIL"), require = 0)
  private void fishbattery$addCapeButton(CallbackInfo ci) {
    CapeMenuBridge.tryAttachToScreen(this, false);
  }
}

