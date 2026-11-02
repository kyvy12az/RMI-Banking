package vn.rmibank.common.ui;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import java.awt.Color;
import java.util.Map;

/** The original client SVG directory is packaged in common for both desktop applications. */
public final class BankIcons {
  private BankIcons() {}

  private static final Map<String, String> ALIASES =
      Map.ofEntries(
          Map.entry("bank", "landmark"), Map.entry("transfer", "arrow-right-left"),
          Map.entry("home", "house"), Map.entry("history", "clock"),
          Map.entry("refresh", "refresh-cw"), Map.entry("logout", "log-out"),
          Map.entry("logs", "file-text"), Map.entry("eyeOff", "eye-off"));

  public static FlatSVGIcon icon(String name, int size, Color color) {
    String path = "icons/" + ALIASES.getOrDefault(name, name) + ".svg";
    ClassLoader loader = BankIcons.class.getClassLoader();
    if (loader.getResource(path) == null)
      throw new IllegalArgumentException("Không tìm thấy SVG: " + path);
    FlatSVGIcon icon = new FlatSVGIcon(path, size, size, loader);
    icon.setColorFilter(
        new FlatSVGIcon.ColorFilter(
            original ->
                new Color(color.getRed(), color.getGreen(), color.getBlue(), original.getAlpha())));
    return icon;
  }

  public static String navigation(String title) {
    return switch (title) {
      case "Tổng quan" -> "house";
      case "Chuyển khoản", "Giao dịch" -> "arrow-right-left";
      case "Tài khoản" -> "users";
      case "Lịch sử giao dịch" -> "clock";
      case "Nhật ký đồng bộ" -> "file-text";
      case "Cấu hình" -> "settings";
      default -> "landmark";
    };
  }
}
