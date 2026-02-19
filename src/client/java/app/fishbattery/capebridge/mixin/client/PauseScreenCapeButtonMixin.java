package app.fishbattery.capebridge.mixin.client;

import app.fishbattery.capebridge.CapeMenuBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Inject cape-selector entry button into PauseScreen variants used by some mappings.
@Mixin(targets = "net.minecraft.client.gui.screens.PauseScreen")
abstract class PauseScreenCapeButtonMixin {
  @Inject(method = "init", at = @At("TAIL"), require = 0)
  private void fishbattery$addCapeButton(CallbackInfo ci) {
    // Attach after screen init so button placement can account for existing controls.
    CapeMenuBridge.tryAttachToScreen(this, true);
  }
}
