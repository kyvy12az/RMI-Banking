package vn.rmibank.common.ui;

import com.formdev.flatlaf.FlatLightLaf;
import java.awt.*;
import javax.swing.*;
import javax.swing.border.*;

/** Presentation tokens shared by Client, authentication, remote admin and local launcher. */
public final class BankTheme {
  private BankTheme() {}

  public static final Color NAVY = new Color(16, 43, 77),
      INK = new Color(16, 37, 67),
      TEAL = new Color(0, 137, 148),
      MUTED = new Color(96, 113, 137),
      LINE = new Color(218, 228, 239),
      BG = new Color(247, 250, 253),
      SUCCESS = new Color(17, 139, 74),
      ERROR = new Color(190, 53, 64);

  public static void setup() {
    FlatLightLaf.setup();
    Font font = new Font("SansSerif", Font.PLAIN, 16);
    for (String family : new String[] {"Segoe UI", "Noto Sans", "DejaVu Sans", "SansSerif"}) {
      Font candidate = new Font(family, Font.PLAIN, 16);
      if (candidate.getFamily().equalsIgnoreCase(family)
          && candidate.canDisplayUpTo("Nguyễn Kỳ Vỹ — Đăng nhập") < 0) {
        font = candidate;
        break;
      }
    }
    UIManager.put("defaultFont", font);
    UIManager.put("Component.focusColor", TEAL);
    UIManager.put("Component.accentColor", TEAL);
    UIManager.put("Component.arc", 12);
    UIManager.put("Component.borderColor", new Color(195, 208, 225));
    UIManager.put("TextComponent.arc", 12);
    UIManager.put("TextField.background", Color.WHITE);
    UIManager.put("PasswordField.background", Color.WHITE);
    UIManager.put("Button.arc", 12);
    UIManager.put("Button.default.background", TEAL);
    UIManager.put("Button.default.foreground", Color.WHITE);
    UIManager.put("ToggleButton.selectedBackground", TEAL);
    UIManager.put("ToggleButton.selectedForeground", Color.WHITE);
    UIManager.put("CheckBox.icon.selectedBackground", TEAL);
    UIManager.put("CheckBox.icon.selectedBorderColor", TEAL);
    UIManager.put("Table.rowHeight", 38);
    UIManager.put("TableHeader.height", 38);
    UIManager.put("TableHeader.background", new Color(235, 241, 248));
    UIManager.put("TableHeader.foreground", INK);
    UIManager.put("Table.selectionBackground", new Color(223, 244, 245));
    UIManager.put("Table.selectionForeground", INK);
    UIManager.put("Table.alternateRowColor", new Color(248, 250, 253));
    UIManager.put("TabbedPane.underlineColor", TEAL);
    UIManager.put("TabbedPane.selectedBackground", Color.WHITE);
    UIManager.put("TabbedPane.contentAreaColor", LINE);
    UIManager.put("ScrollBar.width", 10);
    UIManager.put("ScrollPane.border", BorderFactory.createEmptyBorder());
    UIManager.put("OptionPane.informationIcon", BankIcons.icon("info", 30, TEAL));
    UIManager.put("OptionPane.warningIcon", BankIcons.icon("info", 30, new Color(188, 125, 25)));
    UIManager.put("OptionPane.errorIcon", BankIcons.icon("x", 30, ERROR));
    UIManager.put("OptionPane.questionIcon", BankIcons.icon("info", 30, NAVY));
  }

  public static void styleButton(AbstractButton button, boolean primary) {
    button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    button.setMargin(new Insets(11, 16, 11, 16));
    button.setIconTextGap(10);
    button.setBackground(primary ? TEAL : new Color(234, 240, 247));
    button.setForeground(primary ? Color.WHITE : INK);
    button.setFont(button.getFont().deriveFont(Font.BOLD));
    button.putClientProperty("JButton.minimumWidth", 0);
    button.putClientProperty("JButton.minimumHeight", 42);
    button.putClientProperty(
        "FlatLaf.style",
        primary
            ? "background: #008994; foreground: #ffffff; borderWidth: 0; focusedBorderColor: #008994; hoverBackground: #007984; pressedBackground: #006b76"
            : "background: #eaf0f7; foreground: #102543; borderWidth: 0; hoverBackground: #dee8f3");
  }

  public static Border cardBorder() {
    return new AbstractBorder() {
      public Insets getBorderInsets(Component c) {
        return new Insets(1, 1, 1, 1);
      }

      public Insets getBorderInsets(Component c, Insets i) {
        i.set(1, 1, 1, 1);
        return i;
      }

      public void paintBorder(Component c, Graphics raw, int x, int y, int w, int h) {
        Graphics2D g = (Graphics2D) raw.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(LINE);
        g.drawRoundRect(x, y, w - 1, h - 1, 14, 14);
        g.dispose();
      }
    };
  }
}
