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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

public final class LauncherCapeRuntime {
  private static final String CAPE_PATH_PROPERTY = "fishbattery.launcherCape.path";
  private static final String CAPE_URL_PROPERTY = "fishbattery.launcherCape.url";
  private static final String CAPE_ID_PROPERTY = "fishbattery.launcherCape.id";
  private static final String CAPE_TIER_PROPERTY = "fishbattery.launcherCape.tier";
  private static final String CAPE_CATALOG_PROPERTY = "fishbattery.launcherCape.catalog";
  private static final String CAPE_META_PROPERTY = "fishbattery.launcherCape.meta";

  private static String cachedSourceKey = "";
  private static Object cachedTextureId = null;
  private static Object cachedCapeTextureHandle = null;

  private LauncherCapeRuntime() {}

  public static Object tryGetCapeTextureForLocalPlayer(Object playerInfoLike) {
    try {
      if (!isLocalPlayerProfile(playerInfoLike)) return null;
      return reloadCapeTextureFromSystemProperties();
    } catch (Throwable t) {
      System.err.println("[fishbattery_cape_bridge] Failed to load launcher cape: " + t.getMessage());
      return null;
    }
  }

  public static Object tryGetCapeTextureIdForLocalPlayerEntity(Object playerLike) {
    try {
      if (!isLocalPlayerEntity(playerLike)) return null;
      reloadCapeTextureFromSystemProperties();
      return cachedTextureId;
    } catch (Throwable t) {
      System.err.println("[fishbattery_cape_bridge] Failed to resolve local player cape texture id: " + t.getMessage());
      return null;
    }
  }

  public static Object tryGetCapeTextureHandleForLocalPlayerEntity(Object playerLike) {
    try {
      if (!isLocalPlayerEntity(playerLike)) return null;
      reloadCapeTextureFromSystemProperties();
      return cachedCapeTextureHandle;
    } catch (Throwable t) {
      System.err.println(
        "[fishbattery_cape_bridge] Failed to resolve local player cape texture handle: " + t.getMessage()
      );
      return null;
    }
  }

  public static Object tryReplaceCapeOnSkin(Object skinLike, Object capeTextureLike) {
    if (skinLike == null || capeTextureLike == null) return null;
    try {
      final Object body = invokeNoArg(skinLike, "body", "texture", "skin", "getTexture");
      final String textureUrl = stringOrNull(invokeNoArg(skinLike, "textureUrl", "getTextureUrl"));
      final Object elytra = invokeNoArg(skinLike, "elytra", "elytraTexture", "getElytraTexture");
      final Object model = invokeNoArg(skinLike, "model", "modelType", "getModel");
      final Boolean secure = asBoolean(invokeNoArg(skinLike, "secure", "isSecure"));
      if (body == null || model == null || secure == null) return null;

      for (Constructor<?> c : skinLike.getClass().getDeclaredConstructors()) {
        final Class<?>[] p = c.getParameterTypes();

        if (p.length == 5) {
          if (
            !accepts(p[0], body) ||
            !accepts(p[1], capeTextureLike) ||
            !accepts(p[2], elytra) ||
            !accepts(p[3], model) ||
            !acceptsBoolean(p[4])
          ) {
            continue;
          }
          try {
            c.setAccessible(true);
            return c.newInstance(body, capeTextureLike, elytra, model, secure);
          } catch (Exception ignored) {}
        }

        if (p.length == 6) {
          if (
            !accepts(p[0], body) ||
            !accepts(p[2], capeTextureLike) ||
            !accepts(p[3], elytra) ||
            !accepts(p[4], model) ||
            !acceptsBoolean(p[5])
          ) {
            continue;
          }
          if (p[1] != String.class) continue;
          try {
            c.setAccessible(true);
            return c.newInstance(body, textureUrl == null ? "" : textureUrl, capeTextureLike, elytra, model, secure);
          } catch (Exception ignored) {}
        }
      }
    } catch (Throwable ignored) {}
    return null;
  }

