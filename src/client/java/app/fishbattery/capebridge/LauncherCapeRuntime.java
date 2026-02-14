package app.fishbattery.capebridge;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;

public final class LauncherCapeRuntime {
  private static final String CAPE_PATH_PROPERTY = "fishbattery.launcherCape.path";
  private static final String CAPE_URL_PROPERTY = "fishbattery.launcherCape.url";

  private static String cachedSourceKey = "";
  private static Object cachedTextureId = null;

  private LauncherCapeRuntime() {}

  public static Object tryGetCapeTextureForLocalPlayer(Object playerInfoLike) {
    try {
      if (!isLocalPlayerProfile(playerInfoLike)) return null;

      final String rawPath = System.getProperty(CAPE_PATH_PROPERTY, "").trim();
      final String rawUrl = System.getProperty(CAPE_URL_PROPERTY, "").trim();
      final CapeSource source = resolveCapeSource(rawPath, rawUrl);
      if (source == null) return null;
      if (source.cacheKey.equals(cachedSourceKey) && cachedTextureId != null) {
        return cachedTextureId;
      }

      final Object mc = getMinecraftInstance();
      if (mc == null) return null;

      final Object textureManager = invokeNoArg(mc, "getTextureManager", "getTextureManager");
      if (textureManager == null) return null;

      final Object nativeImage = readNativeImage(source.stream);
      if (nativeImage == null) return null;

      final Object dynamicTexture = newDynamicTexture(nativeImage);
      if (dynamicTexture == null) return null;

      final Object textureId = newIdentifier("fishbattery", "launcher_cape_dynamic");
      if (textureId == null) return null;

      if (!registerTexture(textureManager, textureId, dynamicTexture)) return null;

      cachedSourceKey = source.cacheKey;
      cachedTextureId = textureId;
      return textureId;
    } catch (Throwable t) {
      System.err.println("[fishbattery_cape_bridge] Failed to load launcher cape: " + t.getMessage());
      return null;
    }
  }

  public static Object tryReplaceCapeOnSkin(Object skinLike, Object capeTextureId) {
    if (skinLike == null || capeTextureId == null) return null;
    try {
      final Object body = invokeNoArg(skinLike, "body", "texture", "skin", "getTexture");
      final Object cape = invokeNoArg(skinLike, "cape", "capeTexture", "getCapeTexture");
      final Object elytra = invokeNoArg(skinLike, "elytra", "elytraTexture", "getElytraTexture");
      final Object model = invokeNoArg(skinLike, "model", "modelType", "getModel");
      final Object secure = invokeNoArg(skinLike, "secure", "isSecure");

      if (body == null || model == null || secure == null) return null;
      // Keep old cape if no replacement provided (defensive), but we always pass replacement.
      final Object nextCape = capeTextureId != null ? capeTextureId : cape;

      for (Constructor<?> c : skinLike.getClass().getDeclaredConstructors()) {
        final Class<?>[] p = c.getParameterTypes();
        if (p.length != 5) continue;
        try {
          c.setAccessible(true);
          return c.newInstance(body, nextCape, elytra, model, secure);
        } catch (Exception ignored) {}
      }
    } catch (Throwable ignored) {}
    return null;
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

  private static Object readNativeImage(InputStream input) throws IOException {
    final Class<?> clazz = findClass("com.mojang.blaze3d.platform.NativeImage");
    if (clazz == null) return null;

    final Method read = findMethod(clazz, "read", 1);
    if (read == null) return null;

    try (InputStream in = input) {
      return read.invoke(null, in);
    } catch (Exception e) {
      return null;
    }
  }

  private static CapeSource resolveCapeSource(String rawPath, String rawUrl) {
    if (!rawPath.isEmpty()) {
      try {
        final Path path = Path.of(rawPath);
        if (Files.isRegularFile(path)) {
          final long mtime = Files.getLastModifiedTime(path).toMillis();
          return new CapeSource(Files.newInputStream(path), "path:" + rawPath + ":" + mtime);
        }
      } catch (Exception ignored) {}
    }

    if (rawUrl.isEmpty()) return null;
    try {
      if (rawUrl.startsWith("data:")) {
        final int comma = rawUrl.indexOf(',');
        if (comma <= 0 || comma >= rawUrl.length() - 1) return null;
        final String head = rawUrl.substring(0, comma);
        final String body = rawUrl.substring(comma + 1);
        final byte[] bytes = head.contains(";base64")
          ? Base64.getDecoder().decode(body)
          : URLDecoder.decode(body, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
        return new CapeSource(new java.io.ByteArrayInputStream(bytes), "data:" + bytes.length);
      }

      if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
        final URL url = URI.create(rawUrl).toURL();
        final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "FishbatteryCapeBridge/1.0");
        final int status = conn.getResponseCode();
        if (status < 200 || status >= 300) {
          conn.disconnect();
          return null;
        }
        return new CapeSource(conn.getInputStream(), "url:" + rawUrl);
      }
    } catch (Exception ignored) {}
    return null;
  }

  private static final class CapeSource {
    final InputStream stream;
    final String cacheKey;

    CapeSource(InputStream stream, String cacheKey) {
      this.stream = stream;
      this.cacheKey = cacheKey;
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
