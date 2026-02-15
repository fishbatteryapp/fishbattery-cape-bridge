package app.fishbattery.capebridge.mixin;

import app.fishbattery.capebridge.LauncherCapeRuntime;
import java.lang.reflect.Field;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.renderer.entity.player.AvatarRenderer")
abstract class AvatarRendererCapeMixin {
  @Inject(method = "extractRenderState", at = @At("RETURN"), require = 0)
  private void fishbattery$patchAvatarRenderState(Object avatarLike, Object renderStateLike, float tickDelta, CallbackInfo ci) {
    if (renderStateLike == null) return;
    final Object capeTexture = LauncherCapeRuntime.tryGetCapeTextureHandleForLocalPlayerEntity(avatarLike);
    if (capeTexture == null) return;

    final Object currentSkin = readField(renderStateLike, "skin");
    if (currentSkin != null) {
      final Object replacedSkin = LauncherCapeRuntime.tryReplaceCapeOnSkin(currentSkin, capeTexture);
      if (replacedSkin != null) writeField(renderStateLike, "skin", replacedSkin);
    }

    final String selectedCapeId = LauncherCapeRuntime.getSelectedCapeId();
    if (!selectedCapeId.isEmpty()) {
      writeField(renderStateLike, "showCape", Boolean.TRUE);
    }
  }

  private static Object readField(Object target, String fieldName) {
    Class<?> cls = target.getClass();
    while (cls != null) {
      try {
        final Field field = cls.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
      } catch (Exception ignored) {}
      cls = cls.getSuperclass();
    }
    return null;
  }

  private static void writeField(Object target, String fieldName, Object value) {
    Class<?> cls = target.getClass();
    while (cls != null) {
      try {
        final Field field = cls.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
        return;
      } catch (Exception ignored) {}
      cls = cls.getSuperclass();
    }
  }
}
