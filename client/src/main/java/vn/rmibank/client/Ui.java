package vn.rmibank.client;

import static vn.rmibank.common.ui.BankIcons.icon;

import java.awt.*;
import java.text.NumberFormat;
import java.util.Locale;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import net.miginfocom.swing.MigLayout;
import vn.rmibank.common.ui.BankTheme;

final class Ui {
  static final Color NAVY = BankTheme.NAVY,
      INK = BankTheme.INK,
      TEAL = BankTheme.TEAL,
      MUTED = BankTheme.MUTED,
      LINE = BankTheme.LINE,
      BG = BankTheme.BG;

  static void setup() {
    BankTheme.setup();
  }

  static JPanel panel(String constraints, String cols, String rows) {
    JPanel p =
        new ScrollPanel(
            new MigLayout(constraints, cols.replace("[grow,fill]", "[grow,fill,0:pref]"), rows));
    p.setBackground(Color.WHITE);
    return p;
  }

  static final class ScrollPanel extends JPanel implements Scrollable {
    ScrollPanel(LayoutManager layout) {
      super(layout);
    }

    public Dimension getPreferredScrollableViewportSize() {
      return getPreferredSize();
    }

    public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) {
      return 20;
    }

    public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) {
      return Math.max(20, r.height - 30);
    }

    public boolean getScrollableTracksViewportWidth() {
      return true;
    }

    public boolean getScrollableTracksViewportHeight() {
      return getParent() instanceof JViewport v && getPreferredSize().height < v.getHeight();
    }
  }

  static JLabel title(String text, int size) {
    JLabel l = new JLabel(text);
    l.setFont(l.getFont().deriveFont(Font.BOLD, (float) size));
    l.setForeground(INK);
    return l;
  }

  static JLabel label(String text) {
    JLabel l = new JLabel(text);
    l.setForeground(INK);
    return l;
  }

  static JButton button(String text, String icon, boolean primary) {
    JButton b = new JButton(text, icon(icon, 21, primary ? Color.WHITE : NAVY));
    BankTheme.styleButton(b, primary);
    return b;
  }

  static JPanel card(String heading, String icon) {
    JPanel p = panel("insets 20,wrap 1,gap 10", "[grow,fill]", "");
    p.setBorder(BankTheme.cardBorder());
    if (heading != null) {
      JLabel h = title(heading, 21);
      h.setIcon(icon(icon, 25, TEAL));
      h.setIconTextGap(12);
      p.add(h, "gapbottom 8");
    }
    return p;
  }

  static void styleNotice(JLabel label, boolean success) {
    label.setOpaque(true);
    label.setBackground(success ? new Color(233, 248, 240) : new Color(255, 240, 241));
    label.setBorder(
        BorderFactory.createCompoundBorder(
            BankTheme.cardBorder(), BorderFactory.createEmptyBorder(11, 14, 11, 14)));
    label.setIconTextGap(12);
  }

  static String wrapMessage(String text) {
    return "<html>"
        + text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")
        + "</html>";
  }

  static String accountIdentity(String name, long account) {
    String safe = name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    return "<html>"
        + safe
        + "<br><span style='font-size: 13px; font-weight: normal; color: #607189'>TK: "
        + account
        + "</span></html>";
  }

  static JTextField field() {
    JTextField f = new JTextField();
    f.setMargin(new Insets(9, 12, 9, 12));
    return f;
  }

  static JPanel password(JPasswordField f) {
    f.setMargin(new Insets(9, 12, 9, 12));
    JPanel p = panel("insets 0,gap 0", "[grow,fill][]", "[fill]");
    JButton show = button("", "eye", false);
    char echo = f.getEchoChar();
    show.setToolTipText("Ẩn / hiện mật khẩu");
    show.addActionListener(
        e -> {
          boolean reveal = f.getEchoChar() != 0;
          f.setEchoChar(reveal ? (char) 0 : echo);
          show.setIcon(icon(reveal ? "eye-off" : "eye", 20, NAVY));
          show.setToolTipText(reveal ? "Ẩn mật khẩu" : "Hiện mật khẩu");
          show.getAccessibleContext().setAccessibleName(show.getToolTipText());
        });
    p.add(f, "growx");
    p.add(show);
    return p;
  }

  static void form(JPanel p, String label, JComponent input) {
    p.add(title(label, 16));
    p.add(input, "growx,hmin 43");
  }

  static JTable table(String... cols) {
    JTable t =
        new JTable(
            new DefaultTableModel(cols, 0) {
              public boolean isCellEditable(int r, int c) {
                return false;
              }
            }) {
          @Override
          protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (getRowCount() == 0) {
              Graphics2D g = (Graphics2D) graphics.create();
              g.setColor(MUTED);
              String text = "Chưa có dữ liệu";
              g.drawString(
                  text,
                  Math.max(12, (getWidth() - g.getFontMetrics().stringWidth(text)) / 2),
                  Math.max(40, getHeight() / 2));
              g.dispose();
            }
          }
        };
    t.setPreferredScrollableViewportSize(new Dimension(450, 160));
    t.setFillsViewportHeight(true);
    t.setDefaultRenderer(
        Object.class,
        new DefaultTableCellRenderer() {
          @Override
          public Component getTableCellRendererComponent(
              JTable table, Object value, boolean selected, boolean focus, int row, int column) {
            JLabel cell =
                (JLabel)
                    super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            String text = value == null ? "" : value.toString();
            cell.setIcon(null);
            cell.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
            cell.setToolTipText(text);
            cell.setHorizontalAlignment(
                column > 0 && (value instanceof Number || text.endsWith(" đ"))
                    ? SwingConstants.RIGHT
                    : SwingConstants.LEFT);
            if (!selected) {
              cell.setForeground(INK);
              if (text.startsWith("+")) cell.setForeground(BankTheme.SUCCESS);
              else if (text.startsWith("−") || text.startsWith("-"))
                cell.setForeground(BankTheme.ERROR);
            }
            if (text.equals("COMMITTED")) {
              cell.setText("Thành công");
              if (!selected) cell.setForeground(BankTheme.SUCCESS);
            }
            if (text.equals("Đã khóa") && !selected) cell.setForeground(BankTheme.ERROR);
            return cell;
          }
        });
    t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    t.setShowHorizontalLines(true);
    t.setGridColor(LINE);
    t.getTableHeader().setFont(t.getFont().deriveFont(Font.BOLD));
    return t;
  }

  static void rows(JTable t, java.util.List<Object[]> data) {
    Object key = t.getSelectedRow() < 0 ? null : t.getValueAt(t.getSelectedRow(), 0);
    DefaultTableModel m = (DefaultTableModel) t.getModel();
    m.setRowCount(0);
    for (Object[] r : data) m.addRow(r);
    if (key != null)
      for (int i = 0; i < t.getRowCount(); i++)
        if (key.equals(t.getValueAt(i, 0))) t.setRowSelectionInterval(i, i);
  }

  static String money(java.math.BigDecimal n) {
    return NumberFormat.getIntegerInstance(Locale.forLanguageTag("vi-VN")).format(n) + " đ";
  }

  static JPanel brand() {
    JPanel p =
        new JPanel(
            new MigLayout(
                "insets 32,wrap 1,align center center", "[grow,center]", "[]20[]8[]52[]16[]12[]")) {
          protected void paintComponent(Graphics g) {
            Graphics2D x = (Graphics2D) g.create();
            x.setPaint(
                new GradientPaint(0, 0, NAVY, getWidth(), getHeight(), new Color(11, 36, 69)));
            x.fillRect(0, 0, getWidth(), getHeight());
            x.dispose();
          }
        };
    p.setPreferredSize(new Dimension(410, 700));
    p.add(new JLabel(icon("bank", 90, Color.WHITE)));
    JLabel h = title("RMI Banking", 38);
    h.setForeground(Color.WHITE);
    p.add(h);
    JLabel sub = label("Hệ thống ngân hàng RMI");
    sub.setFont(sub.getFont().deriveFont(22f));
    sub.setForeground(new Color(209, 223, 242));
    p.add(sub);
    JPanel servers = panel("insets 0,gap 22", "[][][]", "");
    servers.setOpaque(false);
    for (int i = 0; i < 3; i++)
      servers.add(new JLabel(icon("server", 64, new Color(150, 199, 237))));
    p.add(servers);
    p.add(new JLabel(icon("network", 30, new Color(150, 199, 237))));
    JLabel sync = label("Đồng bộ dữ liệu trên 3 server");
    sync.setForeground(Color.WHITE);
    p.add(sync);
    return p;
  }
}