  public static List<CapeOption> getSelectableCapes() {
    final Path catalogPath = resolveCatalogPath();
    if (catalogPath == null || !Files.isRegularFile(catalogPath)) return Collections.emptyList();

    final List<CapeOption> out = new ArrayList<CapeOption>();
    try {
      final List<String> lines = Files.readAllLines(catalogPath, StandardCharsets.UTF_8);
      for (String rawLine : lines) {
        final String line = String.valueOf(rawLine).trim();
        if (line.isEmpty() || line.startsWith("#")) continue;
        if (!line.startsWith("cape\t")) continue;

        final String[] parts = line.split("\t", -1);
        if (parts.length < 6) continue;

        final String id = decodeCatalogField(parts[1]);
        if (id.isEmpty()) continue;
        final String name = decodeCatalogField(parts[2]);
        final String tier = decodeCatalogField(parts[3]).toLowerCase(Locale.ROOT);
        final String fullPath = decodeCatalogField(parts[4]);
        final String cloudUrl = decodeCatalogField(parts[5]);
        out.add(new CapeOption(id, name.isEmpty() ? id : name, tier, fullPath, cloudUrl));
      }
    } catch (Exception ignored) {}
    return out;
  }

  public static String getSelectedCapeId() {
    return String.valueOf(System.getProperty(CAPE_ID_PROPERTY, "")).trim();
  }

  public static boolean selectCapeById(String capeId) {
    final String id = String.valueOf(capeId == null ? "" : capeId).trim();
    if (id.isEmpty()) {
      clearSelectedCape();
      return true;
    }

    final List<CapeOption> options = getSelectableCapes();
    CapeOption selected = null;
    for (CapeOption option : options) {
      if (id.equals(option.id)) {
        selected = option;
        break;
      }
    }
    if (selected == null) return false;

    System.setProperty(CAPE_PATH_PROPERTY, selected.fullPath);
    System.setProperty(CAPE_URL_PROPERTY, selected.cloudUrl);
    System.setProperty(CAPE_ID_PROPERTY, selected.id);
    System.setProperty(CAPE_TIER_PROPERTY, selected.tier);
    resetCachedCape();
    reloadCapeTextureFromSystemProperties();
    saveSelectedCapeToCatalog(selected.id);
    saveSelectedCapeToMeta(selected.id, selected.tier, selected.fullPath, selected.cloudUrl);
    return true;
  }

  private static void clearSelectedCape() {
    System.setProperty(CAPE_PATH_PROPERTY, "");
    System.setProperty(CAPE_URL_PROPERTY, "");
    System.setProperty(CAPE_ID_PROPERTY, "");
    System.setProperty(CAPE_TIER_PROPERTY, "");
    resetCachedCape();
    saveSelectedCapeToCatalog("");
    saveSelectedCapeToMeta("", "", "", "");
  }

  private static void resetCachedCape() {
    cachedSourceKey = "";
    cachedTextureId = null;
    cachedCapeTextureHandle = null;
  }

  private static Object reloadCapeTextureFromSystemProperties() {
    final String rawPath = String.valueOf(System.getProperty(CAPE_PATH_PROPERTY, "")).trim();
    final String rawUrl = String.valueOf(System.getProperty(CAPE_URL_PROPERTY, "")).trim();
    final CapeSource source = resolveCapeSource(rawPath, rawUrl);
    if (source == null) return null;
    if (source.cacheKey.equals(cachedSourceKey) && cachedCapeTextureHandle != null) {
      return cachedCapeTextureHandle;
    }

    final Object minecraft = getMinecraftInstance();
    if (minecraft == null) return null;
    final Object textureManager = invokeNoArg(minecraft, "getTextureManager");
    if (textureManager == null) return null;

    final Object nativeImage;
    try {
      nativeImage = readNativeImage(source.stream);
    } catch (IOException ignored) {
      return null;
    }
    if (nativeImage == null) return null;

    final Object dynamicTexture = newDynamicTexture(nativeImage);
    if (dynamicTexture == null) return null;

    final Object textureId = newIdentifier("fishbattery", "launcher_cape_dynamic");
    if (textureId == null) return null;
    if (!registerTexture(textureManager, textureId, dynamicTexture)) return null;

    final Object capeTextureHandle = newCapeTextureHandle(textureId);
    if (capeTextureHandle == null) return null;

    cachedSourceKey = source.cacheKey;
    cachedTextureId = textureId;
    cachedCapeTextureHandle = capeTextureHandle;
    return cachedCapeTextureHandle;
  }

