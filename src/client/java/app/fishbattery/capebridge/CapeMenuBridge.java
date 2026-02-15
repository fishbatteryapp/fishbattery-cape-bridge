package app.fishbattery.capebridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class CapeMenuBridge {
  private static final Set<Object> INJECTED_SCREENS = Collections.newSetFromMap(new WeakHashMap<>());

  private CapeMenuBridge() {}

  public static void tryAttachToScreen(Object screenLike, boolean pauseMenu) {
    if (!(screenLike instanceof Screen screen)) return;
    if (isAlreadyAdded(screen)) return;
    final Minecraft mc = Minecraft.getInstance();
    final int width = mc != null && mc.getWindow() != null
      ? mc.getWindow().getGuiScaledWidth()
      : readIntField(screen, "width", 0);
    final int height = mc != null && mc.getWindow() != null
      ? mc.getWindow().getGuiScaledHeight()
      : readIntField(screen, "height", 0);
    if (width <= 0 || height <= 0) return;

    final int btnW = 98;
    final int btnH = 20;
    final int x = pauseMenu ? (width / 2) - (btnW / 2) : (width / 2) - 100;
    final int y = pauseMenu ? Math.min(height - 28, (height / 4) + 120) : (height / 4) + 120;

    final Object button = createButton(literal("Capes"), x, y, btnW, btnH, (btn) -> openCapeMenu(screen));
    if (button == null) return;
    if (!addWidget(screen, button)) return;
    setAlreadyAdded(screen, true);
  }

  private static void openCapeMenu(Screen parent) {
    final Minecraft mc = Minecraft.getInstance();
    if (mc == null) return;
    mc.setScreen(new CapeSelectorScreen(parent));
  }

  private static boolean addWidget(Screen screen, Object widget) {
    final List<Method> methods = getHierarchyMethods(screen.getClass());
    Method preferred = null;
    for (Method m : methods) {
      if (m.getParameterCount() != 1) continue;
      final Class<?> p = m.getParameterTypes()[0];
      if (!p.isAssignableFrom(widget.getClass())) continue;
      final Class<?> r = m.getReturnType();
      if (r == void.class) continue;
      if (r.isAssignableFrom(widget.getClass()) || widget.getClass().isAssignableFrom(r)) {
        preferred = m;
        break;
      }
    }
    if (preferred != null) {
      try {
        preferred.setAccessible(true);
        preferred.invoke(screen, widget);
        return true;
      } catch (Exception ignored) {}
    }

    for (Method m : methods) {
      if (m.getParameterCount() != 1) continue;
      final Class<?> p = m.getParameterTypes()[0];
      if (!p.isAssignableFrom(widget.getClass())) continue;
      try {
        m.setAccessible(true);
        m.invoke(screen, widget);
        return true;
      } catch (Exception ignored) {}
    }
    return false;
  }

  private static Object createButton(Component label, int x, int y, int width, int height, Button.OnPress onPress) {
    if (label == null || onPress == null) return null;
    try {
      for (Method m : Button.class.getMethods()) {
        if ((m.getModifiers() & java.lang.reflect.Modifier.STATIC) == 0) continue;
        if (m.getParameterCount() != 2) continue;
        final Class<?>[] p = m.getParameterTypes();
        if (!p[0].isAssignableFrom(label.getClass())) continue;
        if (!p[1].isAssignableFrom(onPress.getClass())) continue;
        final Object builder = m.invoke(null, label, onPress);
        if (builder == null) continue;
        if (!applyBuilderBounds(builder, x, y, width, height)) continue;
        final Method build = builder.getClass().getMethod("build");
        return build.invoke(builder);
      }
    } catch (Exception ignored) {}

    try {
      for (java.lang.reflect.Constructor<?> c : Button.class.getDeclaredConstructors()) {
        final Class<?>[] p = c.getParameterTypes();
        if (p.length < 6) continue;
        if (p[0] != int.class || p[1] != int.class || p[2] != int.class || p[3] != int.class) continue;
        if (!p[4].isAssignableFrom(label.getClass())) continue;
        if (!p[5].isAssignableFrom(onPress.getClass())) continue;
        final Object[] args = new Object[p.length];
        args[0] = Integer.valueOf(x);
        args[1] = Integer.valueOf(y);
        args[2] = Integer.valueOf(width);
        args[3] = Integer.valueOf(height);
        args[4] = label;
        args[5] = onPress;
        for (int i = 6; i < p.length; i++) args[i] = null;
        c.setAccessible(true);
        return c.newInstance(args);
      }
    } catch (Exception ignored) {}
    return null;
  }

  private static Component literal(String text) {
    try {
      final Method m = Component.class.getMethod("literal", String.class);
      final Object out = m.invoke(null, text);
      if (out instanceof Component) return (Component) out;
    } catch (Exception ignored) {}
    try {
      final Class<?> textComponent = Class.forName("net.minecraft.network.chat.TextComponent");
      final java.lang.reflect.Constructor<?> c = textComponent.getDeclaredConstructor(String.class);
      c.setAccessible(true);
      final Object out = c.newInstance(text);
      if (out instanceof Component) return (Component) out;
    } catch (Exception ignored) {}
    try {
      final Method m = Component.class.getMethod("empty");
      final Object out = m.invoke(null);
      if (out instanceof Component) return (Component) out;
    } catch (Exception ignored) {}
    return null;
  }

  private static boolean applyBuilderBounds(Object builder, int x, int y, int width, int height) {
    for (Method method : builder.getClass().getMethods()) {
      if (method.getParameterCount() != 4) continue;
      final Class<?>[] p = method.getParameterTypes();
      if (p[0] != int.class || p[1] != int.class || p[2] != int.class || p[3] != int.class) continue;
      try {
        final Object out = method.invoke(builder, Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(width), Integer.valueOf(height));
        if (out == null || out == builder || builder.getClass().isAssignableFrom(out.getClass())) return true;
      } catch (Exception ignored) {}
    }
    return false;
  }

  private static int readIntField(Object target, String field, int fallback) {
    try {
      final Field f = target.getClass().getSuperclass().getDeclaredField(field);
      f.setAccessible(true);
      final Object value = f.get(target);
      if (value instanceof Number) return ((Number) value).intValue();
    } catch (Exception ignored) {}
    return fallback;
  }

  private static List<Method> getHierarchyMethods(Class<?> type) {
    final List<Method> out = new ArrayList<>();
    Class<?> cursor = type;
    while (cursor != null && cursor != Object.class) {
      try {
        Collections.addAll(out, cursor.getDeclaredMethods());
      } catch (Throwable ignored) {}
      cursor = cursor.getSuperclass();
    }
    return out;
  }

  private static boolean isAlreadyAdded(Object target) {
    return INJECTED_SCREENS.contains(target);
  }

  private static void setAlreadyAdded(Object target, boolean value) {
    if (value) INJECTED_SCREENS.add(target);
    else INJECTED_SCREENS.remove(target);
  }
}
