package app.fishbattery.capebridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

import java.lang.reflect.RecordComponent;


public final class LauncherCapeRuntime {
  private static final String CAPE_PATH_PROPERTY = "fishbattery.launcherCape.path";
  private static final String CAPE_URL_PROPERTY  = "fishbattery.launcherCape.url";
  private static final String CAPE_ID_PROPERTY   = "fishbattery.launcherCape.id";
  private static final String CAPE_TIER_PROPERTY = "fishbattery.launcherCape.tier";
  private static final String CAPE_CATALOG_PROPERTY = "fishbattery.launcherCape.catalog";
  private static final String CAPE_META_PROPERTY    = "fishbattery.launcherCape.meta";
  private static final String PLAYER_UUID_PROPERTY  = "fishbattery.launcherPlayer.uuid";

  private static String cachedSourceKey = "";
  private static Object cachedTextureId = null;      // usually Identifier
  private static Object cachedCapeAsset = null;      // ClientAsset wrapper (if needed)
  private static String cachedLocalUuidRaw = "";
  private static UUID cachedLocalUuid = null;
  private static boolean loggedTextureRegistrationDiagnostics = false;
  private static String lastLoggedPropsKey = "";
  private static boolean loggedRejectedRegistrationMethod = false;
  private static boolean loggedUnusableRegistrationValue = false;
  private static boolean loggedIdentifierCreationFailure = false;
  private static boolean loggedRecordCapeFallback = false;
  private static boolean loggedSkinReplaceSuccess = false;
  private static boolean loggedSkinReplaceFailure = false;

  private LauncherCapeRuntime() {}

  public static Object tryGetCapeTextureForLocalPlayer(Object playerInfoLike) {
    try {
      if (!isLocalPlayerProfile(playerInfoLike)) return null;
      return reloadCapeTextureFromSystemProperties();
    } catch (Throwable t) {
      System.err.println("[fishbattery_cape_bridge] Failed to load launcher cape: " + t);
      return null;
    }
  }

  /**
   * Returns the raw texture id/identifier for methods that expect cape texture id directly
   * (e.g. AbstractClientPlayerEntity#getCapeTexture on newer versions).
   */
  public static Object tryGetCapeTextureIdForLocalPlayer(Object playerInfoLike) {
    try {
      if (!isLocalPlayerProfile(playerInfoLike)) return null;
      final Object loaded = reloadCapeTextureFromSystemProperties();
      if (isUsableTextureIdValue(cachedTextureId)) return cachedTextureId;
      return isUsableTextureIdValue(loaded) ? loaded : null;
    } catch (Throwable t) {
      System.err.println("[fishbattery_cape_bridge] Failed to load launcher cape id: " + t);
      return null;
    }
  }

  /**
   * Replaces cape component inside SkinTextures (record) or similar skin object.
   * Works for:
   * - record SkinTextures( ... cape... )
   * - non-record constructor-based skins
   */
  public static Object tryReplaceCapeOnSkin(Object skinLike, Object newCapeValue) {
    if (skinLike == null || newCapeValue == null) return null;

    try {
      // Record path (SkinTextures is a record in 1.21+)
      if (skinLike.getClass().isRecord()) {
        Object out = tryReplaceCapeOnRecordSkin(skinLike, newCapeValue);
        if (out != null) {
          if (!loggedSkinReplaceSuccess) {
            loggedSkinReplaceSuccess = true;
            System.err.println("[fishbattery_cape_bridge] skin replace success via record path: " + skinLike.getClass().getName());
          }
          return out;
        }
      }

      // Non-record fallback (constructor rebuild)
      final Object currentCape = invokeNoArg(skinLike, "capeTexture", "cape", "getCapeTexture", "capeTextureId");
      Object replacementCape = coerceToType(
        currentCape != null ? currentCape.getClass() : null,
        newCapeValue
      );
      if (replacementCape == null) replacementCape = newCapeValue;

      final Object body   = invokeNoArg(skinLike, "texture", "body", "skin", "getTexture");
      final Object elytra = invokeNoArg(skinLike, "elytraTexture", "elytra", "getElytraTexture");
      final Object model  = invokeNoArg(skinLike, "model", "modelType", "getModel");
      final Object secure = invokeNoArg(skinLike, "secure", "isSecure");
      final Object textureUrl = invokeNoArg(skinLike, "textureUrl", "skinUrl", "url", "getTextureUrl");

      for (Constructor<?> c : skinLike.getClass().getDeclaredConstructors()) {
        Class<?>[] p = c.getParameterTypes();
        Object[] args;

        // Common shapes (varies by MC version)
        if (p.length == 5) {
          args = new Object[] { body, replacementCape, elytra, model, secure };
        } else if (p.length == 6) {
          args = new Object[] { body, textureUrl != null ? textureUrl : "", replacementCape, elytra, model, secure };
        } else {
          continue;
        }

        if (!parametersMatch(p, args)) continue;
        try {
          c.setAccessible(true);
          Object out = c.newInstance(args);
          if (!loggedSkinReplaceSuccess) {
            loggedSkinReplaceSuccess = true;
            System.err.println("[fishbattery_cape_bridge] skin replace success via ctor path: " + skinLike.getClass().getName());
          }
          return out;
        } catch (Throwable ignored) {}
      }
    } catch (Throwable ignored) {}

    if (!loggedSkinReplaceFailure) {
      loggedSkinReplaceFailure = true;
      System.err.println("[fishbattery_cape_bridge] skin replace failed for type: " + skinLike.getClass().getName() + " capeType=" + newCapeValue.getClass().getName());
    }
    return null;
  }

