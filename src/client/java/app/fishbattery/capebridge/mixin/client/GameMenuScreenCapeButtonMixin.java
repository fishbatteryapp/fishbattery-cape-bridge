package app.fishbattery.capebridge.mixin.client;

import app.fishbattery.capebridge.CapeMenuBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Inject cape-selector entry button into in-game pause-style game menu screen.
@Mixin(targets = "net.minecraft.client.gui.screens.GameMenuScreen")
abstract class GameMenuScreenCapeButtonMixin {
  @Inject(method = "init", at = @At("TAIL"), require = 0)
  private void fishbattery$addCapeButton(CallbackInfo ci) {
    // TAIL ensures vanilla widgets are present before bridge positions/attaches button.
    CapeMenuBridge.tryAttachToScreen(this, true);
  }
}
