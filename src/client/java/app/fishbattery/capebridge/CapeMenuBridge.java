package app.fishbattery.capebridge;

/**
 * Minimal stub used by mixins to attach cape UI buttons.
 * We intentionally keep this minimal — the launcher UI already handles cape selection.
 */
public final class CapeMenuBridge {
  private CapeMenuBridge() {}

  public static void tryAttachToScreen(Object screen, boolean addButton) {
    // No-op: keep compilation happy. UI is managed by the launcher.
  }
}