  private static boolean isLocalPlayerProfile(Object playerInfoLike) {
    final Object minecraft = getMinecraftInstance();
    if (minecraft == null) return false;

    final Object player = readFieldOrGetter(minecraft, "player", "getPlayer");
    if (player == null) return false;

    final UUID localUuid = asUuid(invokeNoArg(player, "getUUID", "getUuid"));
    if (localUuid == null) return false;

    final Object profile = invokeNoArg(playerInfoLike, "getProfile");
    if (profile == null) return false;
    final UUID profileUuid = asUuid(invokeNoArg(profile, "id", "getId"));
    return profileUuid != null && profileUuid.equals(localUuid);
  }

  private static boolean isLocalPlayerEntity(Object playerLike) {
    if (playerLike == null) return false;
    final Object minecraft = getMinecraftInstance();
    if (minecraft == null) return false;

    final Object localPlayer = readFieldOrGetter(minecraft, "player", "getPlayer");
    if (localPlayer == null) return false;

    final UUID localUuid = asUuid(invokeNoArg(localPlayer, "getUUID", "getUuid"));
    if (localUuid == null) return false;

    final UUID candidateUuid = asUuid(invokeNoArg(playerLike, "getUUID", "getUuid"));
    return candidateUuid != null && candidateUuid.equals(localUuid);
  }

  private static Object getMinecraftInstance() {
    return invokeStaticNoArg(
      new String[] { "net.minecraft.client.Minecraft", "net.minecraft.client.MinecraftClient" },
      new String[] { "getInstance" }
    );
  }

  private static Object readNativeImage(InputStream input) throws IOException {
    final Class<?> clazz = findClass("com.mojang.blaze3d.platform.NativeImage");
    if (clazz == null) return null;

    Method read = null;
    for (Method m : clazz.getMethods()) {
      if (!m.getName().equals("read") || m.getParameterCount() != 1) continue;
      final Class<?> p = m.getParameterTypes()[0];
      if (InputStream.class.isAssignableFrom(p)) {
        read = m;
        break;
      }
    }
    if (read == null) {
      for (Method m : clazz.getDeclaredMethods()) {
        if (!m.getName().equals("read") || m.getParameterCount() != 1) continue;
        final Class<?> p = m.getParameterTypes()[0];
        if (InputStream.class.isAssignableFrom(p)) {
          read = m;
          break;
        }
      }
    }
    if (read == null) return null;

    try (InputStream in = input) {
      read.setAccessible(true);
      return read.invoke(null, in);
    } catch (Exception e) {
      return null;
    }
  }