  // -------------------------
  // Cape loading + registering
  // -------------------------

  private static Object reloadCapeTextureFromSystemProperties() {
    final String rawPath = String.valueOf(System.getProperty(CAPE_PATH_PROPERTY, System.getProperty("fishbattery.cape.path", ""))).trim();
    final String rawUrl  = String.valueOf(System.getProperty(CAPE_URL_PROPERTY, "")).trim();

    final String propsKey = rawPath + "|" + rawUrl;
    if (!propsKey.equals(lastLoggedPropsKey)) {
      System.err.println("[fishbattery_cape_bridge] launcher props: path='" + rawPath + "' url='" + rawUrl + "'");
      lastLoggedPropsKey = propsKey;
    }

    final CapeSource source = resolveCapeSource(rawPath, rawUrl);
    if (source == null) {
      System.err.println("[fishbattery_cape_bridge] resolved cape source is null");
      return null;
    }

    if (source.cacheKey.equals(cachedSourceKey) && (cachedCapeAsset != null || isUsableTextureIdValue(cachedTextureId))) {
      // Return cached (Identifier or ClientAsset depending what we have)
      return cachedCapeAsset != null ? cachedCapeAsset : cachedTextureId;
    }

    final MinecraftClient mc = MinecraftClient.getInstance();
    if (mc == null) return null;

    final Object textureManager = mc.getTextureManager();
    if (textureManager == null) return null;

    // Signature gate for premium/founder
    final String tier = String.valueOf(System.getProperty(CAPE_TIER_PROPERTY, "")).trim().toLowerCase(Locale.ROOT);
    if ("premium".equals(tier) || "founder".equals(tier)) {
      if (!verifyCapeSignatureIfPresent(source, rawPath)) {
        System.err.println("[fishbattery_cape_bridge] premium cape signature missing or invalid");
        return null;
      }
    }

    final NativeImage nativeImage;
    try {
      nativeImage = readNativeImage(source.stream);
    } catch (IOException e) {
      System.err.println("[fishbattery_cape_bridge] Failed to read native image: " + e.getMessage());
      return null;
    }
    if (nativeImage == null) {
      System.err.println("[fishbattery_cape_bridge] nativeImage == null");
      return null;
    }

    // Use the *real* registerDynamicTexture API (no random boolean return type)
    final Object registeredId = registerDynamicTexture(textureManager, "launcher_cape/" + Integer.toHexString(source.cacheKey.hashCode()), nativeImage);
    if (!isUsableTextureIdValue(registeredId)) {
      if (!loggedUnusableRegistrationValue) {
        loggedUnusableRegistrationValue = true;
        System.err.println("[fishbattery_cape_bridge] registerDynamicTexture returned unusable value: " + registeredId);
      }
      return null;
    }

    cachedSourceKey = source.cacheKey;
    cachedTextureId = registeredId;
    cachedCapeAsset = null;

    System.err.println("[fishbattery_cape_bridge] registered cape textureId=" + registeredId);
    return cachedTextureId;
  }

