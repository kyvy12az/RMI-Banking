package vn.rmibank.server;

import static vn.rmibank.common.ui.BankIcons.icon;
import static vn.rmibank.common.ui.BankTheme.*;

import java.awt.*;
import javax.swing.*;
import net.miginfocom.swing.MigLayout;
import vn.rmibank.common.ui.BankTheme;

/** Local lifecycle controls; the remote administration console remains in Client. */
public final class Launcher extends JFrame {
  ServerMain.Runtime runtime;
  final JButton toggle = new JButton("Khởi động server");
  final JLabel status = new JLabel("Server đã dừng");
  final JLabel detail = new JLabel("Sẵn sàng khởi động dịch vụ RMI và kết nối CSDL.");
  final JProgressBar progress = new JProgressBar();
  boolean busy, closing;

  public Launcher(Config c) {
    super("RMI Banking — Server " + c.id());
    setIconImage(icon("database", 32, NAVY).getImage());
    setLayout(new BorderLayout());
    JPanel header = new JPanel(new MigLayout("insets 28,gap 18", "[][grow,fill]", "[]6[]"));
    header.setBackground(NAVY);
    JLabel mark = new JLabel(icon("database", 52, new Color(73, 200, 215)));
    header.add(mark, "spany 2");
    JLabel brand = new JLabel("RMI Banking");
    brand.setForeground(Color.WHITE);
    brand.setFont(brand.getFont().deriveFont(Font.BOLD, 27f));
    header.add(brand);
    JLabel role = new JLabel(c.get("server.role"));
    role.setForeground(new Color(158, 236, 227));
    role.setFont(role.getFont().deriveFont(Font.BOLD, 13f));
    header.add(role, "spany 2,aligny center,wrap");
    JLabel subtitle = new JLabel("Server Console • Máy chủ " + c.id());
    subtitle.setForeground(new Color(192, 211, 233));
    header.add(subtitle);
    add(header, BorderLayout.NORTH);
    JPanel content =
        new JPanel(new MigLayout("insets 24,wrap 1,gap 18", "[grow,fill,0:pref]", "[][grow,fill]"));
    content.setBackground(BG);
    JLabel heading =
        new JLabel("Quản lý dịch vụ cục bộ", icon("settings", 25, NAVY), SwingConstants.LEFT);
    heading.setIconTextGap(12);
    heading.setForeground(INK);
    heading.setFont(heading.getFont().deriveFont(Font.BOLD, 23f));
    content.add(heading);
    JPanel card =
        new JPanel(
            new MigLayout(
                "insets 22,wrap 2,gap 16 16", "[grow,fill,0:pref][grow,fill,0:pref]", "[][][]"));
    card.setBackground(Color.WHITE);
    card.setBorder(cardBorder());
    addInfo(card, "Máy chủ", c.get("server.host"), "server");
    addInfo(card, "Vai trò", c.get("server.role"), "database");
    addInfo(card, "RMI Registry", c.get("rmi.registry.port"), "network");
    addInfo(card, "Cổng dịch vụ RMI", c.get("rmi.service.port"), "wifi");
    status.setIcon(icon("circle", 20, MUTED));
    status.setIconTextGap(10);
    status.setForeground(MUTED);
    status.setFont(status.getFont().deriveFont(Font.BOLD, 18f));
    card.add(status, "span 2,gaptop 4");
    detail.setForeground(MUTED);
    detail.setFont(detail.getFont().deriveFont(14f));
    card.add(detail, "span 2");
    progress.setIndeterminate(true);
    progress.setVisible(false);
    card.add(progress, "span 2,growx,hidemode 3,h 5!");
    content.add(card, "grow");
    styleButton(toggle, true);
    toggle.setIcon(icon("server", 22, Color.WHITE));
    toggle.addActionListener(e -> change(c));
    JScrollPane viewport = new JScrollPane(content);
    viewport.setBorder(null);
    viewport.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    add(viewport, BorderLayout.CENTER);
    JLabel footer =
        new JLabel("Đồng bộ dữ liệu qua Java RMI", icon("network", 18, TEAL), SwingConstants.LEFT);
    footer.setIconTextGap(10);
    footer.setForeground(MUTED);
    footer.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
    JPanel actions =
        new JPanel(new MigLayout("insets 14 24 12 24,wrap 1,gap 8", "[grow,fill]", "[][]"));
    actions.setBackground(BG);
    actions.add(toggle, "h 50!");
    actions.add(footer);
    add(actions, BorderLayout.SOUTH);
    getContentPane().setBackground(BG);
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    addWindowListener(
        new java.awt.event.WindowAdapter() {
          public void windowClosing(java.awt.event.WindowEvent e) {
            if (!busy) {
              if (runtime == null) dispose();
              else {
                closing = true;
                change(c);
              }
            }
          }
        });
    setMinimumSize(new Dimension(640, 540));
    setSize(760, 720);
    setLocationRelativeTo(null);
    setVisible(true);
  }

