package app.fishbattery.capebridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.lang.reflect.Method;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

// In-game launcher cape picker.
// This screen is intentionally reflection-heavy so it can survive minor API shifts
// across supported Minecraft versions/mappings without maintaining per-version UIs.
public final class CapeSelectorScreen extends Screen {
  // Keep page size fixed so layout stays stable on low-height windows.
  private static final int ROWS_PER_PAGE = 8;
  private final Screen parent;
  private final int page;
  private List<LauncherCapeRuntime.CapeOption> entries;

  public CapeSelectorScreen(Screen parent) {
    this(parent, 0);
  }

  public CapeSelectorScreen(Screen parent, int page) {
    super(literal("Fishbattery Capes"));
    this.parent = parent;
    this.page = Math.max(0, page);
    this.entries = new ArrayList<>();
  }

  @Override
  protected void init() {
    // Re-query runtime on each init so selection and remote catalog updates are visible immediately.
    this.entries = new ArrayList<>(LauncherCapeRuntime.getSelectableCapes());
    final String selectedId = LauncherCapeRuntime.getSelectedCapeId();
    final int totalPages = Math.max(1, (int) Math.ceil(this.entries.size() / (double) ROWS_PER_PAGE));
    final int currentPage = Math.min(this.page, totalPages - 1);
    final int start = currentPage * ROWS_PER_PAGE;
    final int end = Math.min(this.entries.size(), start + ROWS_PER_PAGE);

    final int buttonWidth = 320;
    final int buttonHeight = 20;
    final int left = (this.width - buttonWidth) / 2;
    int y = 40;

    addWidgetCompat(
      createButtonCompat(
        this.decorateSelected("No Fishbattery Cape", selectedId.isEmpty()),
        left,
        y,
        buttonWidth,
        buttonHeight,
        (btn) -> {
          LauncherCapeRuntime.selectCapeById("");
          // Re-open same page to refresh selected marker and keep UX consistent.
          this.minecraft.setScreen(new CapeSelectorScreen(this.parent, currentPage));
        }
      )
    );
    y += 24;

    for (int i = start; i < end; i++) {
      final LauncherCapeRuntime.CapeOption item = this.entries.get(i);
      final String tierLabel =
        item.tier == null || item.tier.isEmpty() ? "free" : item.tier.toLowerCase(Locale.ROOT);
      final String name = item.name == null || item.name.isEmpty() ? item.id : item.name;
      final String label = String.format("%s [%s]", name, tierLabel);
      addWidgetCompat(
        createButtonCompat(
          this.decorateSelected(label, item.id.equals(selectedId)),
          left,
          y,
          buttonWidth,
          buttonHeight,
          (btn) -> {
            LauncherCapeRuntime.selectCapeById(item.id);
            // Re-open to re-render active state and guarantee runtime side effects are reflected.
            this.minecraft.setScreen(new CapeSelectorScreen(this.parent, currentPage));
          }
        )
      );
      y += 24;
    }

    final int footerY = this.height - 28;
    final int navWidth = 98;
    addWidgetCompat(
      createButtonCompat(
        literal("Prev"),
        left,
        footerY,
        navWidth,
        20,
        (btn) -> {
          // Page navigation clamps to valid bounds.
          final int prev = Math.max(0, currentPage - 1);
          this.minecraft.setScreen(new CapeSelectorScreen(this.parent, prev));
        }
      )
    );
    addWidgetCompat(
      createButtonCompat(
        literal("Done"),
        (this.width - 100) / 2,
        footerY,
        100,
        20,
        (btn) -> this.minecraft.setScreen(this.parent)
      )
    );
    addWidgetCompat(
      createButtonCompat(
        literal("Next"),
        left + buttonWidth - navWidth,
        footerY,
        navWidth,
        20,
        (btn) -> {
          final int next = Math.min(totalPages - 1, currentPage + 1);
          this.minecraft.setScreen(new CapeSelectorScreen(this.parent, next));
        }
      )
    );
  }

  @Override
  public void onClose() {
    // Always return to caller screen rather than defaulting to generic back behavior.
    final Minecraft mc = this.minecraft;
    if (mc != null) mc.setScreen(this.parent);
  }

  private Component decorateSelected(String label, boolean selected) {
    return literal((selected ? "* " : "") + label);
  }

  private void addWidgetCompat(Object widget) {
    if (widget == null) return;
    // First pass: prefer methods whose return type matches the widget type/builder style.
    // This keeps behavior deterministic when multiple add* methods exist.
    Method preferred = null;
    for (Method m : this.getClass().getMethods()) {
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
        preferred.invoke(this, widget);
        return;
      } catch (Exception ignored) {}
    }

    // Fallback pass: invoke the first single-arg compatible adder.
    for (Method m : this.getClass().getMethods()) {
      if (m.getParameterCount() != 1) continue;
      final Class<?> p = m.getParameterTypes()[0];
      if (!p.isAssignableFrom(widget.getClass())) continue;
      try {
        m.invoke(this, widget);
        return;
      } catch (Exception ignored) {}
    }
  }

  private Object createButtonCompat(Component label, int x, int y, int width, int height, Button.OnPress onPress) {
    if (label == null || onPress == null) return null;
    try {
      // Preferred path for newer versions: Button.builder(...).bounds(...).build()
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
      // Legacy path for older mappings: direct Button constructor.
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

  private boolean applyBuilderBounds(Object builder, int x, int y, int width, int height) {
    // Search for any builder method taking four ints (x, y, w, h) regardless of name.
    for (Method m : builder.getClass().getMethods()) {
      if (m.getParameterCount() != 4) continue;
      final Class<?>[] p = m.getParameterTypes();
      if (p[0] != int.class || p[1] != int.class || p[2] != int.class || p[3] != int.class) continue;
      try {
        final Object out = m.invoke(builder, Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(width), Integer.valueOf(height));
        if (out == null || out == builder || builder.getClass().isAssignableFrom(out.getClass())) return true;
      } catch (Exception ignored) {}
    }
    return false;
  }

  private static Component literal(String text) {
    try {
      // Modern static factory.
      final Method m = Component.class.getMethod("literal", String.class);
      final Object out = m.invoke(null, text);
      if (out instanceof Component) return (Component) out;
    } catch (Exception ignored) {}
    try {
      // Legacy TextComponent constructor fallback.
      final Class<?> textComponent = Class.forName("net.minecraft.network.chat.TextComponent");
      final java.lang.reflect.Constructor<?> c = textComponent.getDeclaredConstructor(String.class);
      c.setAccessible(true);
      final Object out = c.newInstance(text);
      if (out instanceof Component) return (Component) out;
    } catch (Exception ignored) {}
    try {
      // Final fallback so callers never crash on missing text factory methods.
      final Method m = Component.class.getMethod("empty");
      final Object out = m.invoke(null);
      if (out instanceof Component) return (Component) out;
    } catch (Exception ignored) {}
    return null;
  }
}