  private static Object registerDynamicTexture(Object textureManager, String name, NativeImage image) {
    // Yarn names (1.21.1): TextureManager.registerDynamicTexture(String, NativeImage) -> Identifier
    try {
      Method m = textureManager.getClass().getMethod("registerDynamicTexture", String.class, NativeImage.class);
      m.setAccessible(true);
      return m.invoke(textureManager, name, image);
    } catch (Throwable ignored) {}

    // Some versions use Supplier<String> for the label
    try {
      Method m = textureManager.getClass().getMethod("registerDynamicTexture", Supplier.class, NativeImage.class);
      m.setAccessible(true);
      return m.invoke(textureManager, (Supplier<String>) () -> name, image);
    } catch (Throwable ignored) {}

    // Remapped runtime names can differ from Yarn; use signature-based discovery as a fallback.
    final Object dynamicTexture = buildDynamicTextureArg(textureManager, null, image);
    for (Method m : allMethods(textureManager.getClass())) {
      try {
        final Class<?>[] p = m.getParameterTypes();
        if (p.length != 2) continue;

        final Object arg0 = coerceTextureNameArg(p[0], name);
        if (arg0 == null) continue;

        final Object arg1 = coerceTextureDataArg(textureManager, p[1], image, dynamicTexture);
        if (arg1 == null) continue;

        m.setAccessible(true);
        final Object out = m.invoke(textureManager, arg0, arg1);
        if (isUsableTextureIdValue(out)) return out;
        if (out instanceof Boolean) {
          if (!loggedRejectedRegistrationMethod) {
            loggedRejectedRegistrationMethod = true;
            System.err.println("[fishbattery_cape_bridge] rejected texture registration method: " + methodKey(m) + " returned " + out);
          }
          if ((Boolean) out && isUsableTextureIdValue(arg0)) return arg0;
          continue;
        }
        if (out != null) continue;

        // Some methods return void/bool. If arg0 is id-like, use it as texture id.
        if (isUsableTextureIdValue(arg0)) return arg0;
      } catch (Throwable ignored) {}
    }

    // Last-resort fallback: some runtimes expose one-arg register methods.
    for (Method m : allMethods(textureManager.getClass())) {
      try {
        final Class<?>[] p = m.getParameterTypes();
        if (p.length != 1) continue;
        final Object arg = coerceTextureDataArg(textureManager, p[0], image, dynamicTexture);
        if (arg == null) continue;
        m.setAccessible(true);
        final Object out = m.invoke(textureManager, arg);
        if (isUsableTextureIdValue(out)) return out;
      } catch (Throwable ignored) {}
    }

    logTextureRegistrationDiagnostics(textureManager, image, dynamicTexture);
    return null;
  }

  private static boolean isUsableTextureIdValue(Object value) {
    if (value == null) return false;
    if (value instanceof Boolean) return false;
    if (value instanceof Number) return false;
    if (value instanceof Supplier<?>) return false;
    if (value instanceof CharSequence) return false;
    if (isIdentifierLike(value.getClass())) return true;
    final String n = value.getClass().getName().toLowerCase(Locale.ROOT);
    return n.contains("identifier")
      || n.contains("resource")
      || n.contains("clientasset")
      || n.contains("asset");
  }

  private static boolean isIdentifierLike(Class<?> cls) {
    if (cls == null) return false;
    final String n = cls.getName();
    if ("net.minecraft.class_2960".equals(n)) return true;
    if (n.endsWith(".Identifier") || n.endsWith(".ResourceLocation")) return true;
    boolean hasNs = false;
    boolean hasPath = false;
    for (Method m : cls.getMethods()) {
      if (m.getParameterCount() != 0 || m.getReturnType() != String.class) continue;
      final String mn = m.getName().toLowerCase(Locale.ROOT);
      if (mn.contains("namespace")) hasNs = true;
      if (mn.equals("path") || mn.contains("path")) hasPath = true;
    }
    return hasNs && hasPath;
  }

  private static Method[] allMethods(Class<?> cls) {
    final LinkedHashMap<String, Method> out = new LinkedHashMap<>();
    try {
      for (Method m : cls.getMethods()) {
        out.putIfAbsent(methodKey(m), m);
      }
    } catch (Throwable ignored) {}
    Class<?> cursor = cls;
    while (cursor != null && cursor != Object.class) {
      try {
        for (Method m : cursor.getDeclaredMethods()) {
          out.putIfAbsent(methodKey(m), m);
        }
      } catch (Throwable ignored) {}
      cursor = cursor.getSuperclass();
    }
    return out.values().toArray(new Method[0]);
  }

  private static String methodKey(Method m) {
    final StringBuilder sb = new StringBuilder();
    sb.append(m.getName()).append('(');
    final Class<?>[] p = m.getParameterTypes();
    for (int i = 0; i < p.length; i++) {
      if (i > 0) sb.append(',');
      sb.append(p[i].getName());
    }
    sb.append(")->").append(m.getReturnType().getName());
    return sb.toString();
  }

  private static void logTextureRegistrationDiagnostics(Object textureManager, NativeImage image, Object dynamicTexture) {
    if (loggedTextureRegistrationDiagnostics) return;
    loggedTextureRegistrationDiagnostics = true;
    try {
      final String imageType = image == null ? "null" : image.getClass().getName();
      final String dynType = dynamicTexture == null ? "null" : dynamicTexture.getClass().getName();
      System.err.println("[fishbattery_cape_bridge] texture registration diagnostics begin");
      System.err.println("[fishbattery_cape_bridge] textureManager=" + textureManager.getClass().getName());
      System.err.println("[fishbattery_cape_bridge] imageType=" + imageType + " dynamicTextureType=" + dynType);
      for (Method m : allMethods(textureManager.getClass())) {
        final Class<?>[] p = m.getParameterTypes();
        if (p.length > 2) continue;
        final StringBuilder sig = new StringBuilder();
        sig.append(m.getName()).append('(');
        for (int i = 0; i < p.length; i++) {
          if (i > 0) sig.append(", ");
          sig.append(p[i].getName());
        }
        sig.append(") -> ").append(m.getReturnType().getName());
        System.err.println("[fishbattery_cape_bridge] method " + sig);
      }
      System.err.println("[fishbattery_cape_bridge] texture registration diagnostics end");
    } catch (Throwable t) {
      System.err.println("[fishbattery_cape_bridge] diagnostics failed: " + t);
    }
  }

