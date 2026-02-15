package app.fishbattery.capebridge;

import com.mojang.blaze3d.platform.NativeImage;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;

public final class LauncherCapeRuntime {
  private static final String CAPE_PATH_PROPERTY = "fishbattery.launcherCape.path";
  private static final String CAPE_URL_PROPERTY = "fishbattery.launcherCape.url";
  private static final String CAPE_ID_PROPERTY = "fishbattery.launcherCape.id";
  private static final String CAPE_TIER_PROPERTY = "fishbattery.launcherCape.tier";
  private static final String CAPE_CATALOG_PROPERTY = "fishbattery.launcherCape.catalog";
  private static final String CAPE_META_PROPERTY = "fishbattery.launcherCape.meta";
  private static final String PLAYER_UUID_PROPERTY = "fishbattery.launcherPlayer.uuid";

  private static String cachedSourceKey = "";
  private static Object cachedTextureId = null;
  private static Object cachedCapeTexture = null;
  private static String cachedLocalUuidRaw = "";
  private static UUID cachedLocalUuid = null;

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

  public static Object tryReplaceCapeOnSkin(Object skinLike, Object capeTextureId) {
    if (skinLike == null || capeTextureId == null) return null;
    try {
      final Object currentCape = invokeNoArg(skinLike, "cape", "capeTexture", "getCapeTexture");
      Object replacementCape = coerceCapeForExpectedType(currentCape != null ? currentCape.getClass() : null, capeTextureId);
      if (replacementCape == null) replacementCape = capeTextureId;

      if (skinLike.getClass().isRecord()) {
        final Object replacedRecord = tryReplaceCapeOnRecordSkin(skinLike, replacementCape);
        if (replacedRecord != null) return replacedRecord;
      }

      final Object body = invokeNoArg(skinLike, "body", "texture", "skin", "getTexture");
      final Object cape = currentCape;
      final Object elytra = invokeNoArg(skinLike, "elytra", "elytraTexture", "getElytraTexture");
      final Object model = invokeNoArg(skinLike, "model", "modelType", "getModel");
      final Object secure = invokeNoArg(skinLike, "secure", "isSecure");

      if (body == null || model == null || secure == null) return null;
      // Keep old cape if no replacement provided (defensive), but we always pass replacement.
      final Object nextCape = replacementCape != null ? replacementCape : cape;
      final Object textureUrl = invokeNoArg(skinLike, "textureUrl", "skinUrl", "url", "getTextureUrl");

      for (Constructor<?> c : skinLike.getClass().getDeclaredConstructors()) {
        final Class<?>[] p = c.getParameterTypes();
        final Object[] args;
        if (p.length == 5) {
          args = new Object[] { body, nextCape, elytra, model, secure };
        } else if (p.length == 6) {
          args = new Object[] { body, textureUrl != null ? textureUrl : "", nextCape, elytra, model, secure };
        } else {
          continue;
        }
        if (!parametersMatch(p, args)) continue;
        try {
          c.setAccessible(true);
          return c.newInstance(args);
        } catch (Exception ignored) {}
      }
    } catch (Throwable ignored) {}
    return null;
  }