  private void addInfo(JPanel panel, String label, String value, String glyph) {
    JPanel cell = new JPanel(new MigLayout("insets 0,wrap 1,gap 7", "[grow,fill,0:pref]", "[][]"));
    cell.setOpaque(false);
    JLabel caption = new JLabel(label, icon(glyph, 19, TEAL), SwingConstants.LEFT);
    caption.setForeground(MUTED);
    caption.setIconTextGap(8);
    caption.setFont(caption.getFont().deriveFont(14f));
    cell.add(caption);
    JLabel text = new JLabel(value);
    text.setForeground(INK);
    text.setFont(text.getFont().deriveFont(Font.BOLD, 19f));
    cell.add(text);
    panel.add(cell);
  }

  void change(Config c) {
    busy = true;
    toggle.setEnabled(false);
    progress.setVisible(true);
    status.setText("Đang xử lý…");
    status.setIcon(icon("refresh-cw", 20, TEAL));
    status.setForeground(TEAL);
    detail.setText(
        runtime == null
            ? "Đang khởi động dịch vụ RMI và kết nối CSDL…"
            : "Đang đóng worker, RMI và connection pool…");
    new SwingWorker<Void, Void>() {
      protected Void doInBackground() throws Exception {
        if (runtime == null) runtime = new ServerMain.Runtime(c);
        else {
          runtime.close();
          runtime = null;
        }
        return null;
      }

      protected void done() {
        try {
          get();
          boolean running = runtime != null;
          status.setText(running ? "Đang hoạt động" : "Server đã dừng");
          status.setIcon(icon(running ? "circle-check" : "circle", 20, running ? SUCCESS : MUTED));
          status.setForeground(running ? SUCCESS : MUTED);
          detail.setText(
              running
                  ? "Dịch vụ RMI và CSDL đã kết nối."
                  : "Sẵn sàng khởi động dịch vụ RMI và kết nối CSDL.");
        } catch (Exception e) {
          status.setText("Không thể thay đổi trạng thái server");
          status.setIcon(icon("x", 20, BankTheme.ERROR));
          status.setForeground(BankTheme.ERROR);
          String message = e.getCause() == null ? e.getMessage() : e.getCause().getMessage();
          detail.setText("<html>" + escape(message) + "</html>");
          closing = false;
        }
        busy = false;
        progress.setVisible(false);
        boolean running = runtime != null;
        toggle.setText(running ? "Dừng server" : "Khởi động server");
        toggle.setIcon(icon(running ? "square" : "server", 22, Color.WHITE));
        styleButton(toggle, true);
        if (running)
          toggle.putClientProperty(
              "FlatLaf.style",
              "background: #bd3540; foreground: #ffffff; borderWidth: 0; hoverBackground: #a32c35");
        toggle.setEnabled(true);
        if (closing && runtime == null) dispose();
      }
    }.execute();
  }

  private static String escape(String text) {
    return text == null
        ? "Kiểm tra cấu hình và nhật ký server."
        : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