  private static Object buildDynamicTextureArg(Object textureManager, Class<?> expectedType, NativeImage image) {
    if (textureManager == null || image == null) return null;
    // Prefer known runtime texture class first.
    for (String cn : new String[] {
      "net.minecraft.class_1043",
      "net.minecraft.client.texture.NativeImageBackedTexture",
      "net.minecraft.client.renderer.texture.DynamicTexture"
    }) {
      try {
        Class<?> cls = Class.forName(cn);
        if (expectedType != null && !expectedType.isAssignableFrom(cls)) continue;
        for (Constructor<?> c : cls.getDeclaredConstructors()) {
          final Class<?>[] cp = c.getParameterTypes();
          try {
            if (cp.length == 1 && cp[0].isAssignableFrom(image.getClass())) {
              c.setAccessible(true);
              return c.newInstance(image);
            }
            if (cp.length == 2 && cp[0] == String.class && cp[1].isAssignableFrom(image.getClass())) {
              c.setAccessible(true);
              return c.newInstance("fishbattery_launcher_cape", image);
            }
            if (cp.length == 2 && Supplier.class.isAssignableFrom(cp[0]) && cp[1].isAssignableFrom(image.getClass())) {
              c.setAccessible(true);
              return c.newInstance((Supplier<String>) () -> "fishbattery_launcher_cape", image);
            }
          } catch (Throwable ignored) {}
        }
      } catch (Throwable ignored) {}
    }

    for (Method m : textureManager.getClass().getMethods()) {
      final Class<?>[] p = m.getParameterTypes();
      if (p.length != 2) continue;
      final Class<?> textureType = p[1];
      if (textureType.isPrimitive() || textureType == NativeImage.class) continue;
      if (expectedType != null && !expectedType.isAssignableFrom(textureType)) continue;

      for (Constructor<?> c : textureType.getDeclaredConstructors()) {
        final Class<?>[] cp = c.getParameterTypes();
        try {
          if (cp.length == 1 && cp[0].isAssignableFrom(image.getClass())) {
            c.setAccessible(true);
            return c.newInstance(image);
          }
          if (cp.length == 2 && cp[0] == String.class && cp[1].isAssignableFrom(image.getClass())) {
            c.setAccessible(true);
            return c.newInstance("fishbattery_launcher_cape", image);
          }
          if (cp.length == 2 && Supplier.class.isAssignableFrom(cp[0]) && cp[1].isAssignableFrom(image.getClass())) {
            c.setAccessible(true);
            return c.newInstance((Supplier<String>) () -> "fishbattery_launcher_cape", image);
          }
        } catch (Throwable ignored) {}
      }
    }
    return null;
  }

  private static Object coerceTextureNameArg(Class<?> expectedType, String name) {
    if (expectedType == String.class) return name;
    if (Supplier.class.isAssignableFrom(expectedType)) return (Supplier<String>) () -> name;
    return newIdentifier(expectedType, "fishbattery", name);
  }

  private static Object coerceTextureDataArg(Object textureManager, Class<?> expectedType, NativeImage image, Object dynamicTexture) {
    if (expectedType.isAssignableFrom(image.getClass())) return image;
    if (dynamicTexture != null && expectedType.isAssignableFrom(dynamicTexture.getClass())) return dynamicTexture;
    if (expectedType.getName().startsWith("net.minecraft.class_")) {
      final Object built = buildDynamicTextureArg(textureManager, expectedType, image);
      if (built != null && expectedType.isAssignableFrom(built.getClass())) return built;
    }
    return null;
  }