  private static Object tryReplaceCapeOnRecordSkin(Object skinLike, Object capeTextureId) {
    try {
      final Class<?> skinClass = skinLike.getClass();
      final java.lang.reflect.RecordComponent[] components = skinClass.getRecordComponents();
      if (components == null || components.length == 0) return null;

      final Class<?>[] constructorTypes = new Class<?>[components.length];
      final Object[] args = new Object[components.length];
      for (int i = 0; i < components.length; i++) {
        constructorTypes[i] = components[i].getType();
        Method accessor = components[i].getAccessor();
        accessor.setAccessible(true);
        args[i] = accessor.invoke(skinLike);
      }

      final int capeIndex = findCapeComponentIndex(components, capeTextureId);
      if (capeIndex < 0) return null;
      Object replacementCape = capeTextureId;
      if (!isAssignable(constructorTypes[capeIndex], replacementCape.getClass())) {
        replacementCape = coerceCapeForExpectedType(constructorTypes[capeIndex], capeTextureId);
      }
      if (replacementCape == null || !isAssignable(constructorTypes[capeIndex], replacementCape.getClass())) return null;
      args[capeIndex] = replacementCape;

      final Constructor<?> constructor = skinClass.getDeclaredConstructor(constructorTypes);
      constructor.setAccessible(true);
      return constructor.newInstance(args);
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static int findCapeComponentIndex(java.lang.reflect.RecordComponent[] components, Object capeTextureId) {
    final Class<?> capeType = capeTextureId.getClass();
    int matchCount = 0;
    int lastMatch = -1;
    for (int i = 0; i < components.length; i++) {
      final Class<?> type = components[i].getType();
      if (isAssignable(type, capeType) || isAssignable(capeType, type)) {
        matchCount += 1;
        lastMatch = i;
        if (matchCount == 2) return i;
      }
    }
    if (matchCount == 1) return lastMatch;

    for (int i = 0; i < components.length; i++) {
      final String name = String.valueOf(components[i].getName()).toLowerCase(Locale.ROOT);
      if (name.contains("cape")) return i;
    }
    return -1;
  }

  public static List<CapeOption> getSelectableCapes() {
    final Path catalogPath = resolveCatalogPath();
    if (catalogPath == null || !Files.isRegularFile(catalogPath)) return Collections.emptyList();
    final List<CapeOption> out = new ArrayList<>();
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
      System.setProperty(CAPE_PATH_PROPERTY, "");
      System.setProperty(CAPE_URL_PROPERTY, "");
      System.setProperty(CAPE_ID_PROPERTY, "");
      System.setProperty(CAPE_TIER_PROPERTY, "");
      cachedSourceKey = "";
      cachedTextureId = null;
      cachedCapeTexture = null;
      saveSelectedCapeToCatalog("");
      saveSelectedCapeToMeta("", "", "", "");
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
    cachedSourceKey = "";
    cachedTextureId = null;
    cachedCapeTexture = null;
    reloadCapeTextureFromSystemProperties();
    saveSelectedCapeToCatalog(selected.id);
    saveSelectedCapeToMeta(selected.id, selected.tier, selected.fullPath, selected.cloudUrl);
    return true;
  }

  private static Object reloadCapeTextureFromSystemProperties() {
    final String rawPath = System.getProperty(CAPE_PATH_PROPERTY, "").trim();
    final String rawUrl = System.getProperty(CAPE_URL_PROPERTY, "").trim();
    final CapeSource source = resolveCapeSource(rawPath, rawUrl);
    if (source == null) return null;
    if (source.cacheKey.equals(cachedSourceKey) && cachedTextureId != null) {
      return cachedTextureId;
    }

    final Minecraft mc = Minecraft.getInstance();
    if (mc == null) return null;

    final Object textureManager = mc.getTextureManager();
    if (textureManager == null) return null;

    final NativeImage nativeImage;
    try {
      nativeImage = readNativeImage(source.stream);
    } catch (IOException ignored) {
      return null;
    }
    if (nativeImage == null) return null;

    final Object dynamicTexture = newDynamicTexture(nativeImage);
    if (dynamicTexture == null) return null;

    final Class<?> identifierType = findIdentifierParameterType(textureManager, dynamicTexture);
    if (identifierType == null) return null;

    final Object textureId = newIdentifier(identifierType, "fishbattery", "launcher_cape_dynamic");
    if (textureId == null) return null;

    if (!registerTexture(textureManager, textureId, dynamicTexture)) return null;

    cachedSourceKey = source.cacheKey;
    cachedTextureId = textureId;
    cachedCapeTexture = createClientAssetTexture(textureId);
    return textureId;
  }

  private static boolean isLocalPlayerProfile(Object playerInfoLike) {
    final UUID localUuid = getConfiguredLocalPlayerUuid();
    if (localUuid == null) return true;
    final UUID profileUuid = extractUuid(playerInfoLike, new IdentityHashMap<>(), 0);
    if (profileUuid == null) return true;
    return localUuid.equals(profileUuid);
  }

  private static NativeImage readNativeImage(InputStream input) throws IOException {
    try (InputStream in = input) {
      return NativeImage.read(in);
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

    if (!rawUrl.isEmpty()) {
      try {
        if (rawUrl.startsWith("data:")) {
          final int comma = rawUrl.indexOf(',');
          if (comma > 0 && comma < rawUrl.length() - 1) {
            final String head = rawUrl.substring(0, comma);
            final String body = rawUrl.substring(comma + 1);
            final byte[] bytes = head.contains(";base64")
              ? Base64.getDecoder().decode(body)
              : URLDecoder.decode(body, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
            return new CapeSource(new java.io.ByteArrayInputStream(bytes), "data:" + bytes.length);
          }
        } else if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
          final URL url = URI.create(rawUrl).toURL();
          final HttpURLConnection conn = (HttpURLConnection) url.openConnection();
          conn.setRequestMethod("GET");
          conn.setConnectTimeout(5000);
          conn.setReadTimeout(10000);
          conn.setRequestProperty("User-Agent", "FishbatteryCapeBridge/1.0");
          final int status = conn.getResponseCode();
          if (status >= 200 && status < 300) {
            return new CapeSource(conn.getInputStream(), "url:" + rawUrl);
          }
          conn.disconnect();
        }
      } catch (Exception ignored) {}
    }
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
    if (!(nativeImage instanceof NativeImage)) return null;
    for (Constructor<?> c : DynamicTexture.class.getDeclaredConstructors()) {
      final Class<?>[] p = c.getParameterTypes();
      if (p.length == 1 && p[0].isAssignableFrom(nativeImage.getClass())) {
        try {
          c.setAccessible(true);
          return c.newInstance(nativeImage);
        } catch (Exception ignored) {}
      }
      if (p.length == 2 && p[1].isAssignableFrom(nativeImage.getClass())) {
        final Object firstArg = buildDynamicTextureNameArg(p[0]);
        if (firstArg == null) continue;
        try {
          c.setAccessible(true);
          return c.newInstance(firstArg, nativeImage);
        } catch (Exception ignored) {}
      }
    }
    return null;
  }

  private static Object newIdentifier(Class<?> cls, String namespace, String path) {

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

    for (Method m : cls.getDeclaredMethods()) {
      if ((m.getModifiers() & java.lang.reflect.Modifier.STATIC) == 0) continue;
      if (!cls.isAssignableFrom(m.getReturnType())) continue;
      final Class<?>[] p = m.getParameterTypes();
      if (p.length == 2 && p[0] == String.class && p[1] == String.class) {
        try {
          m.setAccessible(true);
          return m.invoke(null, namespace, path);
        } catch (Exception ignored) {}
      }
      if (p.length == 1 && p[0] == String.class) {
        try {
          m.setAccessible(true);
          return m.invoke(null, namespace + ":" + path);
        } catch (Exception ignored) {}
      }
    }

    return null;
  }

  private static Class<?> findIdentifierParameterType(Object manager, Object texture) {
    if (manager == null || texture == null) return null;
    final Class<?> textureType = texture.getClass();
    for (Method m : manager.getClass().getMethods()) {
      if (m.getParameterCount() != 2) continue;
      final Class<?>[] p = m.getParameterTypes();
      if (!p[1].isAssignableFrom(textureType)) continue;
      return p[0];
    }
    return null;
  }

  private static boolean registerTexture(Object manager, Object id, Object texture) {
    for (Method m : manager.getClass().getMethods()) {
      if (m.getParameterCount() != 2) continue;
      Class<?>[] p = m.getParameterTypes();
      if (!p[0].isInstance(id)) continue;
      if (!p[1].isAssignableFrom(texture.getClass())) continue;
      try {
        m.invoke(manager, id, texture);
        return true;
      } catch (Exception ignored) {}
    }
    return false;
  }

  private static Object coerceCapeForExpectedType(Class<?> expectedType, Object capeTextureId) {
    if (capeTextureId == null) return null;
    if (expectedType == null || isAssignable(expectedType, capeTextureId.getClass())) return capeTextureId;
    final String typeName = String.valueOf(expectedType.getName());
    if (!typeName.startsWith("net.minecraft.core.ClientAsset")) return null;
    final Object wrapped = createClientAssetTexture(capeTextureId);
    if (wrapped != null && isAssignable(expectedType, wrapped.getClass())) return wrapped;
    return null;
  }

  private static Object createClientAssetTexture(Object textureId) {
    if (textureId == null) return null;
    if (cachedTextureId == textureId && cachedCapeTexture != null) return cachedCapeTexture;
    try {
      final Class<?> resourceTextureClass = Class.forName("net.minecraft.core.ClientAsset$ResourceTexture");
      for (Constructor<?> c : resourceTextureClass.getDeclaredConstructors()) {
        final Class<?>[] p = c.getParameterTypes();
        if (p.length == 1 && isAssignable(p[0], textureId.getClass())) {
          c.setAccessible(true);
          return c.newInstance(textureId);
        }
        if (p.length == 2 && isAssignable(p[0], textureId.getClass()) && isAssignable(p[1], textureId.getClass())) {
          c.setAccessible(true);
          return c.newInstance(textureId, textureId);
        }
        if (p.length > 0 && isAssignable(p[0], textureId.getClass())) {
          final Object[] args = new Object[p.length];
          args[0] = textureId;
          for (int i = 1; i < p.length; i++) {
            if (isAssignable(p[i], textureId.getClass())) args[i] = textureId;
            else if (!p[i].isPrimitive()) args[i] = null;
            else args[i] = primitiveDefaultValue(p[i]);
          }
          c.setAccessible(true);
          return c.newInstance(args);
        }
      }
    } catch (Throwable ignored) {}
    return null;
  }

  private static Object primitiveDefaultValue(Class<?> primitiveType) {
    if (primitiveType == boolean.class) return Boolean.FALSE;
    if (primitiveType == byte.class) return Byte.valueOf((byte) 0);
    if (primitiveType == short.class) return Short.valueOf((short) 0);
    if (primitiveType == int.class) return Integer.valueOf(0);
    if (primitiveType == long.class) return Long.valueOf(0L);
    if (primitiveType == float.class) return Float.valueOf(0.0F);
    if (primitiveType == double.class) return Double.valueOf(0.0D);
    if (primitiveType == char.class) return Character.valueOf('\0');
    return null;
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

  private static Object buildDynamicTextureNameArg(Class<?> type) {
    if (type == String.class) return "fishbattery_launcher_cape";
    if (Supplier.class.isAssignableFrom(type)) {
      return (Supplier<String>) () -> "fishbattery_launcher_cape";
    }
    return null;
  }

  private static UUID getConfiguredLocalPlayerUuid() {
    final String raw = String.valueOf(System.getProperty(PLAYER_UUID_PROPERTY, "")).trim();
    if (raw.equals(cachedLocalUuidRaw)) return cachedLocalUuid;
    cachedLocalUuidRaw = raw;
    if (raw.isEmpty()) {
      cachedLocalUuid = null;
      return null;
    }
    cachedLocalUuid = parseUuid(raw);
    return cachedLocalUuid;
  }

  private static UUID parseUuid(String value) {
    final String raw = String.valueOf(value == null ? "" : value).trim();
    if (raw.isEmpty()) return null;
    try {
      return UUID.fromString(raw);
    } catch (Exception ignored) {}
    if (raw.length() == 32) {
      final String dashed =
        raw.substring(0, 8) + "-" +
        raw.substring(8, 12) + "-" +
        raw.substring(12, 16) + "-" +
        raw.substring(16, 20) + "-" +
        raw.substring(20);
      try {
        return UUID.fromString(dashed);
      } catch (Exception ignored) {}
    }
    return null;
  }

  private static UUID extractUuid(Object target, Map<Object, Boolean> seen, int depth) {
    if (target == null || depth > 4 || seen.containsKey(target)) return null;
    seen.put(target, Boolean.TRUE);

    if (target instanceof UUID) return (UUID) target;

    for (String name : new String[] { "id", "getId", "uuid", "getUuid", "getUUID" }) {
      Object value = invokeNoArg(target, name);
      if (value instanceof UUID) return (UUID) value;
    }

    for (Method method : target.getClass().getMethods()) {
      if (method.getParameterCount() != 0) continue;
      final Class<?> returnType = method.getReturnType();
      if (returnType == UUID.class) {
        try {
          method.setAccessible(true);
          final Object out = method.invoke(target);
          if (out instanceof UUID) return (UUID) out;
        } catch (Exception ignored) {}
      }
      if (returnType == Object.class || returnType.isPrimitive() || returnType.isArray()) continue;
      final UUID nested = invokeNestedUuid(target, method, seen, depth);
      if (nested != null) return nested;
    }

    Class<?> cursor = target.getClass();
    while (cursor != null && cursor != Object.class) {
      for (Field field : cursor.getDeclaredFields()) {
        if (field.getType() == UUID.class) {
          try {
            field.setAccessible(true);
            final Object out = field.get(target);
            if (out instanceof UUID) return (UUID) out;
          } catch (Exception ignored) {}
        }
        if (field.getType().isPrimitive() || field.getType().isArray()) continue;
        try {
          field.setAccessible(true);
          final Object nestedObject = field.get(target);
          final UUID nested = extractUuid(nestedObject, seen, depth + 1);
          if (nested != null) return nested;
        } catch (Exception ignored) {}
      }
      cursor = cursor.getSuperclass();
    }

    return null;
  }

  private static UUID invokeNestedUuid(Object target, Method method, Map<Object, Boolean> seen, int depth) {
    try {
      method.setAccessible(true);
      final Object nestedObject = method.invoke(target);
      return extractUuid(nestedObject, seen, depth + 1);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static boolean parametersMatch(Class<?>[] types, Object[] args) {
    if (types.length != args.length) return false;
    for (int i = 0; i < types.length; i++) {
      if (args[i] == null) {
        if (types[i].isPrimitive()) return false;
        continue;
      }
      if (!isAssignable(types[i], args[i].getClass())) return false;
    }
    return true;
  }

  private static boolean isAssignable(Class<?> target, Class<?> source) {
    if (target.isAssignableFrom(source)) return true;
    if (!target.isPrimitive()) return false;
    if (target == boolean.class) return source == Boolean.class;
    if (target == byte.class) return source == Byte.class;
    if (target == short.class) return source == Short.class || source == Byte.class;
    if (target == int.class) return source == Integer.class || source == Short.class || source == Byte.class;
    if (target == long.class) return source == Long.class || source == Integer.class || source == Short.class || source == Byte.class;
    if (target == float.class) return source == Float.class || source == Long.class || source == Integer.class;
    if (target == double.class) return source == Double.class || source == Float.class || source == Long.class;
    if (target == char.class) return source == Character.class;
    return false;
  }

  private static Path resolveCatalogPath() {
    final String raw = String.valueOf(System.getProperty(CAPE_CATALOG_PROPERTY, "")).trim();
    if (!raw.isEmpty()) {
      try {
        return Path.of(raw);
      } catch (Exception ignored) {}
    }
    try {
      return Path.of(".fishbattery", "launcher-capes.txt");
    } catch (Exception ignored) {
      return null;
    }
  }

  private static Path resolveMetaPath() {
    final String raw = String.valueOf(System.getProperty(CAPE_META_PROPERTY, "")).trim();
    if (raw.isEmpty()) return null;
    try {
      return Path.of(raw);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String decodeCatalogField(String value) {
    try {
      return URLDecoder.decode(String.valueOf(value), StandardCharsets.UTF_8);
    } catch (Exception ignored) {
      return String.valueOf(value);
    }
  }

  private static String encodeCatalogField(String value) {
    return java.net.URLEncoder.encode(String.valueOf(value == null ? "" : value), StandardCharsets.UTF_8);
  }

  private static void saveSelectedCapeToCatalog(String selectedCapeId) {
    final Path catalogPath = resolveCatalogPath();
    if (catalogPath == null || !Files.isRegularFile(catalogPath)) return;
    try {
      final List<String> in = Files.readAllLines(catalogPath, StandardCharsets.UTF_8);
      final List<String> out = new ArrayList<>();
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
      Files.write(catalogPath, String.join("\n", out).concat("\n").getBytes(StandardCharsets.UTF_8));
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
    return String.valueOf(value == null ? "" : value)
      .replace("\\", "\\\\")
      .replace("\"", "\\\"");
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
