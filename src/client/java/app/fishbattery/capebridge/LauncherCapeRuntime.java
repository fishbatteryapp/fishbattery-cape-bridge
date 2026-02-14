package app.fishbattery.capebridge;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class LauncherCapeRuntime {
  private static final String CAPE_PATH_PROPERTY = "fishbattery.launcherCape.path";

  private static String cachedPath = "";
  private static long cachedMtime = -1L;
  private static Object cachedTextureId = null;

  private LauncherCapeRuntime() {}

  public static Object tryGetCapeTextureForLocalPlayer(Object playerInfoLike) {
    try {
      if (!isLocalPlayerProfile(playerInfoLike)) return null;

      final String rawPath = System.getProperty(CAPE_PATH_PROPERTY, "").trim();
      if (rawPath.isEmpty()) return null;

      final Path path = Path.of(rawPath);
      if (!Files.isRegularFile(path)) return null;

      final long mtime = Files.getLastModifiedTime(path).toMillis();
      if (rawPath.equals(cachedPath) && mtime == cachedMtime && cachedTextureId != null) {
        return cachedTextureId;
      }

      final Object mc = getMinecraftInstance();
      if (mc == null) return null;

      final Object textureManager = invokeNoArg(mc, "getTextureManager", "getTextureManager");
      if (textureManager == null) return null;

      final Object nativeImage = readNativeImage(path);
      if (nativeImage == null) return null;

      final Object dynamicTexture = newDynamicTexture(nativeImage);
      if (dynamicTexture == null) return null;

      final Object textureId = newIdentifier("fishbattery", "launcher_cape_dynamic");
      if (textureId == null) return null;

      if (!registerTexture(textureManager, textureId, dynamicTexture)) return null;

      cachedPath = rawPath;
      cachedMtime = mtime;
      cachedTextureId = textureId;
      return textureId;
    } catch (Throwable t) {
      System.err.println("[fishbattery_cape_bridge] Failed to load launcher cape: " + t.getMessage());
      return null;
    }
  }

  private static boolean isLocalPlayerProfile(Object playerInfoLike) {
    final Object mc = getMinecraftInstance();
    if (mc == null) return false;

    final Object player = readFieldOrGetter(mc, "player", "player", "getPlayer");
    if (player == null) return false;

    final UUID localUuid = asUuid(invokeNoArg(player, "getUUID", "getUuid"));
    if (localUuid == null) return false;

    final Object profile = invokeNoArg(playerInfoLike, "getProfile", "getProfile");
    if (profile == null) return false;

    final UUID profileUuid = asUuid(invokeNoArg(profile, "id", "getId"));
    return profileUuid != null && localUuid.equals(profileUuid);
  }

  private static Object getMinecraftInstance() {
    return invokeStaticNoArg(
      new String[] {
        "net.minecraft.client.Minecraft",
        "net.minecraft.client.MinecraftClient"
      },
      new String[] { "getInstance", "getInstance" }
    );
  }

  private static Object readNativeImage(Path path) throws IOException {
    final Class<?> clazz = findClass("com.mojang.blaze3d.platform.NativeImage");
    if (clazz == null) return null;

    final Method read = findMethod(clazz, "read", 1);
    if (read == null) return null;

    try (InputStream in = Files.newInputStream(path)) {
      return read.invoke(null, in);
    } catch (Exception e) {
      return null;
    }
  }

  private static Object newDynamicTexture(Object nativeImage) {
    for (String cn : new String[] {
      "net.minecraft.client.renderer.texture.DynamicTexture",
      "net.minecraft.client.texture.NativeImageBackedTexture"
    }) {
      final Class<?> cls = findClass(cn);
      if (cls == null) continue;
      for (Constructor<?> c : cls.getDeclaredConstructors()) {
        final Class<?>[] p = c.getParameterTypes();
        if (p.length == 1 && p[0].isInstance(nativeImage)) {
          try {
            c.setAccessible(true);
            return c.newInstance(nativeImage);
          } catch (Exception ignored) {}
        }
      }
    }
    return null;
  }

  private static Object newIdentifier(String namespace, String path) {
    final Class<?>[] classes = new Class<?>[] {
      findClass("net.minecraft.resources.ResourceLocation"),
      findClass("net.minecraft.resources.Identifier"),
      findClass("net.minecraft.util.Identifier")
    };

    for (Class<?> cls : classes) {
      if (cls == null) continue;

      for (String factory : new String[] { "fromNamespaceAndPath", "of", "tryParse" }) {
        Method m = findMethod(cls, factory, 2);
        if (m != null) {
          try {
            return m.invoke(null, namespace, path);
          } catch (Exception ignored) {}
        }
      }

      for (Constructor<?> c : cls.getDeclaredConstructors()) {
        Class<?>[] p = c.getParameterTypes();
        if (p.length == 2 && p[0] == String.class && p[1] == String.class) {
          try {
            c.setAccessible(true);
            return c.newInstance(namespace, path);
          } catch (Exception ignored) {}
        }
        if (p.length == 1 && p[0] == String.class) {
          try {
            c.setAccessible(true);
            return c.newInstance(namespace + ":" + path);
          } catch (Exception ignored) {}
        }
      }
    }

    return null;
  }

  private static boolean registerTexture(Object manager, Object id, Object texture) {
    for (Method m : manager.getClass().getMethods()) {
      String n = m.getName();
      if (!("register".equals(n) || "registerTexture".equals(n))) continue;
      if (m.getParameterCount() != 2) continue;
      Class<?>[] p = m.getParameterTypes();
      if (!p[0].isInstance(id) || !p[1].isInstance(texture)) continue;
      try {
        m.invoke(manager, id, texture);
        return true;
      } catch (Exception ignored) {}
    }
    return false;
  }

  private static Object invokeNoArg(Object target, String... names) {
    if (target == null) return null;
    for (String n : names) {
      try {
        Method m = target.getClass().getMethod(n);
        m.setAccessible(true);
        return m.invoke(target);
      } catch (Exception ignored) {}
    }
    return null;
  }

  private static Object readFieldOrGetter(Object target, String fieldName, String... getters) {
    if (target == null) return null;
    try {
      Field f = target.getClass().getDeclaredField(fieldName);
      f.setAccessible(true);
      Object v = f.get(target);
      if (v != null) return v;
    } catch (Exception ignored) {}
    return invokeNoArg(target, getters);
  }

  private static Object invokeStaticNoArg(String[] classNames, String[] methodNames) {
    for (String className : classNames) {
      Class<?> c = findClass(className);
      if (c == null) continue;
      for (String methodName : methodNames) {
        try {
          Method m = c.getMethod(methodName);
          m.setAccessible(true);
          Object out = m.invoke(null);
          if (out != null) return out;
        } catch (Exception ignored) {}
      }
    }
    return null;
  }

  private static Class<?> findClass(String name) {
    try {
      return Class.forName(name);
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static Method findMethod(Class<?> clazz, String name, int paramCount) {
    for (Method m : clazz.getMethods()) {
      if (m.getName().equals(name) && m.getParameterCount() == paramCount) return m;
    }
    for (Method m : clazz.getDeclaredMethods()) {
      if (m.getName().equals(name) && m.getParameterCount() == paramCount) return m;
    }
    return null;
  }

  private static UUID asUuid(Object maybeUuid) {
    if (maybeUuid instanceof UUID) return (UUID) maybeUuid;
    return null;
  }
}