  private static CapeSource resolveCapeSource(String rawPath, String rawUrl) {
    if (!rawPath.isEmpty()) {
      try {
        final Path path = Paths.get(rawPath);
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
          : URLDecoder.decode(body, StandardCharsets.UTF_8.name()).getBytes(StandardCharsets.UTF_8);
        return new CapeSource(new java.io.ByteArrayInputStream(bytes), "data:" + bytes.length);
      }

      if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
        final URL url = URI.create(rawUrl).toURL();
        final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("User-Agent", "FishbatteryCapeBridge/1.2.3");
        final int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
          connection.disconnect();
          return null;
        }
        return new CapeSource(connection.getInputStream(), "url:" + rawUrl);
      }
    } catch (Exception ignored) {}
    return null;
  }

  private static Object newDynamicTexture(Object nativeImage) {
    for (String className : new String[] {
      "net.minecraft.client.renderer.texture.DynamicTexture",
      "net.minecraft.client.texture.NativeImageBackedTexture"
    }) {
      final Class<?> cls = findClass(className);
      if (cls == null) continue;

      for (Constructor<?> c : cls.getDeclaredConstructors()) {
        final Class<?>[] p = c.getParameterTypes();
        try {
          if (p.length == 1 && accepts(p[0], nativeImage)) {
            c.setAccessible(true);
            return c.newInstance(nativeImage);
          }

          if (p.length == 2 && Supplier.class.isAssignableFrom(p[0]) && accepts(p[1], nativeImage)) {
            c.setAccessible(true);
            final Supplier<String> nameSupplier = new Supplier<String>() {
              @Override
              public String get() {
                return "fishbattery_launcher_cape";
              }
            };
            return c.newInstance(nameSupplier, nativeImage);
          }

          if (p.length == 2 && p[0] == String.class && accepts(p[1], nativeImage)) {
            c.setAccessible(true);
            return c.newInstance("fishbattery_launcher_cape", nativeImage);
          }
        } catch (Exception ignored) {}
      }
    }
    return null;
  }

  private static Object newIdentifier(String namespace, String path) {
    final Class<?>[] classes = new Class<?>[] {
      findClass("net.minecraft.resources.Identifier"),
      findClass("net.minecraft.resources.ResourceLocation"),
      findClass("net.minecraft.util.Identifier")
    };

    final String full = namespace + ":" + path;
    for (Class<?> cls : classes) {
      if (cls == null) continue;

      for (String factory : new String[] { "fromNamespaceAndPath", "of", "tryParse", "parse" }) {
        final Method m2 = findMethod(cls, factory, 2);
        if (m2 != null) {
          try {
            return m2.invoke(null, namespace, path);
          } catch (Exception ignored) {}
        }

        final Method m1 = findMethod(cls, factory, 1);
        if (m1 != null) {
          try {
            return m1.invoke(null, full);
          } catch (Exception ignored) {}
        }
      }

      for (Constructor<?> c : cls.getDeclaredConstructors()) {
        final Class<?>[] p = c.getParameterTypes();
        if (p.length == 2 && p[0] == String.class && p[1] == String.class) {
          try {
            c.setAccessible(true);
            return c.newInstance(namespace, path);
          } catch (Exception ignored) {}
        }
        if (p.length == 1 && p[0] == String.class) {
          try {
            c.setAccessible(true);
            return c.newInstance(full);
          } catch (Exception ignored) {}
        }
      }
    }

    return null;
  }

  private static Object newCapeTextureHandle(Object textureId) {
    if (textureId == null) return null;
    try {
      final Class<?> resourceTextureClass = Class.forName("net.minecraft.core.ClientAsset$ResourceTexture");
      for (Constructor<?> c : resourceTextureClass.getDeclaredConstructors()) {
        final Class<?>[] p = c.getParameterTypes();
        if (p.length == 2 && accepts(p[0], textureId) && accepts(p[1], textureId)) {
          c.setAccessible(true);
          return c.newInstance(textureId, textureId);
        }
        if (p.length == 1 && accepts(p[0], textureId)) {
          c.setAccessible(true);
          return c.newInstance(textureId);
        }
      }
    } catch (Throwable ignored) {}
    return textureId;
  }

  private static boolean registerTexture(Object manager, Object id, Object texture) {
    for (Method m : manager.getClass().getMethods()) {
      final String name = m.getName();
      if (!("register".equals(name) || "registerTexture".equals(name))) continue;
      if (m.getParameterCount() != 2) continue;
      final Class<?>[] p = m.getParameterTypes();
      if (!accepts(p[0], id) || !accepts(p[1], texture)) continue;
      try {
        m.invoke(manager, id, texture);
        return true;
      } catch (Exception ignored) {}
    }
    return false;
  }

  private static Object invokeNoArg(Object target, String... names) {
    if (target == null) return null;
    for (String name : names) {
      try {
        final Method m = target.getClass().getMethod(name);
        m.setAccessible(true);
        return m.invoke(target);
      } catch (Exception ignored) {}
    }
    return null;
  }

  private static Object readFieldOrGetter(Object target, String fieldName, String... getterNames) {
    if (target == null) return null;
    Class<?> cls = target.getClass();
    while (cls != null) {
      try {
        final Field field = cls.getDeclaredField(fieldName);
        field.setAccessible(true);
        final Object value = field.get(target);
        if (value != null) return value;
      } catch (Exception ignored) {}
      cls = cls.getSuperclass();
    }
    return invokeNoArg(target, getterNames);
  }

  private static Object invokeStaticNoArg(String[] classNames, String[] methodNames) {
    for (String className : classNames) {
      final Class<?> c = findClass(className);
      if (c == null) continue;
      for (String methodName : methodNames) {
        try {
          final Method m = c.getMethod(methodName);
          m.setAccessible(true);
          final Object out = m.invoke(null);
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

  private static boolean accepts(Class<?> type, Object value) {
    if (value == null) return !type.isPrimitive();
    if (type.isPrimitive()) return primitiveAccepts(type, value);
    return type.isInstance(value);
  }

  private static boolean acceptsBoolean(Class<?> type) {
    return type == boolean.class || type == Boolean.class;
  }

  private static boolean primitiveAccepts(Class<?> primitiveType, Object value) {
    if (primitiveType == boolean.class) return value instanceof Boolean;
    if (primitiveType == byte.class) return value instanceof Byte;
    if (primitiveType == short.class) return value instanceof Short;
    if (primitiveType == int.class) return value instanceof Integer;
    if (primitiveType == long.class) return value instanceof Long;
    if (primitiveType == float.class) return value instanceof Float;
    if (primitiveType == double.class) return value instanceof Double;
    if (primitiveType == char.class) return value instanceof Character;
    return false;
  }

  private static UUID asUuid(Object maybeUuid) {
    return maybeUuid instanceof UUID ? (UUID) maybeUuid : null;
  }

  private static Boolean asBoolean(Object maybeBoolean) {
    return maybeBoolean instanceof Boolean ? (Boolean) maybeBoolean : null;
  }

  private static String stringOrNull(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static Path resolveCatalogPath() {
    final String raw = String.valueOf(System.getProperty(CAPE_CATALOG_PROPERTY, "")).trim();
    if (!raw.isEmpty()) {
      try {
        return Paths.get(raw);
      } catch (Exception ignored) {}
    }
    try {
      return Paths.get(".fishbattery", "launcher-capes.txt");
    } catch (Exception ignored) {
      return null;
    }
  }

  private static Path resolveMetaPath() {
    final String raw = String.valueOf(System.getProperty(CAPE_META_PROPERTY, "")).trim();
    if (raw.isEmpty()) return null;
    try {
      return Paths.get(raw);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String decodeCatalogField(String value) {
    try {
      return URLDecoder.decode(String.valueOf(value), StandardCharsets.UTF_8.name());
    } catch (Exception ignored) {
      return String.valueOf(value);
    }
  }

  private static String encodeCatalogField(String value) {
    try {
      return URLEncoder.encode(String.valueOf(value == null ? "" : value), StandardCharsets.UTF_8.name());
    } catch (Exception ignored) {
      return String.valueOf(value == null ? "" : value);
    }
  }

  private static void saveSelectedCapeToCatalog(String selectedCapeId) {
    final Path catalogPath = resolveCatalogPath();
    if (catalogPath == null || !Files.isRegularFile(catalogPath)) return;
    try {
      final List<String> in = Files.readAllLines(catalogPath, StandardCharsets.UTF_8);
      final List<String> out = new ArrayList<String>();
      boolean replaced = false;
      for (String line : in) {
        if (String.valueOf(line).startsWith("selected=")) {
          out.add("selected=" + encodeCatalogField(selectedCapeId));
          replaced = true;
        } else {
          out.add(line);
        }
      }
      if (!replaced) out.add(0, "selected=" + encodeCatalogField(selectedCapeId));
      final String text = String.join("\n", out).concat("\n");
      Files.write(catalogPath, text.getBytes(StandardCharsets.UTF_8));
    } catch (Exception ignored) {}
  }

  private static void saveSelectedCapeToMeta(String capeId, String tier, String fullPath, String cloudUrl) {
    final Path metaPath = resolveMetaPath();
    if (metaPath == null) return;
    try {
      final String json =
        "{\n" +
        "  \"capeId\": \"" + escapeJson(capeId) + "\",\n" +
        "  \"tier\": \"" + escapeJson(tier) + "\",\n" +
        "  \"fullPath\": \"" + escapeJson(fullPath) + "\",\n" +
        "  \"cloudUrl\": \"" + escapeJson(cloudUrl) + "\",\n" +
        "  \"updatedAt\": " + System.currentTimeMillis() + "\n" +
        "}\n";
      Files.write(metaPath, json.getBytes(StandardCharsets.UTF_8));
    } catch (Exception ignored) {}
  }

  private static String escapeJson(String value) {
    return String.valueOf(value == null ? "" : value).replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static final class CapeSource {
    final InputStream stream;
    final String cacheKey;

    CapeSource(InputStream stream, String cacheKey) {
      this.stream = stream;
      this.cacheKey = cacheKey;
    }
  }

  public static final class CapeOption {
    public final String id;
    public final String name;
    public final String tier;
    public final String fullPath;
    public final String cloudUrl;

    public CapeOption(String id, String name, String tier, String fullPath, String cloudUrl) {
      this.id = String.valueOf(id == null ? "" : id).trim();
      this.name = String.valueOf(name == null ? "" : name).trim();
      this.tier = String.valueOf(tier == null ? "free" : tier).trim().toLowerCase(Locale.ROOT);
      this.fullPath = String.valueOf(fullPath == null ? "" : fullPath).trim();
      this.cloudUrl = String.valueOf(cloudUrl == null ? "" : cloudUrl).trim();
    }
  }
}