  private static Object newIdentifier(Class<?> cls, String namespace, String path) {
    final String nsPath = namespace + ":" + path;
    final String className = cls == null ? "" : cls.getName();

    // Explicit support for obfuscated 1.21+ ResourceLocation class.
    if ("net.minecraft.class_2960".equals(className)) {
      for (String methodName : new String[] { "method_60655", "fromNamespaceAndPath" }) {
        try {
          Method m = cls.getDeclaredMethod(methodName, String.class, String.class);
          if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
            m.setAccessible(true);
            Object out = m.invoke(null, namespace, path);
            if (out != null) return out;
          }
        } catch (Throwable ignored) {}
      }
      for (String methodName : new String[] { "method_60654", "parse", "method_60656", "withDefaultNamespace", "method_12829", "tryParse" }) {
        try {
          Method m = cls.getDeclaredMethod(methodName, String.class);
          if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
            m.setAccessible(true);
            Object out = m.invoke(null, nsPath);
            if (out != null) return out;
          }
        } catch (Throwable ignored) {}
      }
    }

    for (Constructor<?> c : cls.getDeclaredConstructors()) {
      try {
        final Class<?>[] p = c.getParameterTypes();
        if (p.length == 2 && p[0] == String.class && p[1] == String.class) {
          c.setAccessible(true);
          return c.newInstance(namespace, path);
        }
        if (p.length == 1 && p[0] == String.class) {
          c.setAccessible(true);
          return c.newInstance(nsPath);
        }
      } catch (Throwable ignored) {}
    }

    for (Method m : cls.getDeclaredMethods()) {
      try {
        if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
        if (!cls.isAssignableFrom(m.getReturnType())) continue;
        final Class<?>[] p = m.getParameterTypes();
        if (p.length == 2 && p[0] == String.class && p[1] == String.class) {
          m.setAccessible(true);
          return m.invoke(null, namespace, path);
        }
        if (p.length == 1 && p[0] == String.class) {
          m.setAccessible(true);
          return m.invoke(null, nsPath);
        }
      } catch (Throwable ignored) {}
    }

    return null;
  }
  private static Object tryWrapAsClientAsset(Class<?> expectedType, Object textureId) {
    if (textureId == null) return null;

    // Most 1.21+ builds: net.minecraft.core.ClientAsset$ResourceTexture(Identifier)
    // Some forks/mappings: net.minecraft.client.texture.ClientAsset$ResourceTexture
    for (String cn : new String[] {
      "net.minecraft.class_12079$class_10726",
      "net.minecraft.core.ClientAsset$ResourceTexture",
      "net.minecraft.client.texture.ClientAsset$ResourceTexture"
    }) {
      try {
        Class<?> cls = Class.forName(cn);
        if (expectedType != null && !expectedType.isAssignableFrom(cls)) continue;
        // Prefer ctor(Identifier id, Identifier texturePath) so dynamic texture ids don't get
        // remapped to namespace:textures/path.png resource lookups.
        for (Constructor<?> c : cls.getDeclaredConstructors()) {
          Class<?>[] p = c.getParameterTypes();
          if (p.length == 2 && p[0].isInstance(textureId) && p[1].isInstance(textureId)) {
            c.setAccessible(true);
            return c.newInstance(textureId, textureId);
          }
        }
        for (Constructor<?> c : cls.getDeclaredConstructors()) {
          Class<?>[] p = c.getParameterTypes();
          if (p.length >= 1 && p[0].isInstance(textureId)) {
            Object[] args = new Object[p.length];
            args[0] = textureId;
            for (int i = 1; i < p.length; i++) args[i] = defaultArg(p[i], textureId);
            c.setAccessible(true);
            return c.newInstance(args);
          }
        }
      } catch (Throwable ignored) {}
    }

    return null;
  }

  private static Object tryWrapAsClientAsset(Object textureId) {
    return tryWrapAsClientAsset(null, textureId);
  }

  private static Object defaultArg(Class<?> t, Object textureId) {
    if (t.isInstance(textureId)) return textureId;
    if (t == String.class) return "fishbattery_launcher_cape";
    if (t == boolean.class) return false;
    if (t == int.class) return 0;
    if (t == long.class) return 0L;
    if (!t.isPrimitive()) return null;
    return 0;
  }

  // -------------------------
  // Record replacement
  // -------------------------

  private static Object tryReplaceCapeOnRecordSkin(Object skinLike, Object newCapeValue) {
    try {
      Class<?> skinClass = skinLike.getClass();
      RecordComponent[] comps = skinClass.getRecordComponents();
      if (comps == null || comps.length == 0) return null;

      Class<?>[] ctorTypes = new Class<?>[comps.length];
      Object[] args = new Object[comps.length];

      for (int i = 0; i < comps.length; i++) {
        ctorTypes[i] = comps[i].getType();
        Method acc = comps[i].getAccessor();
        acc.setAccessible(true);
        args[i] = acc.invoke(skinLike);
      }

      // Replace every cape-like texture slot (typically cape + elytra), except index 0
      // which is usually the base skin texture.
      boolean replacedAny = false;
      for (int i = 0; i < ctorTypes.length; i++) {
        if (i == 0) continue;
        Object coerced = coerceToType(ctorTypes[i], newCapeValue);
        if (coerced == null) coerced = tryWrapAsClientAsset(ctorTypes[i], newCapeValue);
        if (coerced == null) continue;
        args[i] = coerced;
        replacedAny = true;
      }
      if (!replacedAny) return null;

      Constructor<?> ctor = skinClass.getDeclaredConstructor(ctorTypes);
      ctor.setAccessible(true);
      return ctor.newInstance(args);
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static int findComponentIndexByName(RecordComponent[] comps, String needle) {
    String n = needle.toLowerCase(Locale.ROOT);
    for (int i = 0; i < comps.length; i++) {
      String name = String.valueOf(comps[i].getName()).toLowerCase(Locale.ROOT);
      if (name.contains(n)) return i;
    }
    return -1;
  }

  private static int findCapeIndexHeuristic(RecordComponent[] comps, Class<?>[] ctorTypes, Object[] currentValues, Object newCapeValue) {
    if (comps == null || ctorTypes == null || currentValues == null) return -1;
    final List<Integer> candidates = new ArrayList<>();
    for (int i = 0; i < ctorTypes.length; i++) {
      if (coerceToType(ctorTypes[i], newCapeValue) != null) candidates.add(i);
    }
    if (candidates.isEmpty()) return -1;

    // SkinTextures-like record layouts usually keep cape at index 2.
    if (ctorTypes.length >= 3 && candidates.contains(2)) {
      if (!loggedRecordCapeFallback) {
        loggedRecordCapeFallback = true;
        System.err.println("[fishbattery_cape_bridge] using record cape fallback index=2");
      }
      return 2;
    }

    // Otherwise avoid index 0 (body texture) when possible.
    for (int idx : candidates) {
      if (idx != 0) {
        if (!loggedRecordCapeFallback) {
          loggedRecordCapeFallback = true;
          System.err.println("[fishbattery_cape_bridge] using record cape fallback index=" + idx);
        }
        return idx;
      }
    }

    return candidates.get(0);
  }

  private static Object coerceToType(Class<?> expected, Object value) {
    if (value == null) return null;
    if (expected == null) return value;
    if (expected.isInstance(value)) return value;

    // Optional
    if (expected == Optional.class) return Optional.of(value);

    // If the expected type is Optional<something>, try Optional.of(coercedInner) isn’t possible via erasure,
    // but Optional.of(value) works in practice if MC just checks presence.
    if (expected.getName().equals("java.util.Optional")) return Optional.of(value);

    // Constructors
    for (Constructor<?> c : expected.getDeclaredConstructors()) {
      try {
        Class<?>[] p = c.getParameterTypes();
        if (p.length == 1 && p[0].isAssignableFrom(value.getClass())) {
          c.setAccessible(true);
          return c.newInstance(value);
        }
      } catch (Throwable ignored) {}
    }

    // Static factories: of/create/from
    for (Method m : expected.getDeclaredMethods()) {
      try {
        if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
        if (!expected.isAssignableFrom(m.getReturnType())) continue;
        Class<?>[] p = m.getParameterTypes();
        if (p.length == 1 && p[0].isAssignableFrom(value.getClass())) {
          m.setAccessible(true);
          return m.invoke(null, value);
        }
      } catch (Throwable ignored) {}
    }

    return null;
  }

  // -------------------------
  // Local-player detection
  // -------------------------

  private static boolean isLocalPlayerProfile(Object playerInfoLike) {
    UUID localUuid = getConfiguredLocalPlayerUuid();
    if (localUuid == null) return true; // if not configured, apply to local by default
    UUID profileUuid = extractUuid(playerInfoLike, new IdentityHashMap<>(), 0);
    if (profileUuid == null) return true;
    return localUuid.equals(profileUuid);
  }

  private static UUID getConfiguredLocalPlayerUuid() {
    String raw = String.valueOf(System.getProperty(PLAYER_UUID_PROPERTY, "")).trim();
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
    String raw = String.valueOf(value == null ? "" : value).trim();
    if (raw.isEmpty()) return null;
    try { return UUID.fromString(raw); } catch (Exception ignored) {}
    if (raw.length() == 32) {
      String dashed =
        raw.substring(0, 8) + "-" +
        raw.substring(8, 12) + "-" +
        raw.substring(12, 16) + "-" +
        raw.substring(16, 20) + "-" +
        raw.substring(20);
      try { return UUID.fromString(dashed); } catch (Exception ignored) {}
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
      if (method.getReturnType() == UUID.class) {
        try {
          method.setAccessible(true);
          Object out = method.invoke(target);
          if (out instanceof UUID) return (UUID) out;
        } catch (Exception ignored) {}
      }
    }

    return null;
  }

  // -------------------------
  // Cape catalog helpers (unchanged)
  // -------------------------

  public static List<CapeOption> getSelectableCapes() {
    Path catalogPath = resolveCatalogPath();
    if (catalogPath == null || !Files.isRegularFile(catalogPath)) return Collections.emptyList();
    List<CapeOption> out = new ArrayList<>();
    try {
      List<String> lines = Files.readAllLines(catalogPath, StandardCharsets.UTF_8);
      for (String rawLine : lines) {
        String line = String.valueOf(rawLine).trim();
        if (line.isEmpty() || line.startsWith("#")) continue;
        if (!line.startsWith("cape\t")) continue;
        String[] parts = line.split("\t", -1);
        if (parts.length < 6) continue;
        String id = decodeCatalogField(parts[1]);
        if (id.isEmpty()) continue;
        String name = decodeCatalogField(parts[2]);
        String tier = decodeCatalogField(parts[3]).toLowerCase(Locale.ROOT);
        String fullPath = decodeCatalogField(parts[4]);
        String cloudUrl = decodeCatalogField(parts[5]);
        out.add(new CapeOption(id, name.isEmpty() ? id : name, tier, fullPath, cloudUrl));
      }
    } catch (Exception ignored) {}
    return out;
  }

  public static String getSelectedCapeId() {
    return String.valueOf(System.getProperty(CAPE_ID_PROPERTY, "")).trim();
  }

  public static boolean selectCapeById(String capeId) {
    String id = String.valueOf(capeId == null ? "" : capeId).trim();
    if (id.isEmpty()) {
      System.setProperty(CAPE_PATH_PROPERTY, "");
      System.setProperty(CAPE_URL_PROPERTY, "");
      System.setProperty(CAPE_ID_PROPERTY, "");
      System.setProperty(CAPE_TIER_PROPERTY, "");
      cachedSourceKey = "";
      cachedTextureId = null;
      cachedCapeAsset = null;
      saveSelectedCapeToCatalog("");
      saveSelectedCapeToMeta("", "", "", "");
      return true;
    }

    List<CapeOption> options = getSelectableCapes();
    CapeOption selected = null;
    for (CapeOption option : options) {
      if (id.equals(option.id)) { selected = option; break; }
    }
    if (selected == null) return false;

    System.setProperty(CAPE_PATH_PROPERTY, selected.fullPath);
    System.setProperty(CAPE_URL_PROPERTY, selected.cloudUrl);
    System.setProperty(CAPE_ID_PROPERTY, selected.id);
    System.setProperty(CAPE_TIER_PROPERTY, selected.tier);

    cachedSourceKey = "";
    cachedTextureId = null;
    cachedCapeAsset = null;

    reloadCapeTextureFromSystemProperties();
    saveSelectedCapeToCatalog(selected.id);
    saveSelectedCapeToMeta(selected.id, selected.tier, selected.fullPath, selected.cloudUrl);
    return true;
  }

  // -------------------------
  // IO helpers
  // -------------------------

  private static NativeImage readNativeImage(InputStream input) throws IOException {
    try (InputStream in = input) {
      return NativeImage.read(in);
    } catch (Exception e) {
      System.err.println("[fishbattery_cape_bridge] readNativeImage error: " + e.getMessage());
      return null;
    }
  }

  private static CapeSource resolveCapeSource(String rawPath, String rawUrl) {
    if (!rawPath.isEmpty()) {
      try {
        Path path = Path.of(rawPath);
        if (Files.isRegularFile(path)) {
          long mtime = Files.getLastModifiedTime(path).toMillis();
          byte[] b = Files.readAllBytes(path);
          return new CapeSource(b, "path:" + rawPath + ":" + mtime);
        }
      } catch (Exception ignored) {}
    }

    if (!rawUrl.isEmpty()) {
      try {
        if (rawUrl.startsWith("data:")) {
          int comma = rawUrl.indexOf(',');
          if (comma > 0 && comma < rawUrl.length() - 1) {
            String head = rawUrl.substring(0, comma);
            String body = rawUrl.substring(comma + 1);
            byte[] bytes = head.contains(";base64")
              ? Base64.getDecoder().decode(body)
              : URLDecoder.decode(body, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
            return new CapeSource(bytes, "data:" + bytes.length);
          }
        } else if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) {
          URL url = URI.create(rawUrl).toURL();
          HttpURLConnection conn = (HttpURLConnection) url.openConnection();
          conn.setRequestMethod("GET");
          conn.setConnectTimeout(5000);
          conn.setReadTimeout(10000);
          conn.setRequestProperty("User-Agent", "FishbatteryCapeBridge/1.0");
          int status = conn.getResponseCode();
          if (status >= 200 && status < 300) {
            byte[] b = conn.getInputStream().readAllBytes();
            return new CapeSource(b, "url:" + rawUrl);
          }
          conn.disconnect();
        }
      } catch (Exception ignored) {}
    }
    return null;
  }

  private static final class CapeSource {
    final byte[] bytes;
    final InputStream stream;
    final String cacheKey;

    CapeSource(byte[] bytes, String cacheKey) {
      this.bytes = bytes;
      this.stream = bytes == null ? null : new java.io.ByteArrayInputStream(bytes);
      this.cacheKey = cacheKey;
    }
  }

  // -------------------------
  // Signature verification (keep yours)
  // -------------------------

  private static final String PUBLIC_KEY_BASE64 =
    "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAw7KqAjdgfUUjPOJwozb0XV4aLcnSd2v9hJQX47crxkLnifCE5MEVEvbwSImgslPAvlaRLt129joAlvgPCGZ1mID40EhiYQagtQAdnU4tZuGS9MOBPRBvepUlF5bbTiVpuC2J0qtt7KkKehcp+F6mWYXKzyApKZa3QiqeUk8QQ3Z8WaOog4ZPN+frup6J/UnUYHPFfeyKHW9jFS9VT7yWLSMtOpxwl+UP+Y+fXkubqaTX9WwnA/UxHRvVdlbbIGgYzF1iIWMrb7Ff6inUEv+Kjb3lvPKZfkK7THltj83q0GUf3FdH9u25qHCOapR3GD6nNBWkcY9amNJxRc6qvX87ErkBfBLRihKVyVt8nz/XLbWj0vwHvEhErmYWv/fd8crSqFUoud5dJPjRYz6D4E2qLb23mu7L32a/+O4Ds/EwAjdAbIMDFHliVoC4gYaLeRymW/Zu1mrBxaj5nxD+D6/KCBXmTigdcKvVHW3T8GRPHi9o0u8kVFeIQkuOxMmaPFCf1aNomdTulaZ54HwT+fe+DV6ykrYm5IxpSc1y3r//1BhSvYl0v++FAw9tyqfSet53W2rPBBUSr3nue/psTiGbn/D0mHiWPYd2F99luGpaCHVnn89Ya45ZvnCq36U/tWNwBnMv4HX7xGa5FTz5rqJoHftIxiNUH8r06dPirDUsjdkCAwEAAQ==";

  private static boolean verifyCapeSignatureIfPresent(CapeSource source, String rawPath) {
    try {
      String sigProp = String.valueOf(System.getProperty("fishbattery.cape.sig", "")).trim();
      byte[] signature = null;
      if (!sigProp.isEmpty()) {
        try { signature = Base64.getDecoder().decode(sigProp); } catch (Exception ignored) {}
      }

      if (signature == null && rawPath != null && !rawPath.isEmpty()) {
        try {
          Path sigPath = Path.of(rawPath + ".sig");
          if (Files.isRegularFile(sigPath)) signature = Files.readAllBytes(sigPath);
        } catch (Exception ignored) {}
      }

      if (signature == null || signature.length == 0) return false;
      byte[] data = source.bytes;
      if (data == null) return false;

      byte[] pub = Base64.getDecoder().decode(PUBLIC_KEY_BASE64);
      java.security.spec.X509EncodedKeySpec spec = new java.security.spec.X509EncodedKeySpec(pub);
      java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
      java.security.PublicKey pk = kf.generatePublic(spec);
      java.security.Signature sig = java.security.Signature.getInstance("SHA256withRSA");
      sig.initVerify(pk);
      sig.update(data);
      return sig.verify(signature);
    } catch (Throwable t) {
      return false;
    }
  }

  // -------------------------
  // Misc helpers
  // -------------------------

  private static Object invokeNoArg(Object target, String... names) {
    if (target == null) return null;
    for (String n : names) {
      try {
        Method m = target.getClass().getMethod(n);
        m.setAccessible(true);
        return m.invoke(target);
      } catch (Throwable ignored) {}
    }
    return null;
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
    String raw = String.valueOf(System.getProperty(CAPE_CATALOG_PROPERTY, "")).trim();
    if (!raw.isEmpty()) {
      try { return Path.of(raw); } catch (Exception ignored) {}
    }
    try { return Path.of(".fishbattery", "launcher-capes.txt"); } catch (Exception ignored) { return null; }
  }

  private static Path resolveMetaPath() {
    String raw = String.valueOf(System.getProperty(CAPE_META_PROPERTY, "")).trim();
    if (raw.isEmpty()) return null;
    try { return Path.of(raw); } catch (Exception ignored) { return null; }
  }

  private static String decodeCatalogField(String value) {
    try { return URLDecoder.decode(String.valueOf(value), StandardCharsets.UTF_8); }
    catch (Exception ignored) { return String.valueOf(value); }
  }

  private static String encodeCatalogField(String value) {
    return java.net.URLEncoder.encode(String.valueOf(value == null ? "" : value), StandardCharsets.UTF_8);
  }

  private static void saveSelectedCapeToCatalog(String selectedCapeId) {
    Path catalogPath = resolveCatalogPath();
    if (catalogPath == null || !Files.isRegularFile(catalogPath)) return;
    try {
      List<String> in = Files.readAllLines(catalogPath, StandardCharsets.UTF_8);
      List<String> out = new ArrayList<>();
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
    Path metaPath = resolveMetaPath();
    if (metaPath == null) return;
    try {
      String json =
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

