package vn.rmibank.client;

import static vn.rmibank.client.Ui.*;
import static vn.rmibank.common.ui.BankIcons.icon;

import java.awt.*;
import java.awt.event.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.rmi.registry.LocateRegistry;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.prefs.Preferences;
import javax.swing.*;
import vn.rmibank.common.*;
import vn.rmibank.common.Api.*;
import vn.rmibank.common.ui.BankIcons;
import vn.rmibank.common.ui.BankTheme;

public final class ClientMain extends JFrame {
  record Endpoint(String id, String host, int port) {
    public String toString() {
      return "Server " + id + " — " + host + ":" + port;
    }
  }

  final Properties config;
  final Preferences prefs = Preferences.userNodeForPackage(ClientMain.class);
  final JLabel status = label("Chọn máy chủ để kết nối"), notice = label(" ");
  JPanel center, work, sidebar;
  JComboBox<Endpoint> endpoints;
  Bank bank;
  Login login;
  Account account;
  Endpoint connected;
  int epoch;
  boolean refreshing;
  javax.swing.Timer timer;
  String view = "Chuyển khoản";
  JTable recent, history, accounts, transfers;
  JTextField receiver, amount, note, requestId, search, from, to, adminSearch;
  JLabel owner, balance, recipientName, pageLabel, adminMeta, sourceAccount;
  JTextArea logArea, configArea;
  JPanel nodeCards;
  JButton send, lookup;
  int page;
  TransferRequest pending;
  Snapshot snapshot;

  public ClientMain(Properties config) {
    super("RMI Banking — Đăng nhập");
    this.config = config;
    setIconImage(icon("landmark", 32, NAVY).getImage());
    setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
    addWindowListener(
        new WindowAdapter() {
          public void windowClosing(WindowEvent e) {
            leave();
            dispose();
          }
        });
    setMinimumSize(new Dimension(1040, 750));
    setSize(1320, 900);
    setLocationRelativeTo(null);
    showAuth(false);
  }

  <T> void async(Callable<T> job, Consumer<T> ok, Consumer<Throwable> fail) {
    int generation = epoch;
    new SwingWorker<T, Void>() {
      protected T doInBackground() throws Exception {
        return job.call();
      }

      protected void done() {
        if (generation != epoch) return;
        try {
          ok.accept(get());
        } catch (Exception e) {
          Throwable t = e.getCause() == null ? e : e.getCause();
          fail.accept(t);
        }
      }
    }.execute();
  }

  void error(Throwable e) {
    notice.setVisible(true);
    notice.setForeground(BankTheme.ERROR);
    notice.setIcon(icon("x", 22, BankTheme.ERROR));
    styleNotice(notice, false);
    notice.setText(
        wrapMessage(e.getMessage() == null ? "Không thể kết nối máy chủ" : e.getMessage()));
    status.setText("Cần kiểm tra kết nối");
    status.setForeground(BankTheme.ERROR);
    status.setIcon(icon("info", 18, BankTheme.ERROR));
  }

  void success(String text) {
    notice.setVisible(true);
    notice.setForeground(BankTheme.SUCCESS);
    notice.setIcon(icon("circle-check", 22, BankTheme.SUCCESS));
    styleNotice(notice, true);
    notice.setText(wrapMessage(text));
  }

  Endpoint[] nodes() {
    return Arrays.stream(config.getProperty("servers", "1,2,3").split(","))
        .map(String::trim)
        .map(
            id ->
                new Endpoint(
                    id,
                    config.getProperty("node." + id + ".host"),
                    Integer.parseInt(config.getProperty("node." + id + ".port"))))
        .toArray(Endpoint[]::new);
  }

  Bank connect(Endpoint e) throws Exception {
    return (Bank)
        LocateRegistry.getRegistry(
                e.host(),
                e.port(),
                new Net.Sockets(Integer.parseInt(config.getProperty("timeout.ms", "4000"))))
            .lookup("Bank");
  }

  void base() {
    getContentPane().removeAll();
    setLayout(new BorderLayout());
    center = panel("insets 0", "[grow,fill]", "[grow,fill]");
    add(center, BorderLayout.CENTER);
    // JPanel foot = panel("insets 10 22", "[grow,fill][]", "");
    // foot.setBackground(new Color(237, 243, 249));
    // status.setForeground(MUTED);
    // foot.add(status);
    // foot.add(label("Java RMI • Banking"));
    // add(foot, BorderLayout.SOUTH);
  }

  void showAuth(boolean register) {
    epoch++;
    base();
    setTitle("RMI Banking — " + (register ? "Đăng ký" : "Đăng nhập"));
    center.setLayout(new BorderLayout());
    center.add(brand(), BorderLayout.WEST);
    JPanel right = panel("insets 20 55,wrap 1,aligny center,gap 8", "[grow,fill]", "");
    center.add(
        new JScrollPane(
            right,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER),
        BorderLayout.CENTER);
    JLabel authHeading = title(register ? "Đăng ký tài khoản" : "Đăng nhập", 34);
    authHeading.setIcon(icon(register ? "user-plus" : "arrow-right-left", 36, NAVY));
    authHeading.setIconTextGap(16);
    authHeading.setHorizontalAlignment(SwingConstants.CENTER);
    right.add(authHeading);
    JLabel subtitle =
        label(register ? "Tạo tài khoản để bắt đầu giao dịch" : "Chào mừng bạn quay trở lại");
    subtitle.setForeground(MUTED);
    subtitle.setHorizontalAlignment(SwingConstants.CENTER);
    subtitle.setFont(subtitle.getFont().deriveFont(20f));
    right.add(subtitle, "alignx center,gapbottom 10");
    JToggleButton customer = new JToggleButton("Khách hàng", true),
        admin = new JToggleButton("Quản trị viên");
    ButtonGroup roles = new ButtonGroup();
    roles.add(customer);
    roles.add(admin);
    JPanel tabs = panel("insets 0", "[grow,fill][grow,fill]", "");
    tabs.add(customer, "h 46!");
    tabs.add(admin, "h 46!");
    Runnable roleStyle =
        () -> {
          BankTheme.styleButton(customer, customer.isSelected());
          BankTheme.styleButton(admin, admin.isSelected());
          customer.setIcon(icon("user", 22, customer.isSelected() ? Color.WHITE : NAVY));
          admin.setIcon(icon("settings", 22, admin.isSelected() ? Color.WHITE : NAVY));
        };
    customer.addActionListener(e -> roleStyle.run());
    admin.addActionListener(e -> roleStyle.run());
    roleStyle.run();
    if (!register) right.add(tabs);
    JTextField fullName = field(), username = field(), phone = field();
    JPasswordField password = new JPasswordField(), confirm = new JPasswordField();
    if (register) {
      form(right, "Họ và tên", fullName);
      JPanel fields = panel("insets 0,wrap 2,gap 18 10", "[grow,fill][grow,fill]", "");
      fields.add(title("Tên đăng nhập", 16));
      fields.add(title("Số điện thoại", 16));
      fields.add(username, "hmin 43");
      fields.add(phone, "hmin 43");
      fields.add(title("Mật khẩu", 16));
      fields.add(title("Xác nhận mật khẩu", 16));
      fields.add(password(password));
      fields.add(password(confirm));
      right.add(fields);
      right.add(label("Tối thiểu 8 ký tự; tối đa 72 byte UTF-8"));
    } else {
      form(right, "Tên đăng nhập", username);
      form(right, "Mật khẩu", password(password));
    }
    JCheckBox remember =
        new JCheckBox("Ghi nhớ tên đăng nhập và máy chủ", prefs.getBoolean("remember", false));
    remember.setOpaque(false);
    if (!register) {
      right.add(remember);
      if (remember.isSelected()) username.setText(prefs.get("username", ""));
    }
    endpoints = new JComboBox<>(nodes());
    String saved = prefs.get("server", "");
    for (int i = 0; i < endpoints.getItemCount(); i++)
      if (endpoints.getItemAt(i).id().equals(saved)) endpoints.setSelectedIndex(i);
    form(right, "Máy chủ kết nối", endpoints);
    JLabel availability = label("Đang kiểm tra máy chủ…");
    availability.setIcon(icon("refresh-cw", 18, MUTED));
    availability.setIconTextGap(10);
    availability.setForeground(MUTED);
    right.add(availability);
    Runnable check =
        () -> {
          Endpoint selected = (Endpoint) endpoints.getSelectedItem();
          availability.setText("Đang kiểm tra máy chủ…");
          async(
              () -> connect(selected).ping(),
              id -> {
                if (endpoints.getSelectedItem().equals(selected)) {
                  availability.setText("Máy chủ " + id + " sẵn sàng");
                  availability.setForeground(BankTheme.SUCCESS);
                  availability.setIcon(icon("circle", 14, BankTheme.SUCCESS));
                }
              },
              e -> {
                if (endpoints.getSelectedItem().equals(selected)) {
                  availability.setText("Máy chủ chưa sẵn sàng");
                  availability.setForeground(BankTheme.ERROR);
                  availability.setIcon(icon("x", 18, BankTheme.ERROR));
                }
              });
        };
    endpoints.addActionListener(e -> check.run());
    check.run();
    if (register) {
      JLabel info = label("Số tài khoản được cấp tự động. Số dư ban đầu: 0 đ.");
      info.setForeground(TEAL);
      info.setIcon(icon("info", 22, TEAL));
      info.setIconTextGap(12);
      info.setOpaque(true);
      info.setBackground(new Color(233, 245, 255));
      info.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
      right.add(info, "gaptop 5");
    }
    JButton submit =
        button(register ? "Tạo tài khoản" : "Đăng nhập", register ? "user-plus" : "transfer", true);
    right.add(submit, "h 54!,gaptop 5");
    right.add(notice, "hidemode 3");
    notice.setText(" ");
    notice.setVisible(false);
    JButton switcher =
        new JButton(register ? "Đã có tài khoản?  Đăng nhập" : "Chưa có tài khoản?  Đăng ký ngay");
    switcher.setBorderPainted(false);
    switcher.setContentAreaFilled(false);
    switcher.setForeground(TEAL);
    right.add(switcher, "alignx center");
    switcher.addActionListener(e -> showAuth(!register));
    submit.addActionListener(
        e -> {
          String u = username.getText(),
              p = new String(password.getPassword()),
              name = fullName.getText(),
              tel = phone.getText();
          Endpoint endpoint = (Endpoint) endpoints.getSelectedItem();
          Role role = admin.isSelected() ? Role.ADMIN : Role.CUSTOMER;
          if (register && !Arrays.equals(password.getPassword(), confirm.getPassword())) {
            error(new Exception("Xác nhận mật khẩu không khớp"));
            return;
          }
          submit.setEnabled(false);
          status.setText("Đang xử lý…");
          notice.setText(" ");
          notice.setVisible(false);
          boolean rem = remember.isSelected();
          async(
              () -> {
                Bank remote = connect(endpoint);
                if (register)
                  return new Object[] {remote, remote.register(new Registration(name, u, tel, p))};
                return new Object[] {remote, remote.login(u, p, role)};
              },
              result -> {
                password.setText("");
                confirm.setText("");
                if (register) {
                  showAuth(false);
                  success("Đã tạo tài khoản " + ((Account) result[1]).id() + ". Mời đăng nhập.");
                  return;
                }
                prefs.putBoolean("remember", rem);
                if (rem) {
                  prefs.put("username", u);
                  prefs.put("server", endpoint.id());
                } else {
                  prefs.remove("username");
                  prefs.remove("server");
                }
                bank = (Bank) result[0];
                login = (Login) result[1];
                connected = endpoint;
                openWorkspace();
              },
              failure -> {
                submit.setEnabled(true);
                error(failure);
              });
        });
    getRootPane().setDefaultButton(submit);
    revalidate();
    repaint();
  }

  void openWorkspace() {
    getRootPane().setDefaultButton(null);
    epoch++;
    base();
    setTitle(
        "RMI Banking — "
            + (login.role() == Role.ADMIN ? "Quản trị Server " + connected.id() : "Client"));
    center.setLayout(new BorderLayout());
    sidebar = panel("insets 28 14,wrap 1,gap 14", "[grow,fill]", "");
    sidebar.setBackground(NAVY);
    sidebar.setPreferredSize(new Dimension(240, 600));
    JLabel logo = title("RMI Banking", 24);
    logo.setForeground(Color.WHITE);
    logo.setIcon(icon(login.role() == Role.ADMIN ? "database" : "landmark", 40, Color.WHITE));
    sidebar.add(logo, "gapbottom 4");
    JLabel sub = label(login.role() == Role.ADMIN ? "Server Console" : "Client");
    sub.setForeground(new Color(185, 207, 232));
    sidebar.add(sub, "gapbottom 30");
    String[] menu =
        login.role() == Role.ADMIN
            ? new String[] {"Tổng quan", "Tài khoản", "Giao dịch", "Nhật ký đồng bộ", "Cấu hình"}
            : new String[] {"Tổng quan", "Chuyển khoản", "Lịch sử giao dịch"};
    for (String name : menu) {
      JButton b = button(name, BankIcons.navigation(name), false);
      b.setHorizontalAlignment(SwingConstants.LEFT);
      b.setBackground(NAVY);
      b.setForeground(Color.WHITE);
      b.addActionListener(
          e -> {
            view = name;
            renderView();
          });
      b.setBorderPainted(false);
      b.setIcon(icon(BankIcons.navigation(name), 22, Color.WHITE));
      b.putClientProperty(
          "FlatLaf.style",
          "borderWidth: 0; background: #102b4d; foreground: #ffffff; hoverBackground: #21476d");
      sidebar.add(b, "hmin 48");
    }
    JButton out = button("Đăng xuất", "log-out", false);
    out.setBackground(NAVY);
    out.setIcon(icon("log-out", 22, Color.WHITE));
    out.putClientProperty(
        "FlatLaf.style",
        "borderWidth: 0; background: #102b4d; foreground: #ffffff; hoverBackground: #21476d");
    out.setForeground(Color.WHITE);
    out.addActionListener(
        e -> {
          leave();
          showAuth(false);
        });
    sidebar.add(out, "gaptop 28");
    center.add(sidebar, BorderLayout.WEST);
    work = panel("insets 24,wrap 1,gap 16", "[grow,fill]", "[][grow,fill][]");
    work.setBackground(BG);
    JScrollPane viewport = new JScrollPane(work);
    viewport.setBorder(null);
    viewport.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    center.add(viewport, BorderLayout.CENTER);
    notice.setText(" ");
    notice.setVisible(false);
    view = login.role() == Role.ADMIN ? "Tổng quan" : "Chuyển khoản";
    renderView();
    timer = new javax.swing.Timer(2500, e -> refresh());
    timer.start();
    refresh();
  }

  void renderView() {
    work.removeAll();
    for (Component component : sidebar.getComponents())
      if (component instanceof JButton b) {
        boolean selected = b.getText().equals(view);
        b.putClientProperty(
            "FlatLaf.style",
            selected
                ? "borderWidth: 0; background: #245878; foreground: #ffffff; hoverBackground: #2a6687"
                : "borderWidth: 0; background: #102b4d; foreground: #ffffff; hoverBackground: #21476d");
      }
    JPanel heading = panel("insets 0,gap 14", "[grow,fill,0:pref][190!][46!]", "");
    heading.setOpaque(false);
    JLabel viewHeading = title(login.role() == Role.ADMIN ? "Quản lý đồng bộ CSDL" : view, 30);
    viewHeading.setIcon(
        icon(login.role() == Role.ADMIN ? "database" : BankIcons.navigation(view), 32, NAVY));
    viewHeading.setIconTextGap(14);
    heading.add(viewHeading);
    status.setOpaque(true);
    status.setBackground(new Color(233, 246, 245));
    status.setBorder(BorderFactory.createEmptyBorder(9, 12, 9, 12));
    status.setText("Server " + connected.id() + " • Kết nối…");
    status.setFont(status.getFont().deriveFont(13f));
    status.setForeground(TEAL);
    status.setIcon(icon("wifi", 18, TEAL));
    status.setIconTextGap(8);
    heading.add(status);
    JButton refresh = button("", "refresh", false);
    refresh.setToolTipText("Làm mới dữ liệu");
    refresh.getAccessibleContext().setAccessibleName("Làm mới dữ liệu");
    refresh.addActionListener(e -> refresh());
    heading.add(refresh);
    work.add(heading);
    if (login.role() == Role.ADMIN) adminView();
    else if (view.equals("Lịch sử giao dịch")) historyView();
    else customerView();
    work.add(notice, "hmin 42,hidemode 3");
    work.revalidate();
    work.repaint();
    refresh();
  }

  void customerView() {
    JPanel body = panel("insets 0,gap 16", "[grow,fill][grow,fill]", "[grow,fill]");
    body.setOpaque(false);
    JPanel left = panel("insets 0,wrap 1,gap 16", "[grow,fill]", "[][grow,fill]");
    left.setOpaque(false);
    JPanel info = card("Thông tin tài khoản", "user");
    owner =
        title(
            account == null ? "Đang tải tài khoản…" : accountIdentity(account.name(), account.id()),
            22);
    owner.setIcon(icon("circle-user-round", 58, new Color(113, 164, 220)));
    owner.setIconTextGap(16);
    info.add(owner);
    info.add(label("Số dư khả dụng"));
    balance = title(account == null ? "—" : money(account.balance()), 38);
    balance.setForeground(TEAL);
    info.add(balance, "gaptop 10,gapbottom 15");
    left.add(info);
    JPanel r = card("Giao dịch gần đây", "clock");
    recent = table("Mã GD", "Nội dung", "Số tiền", "Trạng thái");
    r.add(new JScrollPane(recent), "grow,hmin 180");
    left.add(r, "grow");
    body.add(left, "grow");
    if (view.equals("Tổng quan")) {
      JPanel summary = card("Chào mừng trở lại", "bank");
      summary.add(title(login.name(), 28));
      summary.add(label("Giao dịch được xử lý tại PRIMARY."));
      summary.add(label("Dữ liệu được đồng bộ đến hai replica qua RMI."));
      JButton go = button("Thực hiện chuyển khoản", "transfer", true);
      go.addActionListener(
          e -> {
            view = "Chuyển khoản";
            renderView();
          });
      summary.add(go);
      body.add(summary, "grow");
    } else {
      JPanel form = card("Thực hiện chuyển khoản", "send");
      sourceAccount =
          label(
              "Tài khoản nguồn: "
                  + (account == null ? "đang tải" : account.id() + " — " + account.name()));
      form.add(sourceAccount);
      receiver = field();
      receiver.putClientProperty("JTextField.placeholderText", "Nhập số tài khoản nhận");
      amount = field();
      amount.putClientProperty("JTextField.placeholderText", "Ví dụ: 200000");
      amount.setToolTipText("Nhập số nguyên, ví dụ 200000");
      note = field();
      note.setText("Chuyển tiền");
      recipientName = label("Tra cứu người nhận trước khi chuyển");
      JPanel dest = panel("insets 0", "[grow,fill][]", "");
      dest.add(receiver);
      JButton find = button("", "search", false);
      dest.add(find);
      form(form, "Tài khoản nhận", dest);
      form.add(recipientName);
      form(form, "Số tiền (đ)", amount);
      form(form, "Nội dung", note);
      requestId = field();
      requestId.setEditable(false);
      requestId.setToolTipText("Mã yêu cầu chưa xác định kết quả");
      requestId.setVisible(false);
      form.add(requestId, "hidemode 3");
      send = button("Chuyển khoản", "transfer", true);
      lookup = button("Tra cứu kết quả", "search", false);
      JPanel buttons = panel("insets 0,gap 12", "[grow,fill][grow,fill]", "");
      buttons.add(send, "h 50!");
      form.add(buttons, "gaptop 10");
      lookup.setVisible(false);
      form.add(lookup, "hidemode 3");
      JButton clear = button("Làm mới", "refresh", false);
      buttons.add(clear, "h 50!");
      find.addActionListener(
          e -> {
            try {
              long id = Long.parseLong(receiver.getText().strip());
              Bank remote = bank;
              String token = login.token();
              async(
                  () -> remote.recipient(token, id),
                  v -> recipientName.setText(v.name()),
                  this::error);
            } catch (Exception ex) {
              error(new Exception("Nhập số tài khoản hợp lệ"));
            }
          });
      send.addActionListener(e -> sendTransfer());
      lookup.addActionListener(e -> lookupPending());
      clear.addActionListener(
          e -> {
            if (pending != null) {
              error(
                  new Exception(
                      "Yêu cầu chưa rõ kết quả. Tra cứu hoặc gửi lại cùng requestId trước."));
              return;
            }
            receiver.setText("");
            amount.setText("");
            note.setText("Chuyển tiền");
            requestId.setText("");
          });
      loadPending();
      body.add(form, "grow");
    }
    work.add(body, "grow");
  }

  String pendingKey() {
    return "pending."
        + config.getProperty("cluster.id", "default")
        + "."
        + (account == null ? login.name().hashCode() : account.id());
  }

  void loadPending() {
    if (account == null) return;
    if (pending == null) {
      String raw = prefs.get(pendingKey(), "");
      if (!raw.isEmpty())
        try {
          String[] s = raw.split("\\|", 4);
          pending =
              new TransferRequest(
                  s[0],
                  Long.parseLong(s[1]),
                  new BigDecimal(s[2]),
                  new String(
                      Base64.getDecoder().decode(s[3]), java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }
    if (pending != null && receiver != null) {
      receiver.setText("" + pending.recipient());
      amount.setText(pending.amount().toPlainString());
      note.setText(pending.note());
      requestId.setText(pending.requestId());
      freeze(true);
      success("Có yêu cầu chưa xác định kết quả. Hãy tra cứu hoặc thử lại.");
    }
  }

  void freeze(boolean b) {
    receiver.setEditable(!b);
    amount.setEditable(!b);
    note.setEditable(!b);
    requestId.setVisible(b);
    lookup.setVisible(b);
    work.revalidate();
  }

  void sendTransfer() {
    boolean retrying = pending != null;
    if (account == null) return;
    try {
      if (pending == null) {
        if (!amount.getText().strip().matches("[0-9]{1,10}"))
          throw new Exception("Số tiền phải là số nguyên, không có dấu phân cách");
        BigDecimal value = new BigDecimal(amount.getText().strip());
        long dest = Long.parseLong(receiver.getText().strip());
        if (value.signum() <= 0
            || value.compareTo(new BigDecimal("1000000000")) > 0
            || value.compareTo(account.balance()) > 0
            || dest == account.id())
          throw new Exception("Kiểm tra số tiền, số dư và tài khoản nhận");
        pending = new TransferRequest(UUID.randomUUID().toString(), dest, value, note.getText());
        if (JOptionPane.showConfirmDialog(
                this,
                "Chuyển " + money(value) + " đến " + dest + "?",
                "Xác nhận giao dịch",
                JOptionPane.OK_CANCEL_OPTION)
            != JOptionPane.OK_OPTION) {
          pending = null;
          return;
        }
        prefs.put(
            pendingKey(),
            pending.requestId()
                + "|"
                + dest
                + "|"
                + value.toPlainString()
                + "|"
                + Base64.getEncoder()
                    .encodeToString(
                        pending.note().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        requestId.setText(pending.requestId());
        freeze(true);
      }
      send.setEnabled(false);
      TransferRequest r = pending;
      Bank remote = bank;
      String token = login.token();
      status.setText("Đang gửi giao dịch…");
      async(
          () -> remote.transfer(token, r),
          this::committed,
          e -> {
            send.setEnabled(true);
            if (e instanceof BankException rejected && rejected.definitive() && !retrying) {
              prefs.remove(pendingKey());
              pending = null;
              freeze(false);
              error(e);
            } else {
              error(
                  new Exception(
                      "Chưa rõ kết quả do mất kết nối. Giữ requestId, tra cứu hoặc gửi lại."));
            }
          });
    } catch (Exception ex) {
      error(ex);
    }
  }

  void committed(Transfer t) {
    prefs.remove(pendingKey());
    pending = null;
    if (send != null) {
      send.setEnabled(true);
      freeze(false);
    }
    success("Giao dịch " + t.id() + " đã commit tại PRIMARY. Đồng bộ replica được xử lý riêng.");
    refresh();
  }

  void lookupPending() {
    if (pending == null) {
      error(new Exception("Chưa có yêu cầu cần tra cứu"));
      return;
    }
    Bank remote = bank;
    String token = login.token(), id = pending.requestId();
    async(
        () -> remote.findRequest(token, id),
        t -> {
          if (t == null) success("PRIMARY chưa ghi nhận requestId. Có thể thử lại cùng yêu cầu.");
          else committed(t);
        },
        this::error);
  }

  void historyView() {
    JPanel p = card("Lịch sử giao dịch", "history");
    JPanel filters = panel("insets 0", "[grow,fill][130!][130!][]", "");
    search = field();
    search.putClientProperty("JTextField.placeholderText", "Tìm mã giao dịch hoặc nội dung");
    search.setToolTipText("Tìm theo mã giao dịch hoặc nội dung");
    from = field();
    from.putClientProperty("JTextField.placeholderText", "Từ ngày");
    from.setToolTipText("Từ ngày UTC: yyyy-MM-dd");
    to = field();
    to.putClientProperty("JTextField.placeholderText", "Đến ngày");
    to.setToolTipText("Đến ngày UTC: yyyy-MM-dd");
    JButton apply = button("Lọc", "search", false);
    filters.add(search);
    filters.add(from);
    filters.add(to);
    filters.add(apply);
    p.add(filters);
    p.add(label("Ngày theo UTC, định dạng yyyy-MM-dd; để trống để xem tất cả."));
    history =
        table(
            "Mã GD", "Thời gian UTC", "Gửi", "Nhận", "Nội dung", "Số tiền", "Chiều", "Trạng thái");
    p.add(new JScrollPane(history), "grow,hmin 300");
    JPanel pages = panel("insets 0", "[][grow,center][]", "");
    JButton prev = button("Trước", "arrow-right-left", false),
        next = button("Sau", "arrow-right-left", false);
    pageLabel = label("Trang 1");
    pages.add(prev);
    pages.add(pageLabel);
    pages.add(next);
    p.add(pages);
    apply.addActionListener(
        e -> {
          page = 0;
          refresh();
        });
    prev.addActionListener(
        e -> {
          if (page > 0) {
            page--;
            refresh();
          }
        });
    next.addActionListener(
        e -> {
          if (account != null) {
            page++;
            refresh();
          }
        });
    work.add(p, "grow");
  }

  void adminView() {
    JPanel content = panel("insets 0,wrap 1,gap 14", "[grow,fill]", "[][grow,fill]");
    content.setOpaque(false);
    nodeCards = panel("insets 0,gap 12", "[grow,fill][grow,fill][grow,fill]", "");
    nodeCards.setOpaque(false);
    content.add(nodeCards);
    JPanel tables = card(null, null);
    adminMeta = label("Đang tải trạng thái server…");
    tables.add(adminMeta);
    JPanel tools = panel("insets 0", "[grow,fill][][][]", "");
    adminSearch = field();
    adminSearch.putClientProperty("JTextField.placeholderText", "Tìm tài khoản hoặc giao dịch");
    JButton filter = button("Tìm", "search", false),
        resync = button("Đồng bộ lại", "refresh", true),
        lock = button("Khóa / Mở TK", "settings", false);
    tools.add(adminSearch);
    tools.add(filter);
    tools.add(resync);
    tools.add(lock);
    tables.add(tools);
    accounts = table("Số tài khoản", "Chủ tài khoản", "Số dư", "Phiên bản", "Trạng thái");
    transfers = table("Mã GD", "Thời gian UTC", "Gửi", "Nhận", "Số tiền", "Nội dung", "Trạng thái");
    logArea = new JTextArea();
    logArea.setEditable(false);
    logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
    JTabbedPane tabs = new JTabbedPane();
    tabs.addTab("Tài khoản", icon("users", 19, TEAL), new JScrollPane(accounts));
    tabs.addTab("Giao dịch", icon("arrow-right-left", 19, TEAL), new JScrollPane(transfers));
    tabs.addTab("Nhật ký đồng bộ", icon("file-text", 19, TEAL), new JScrollPane(logArea));
    configArea = new JTextArea();
    configArea.setEditable(false);
    configArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
    tabs.addTab("Cấu hình", icon("settings", 19, TEAL), new JScrollPane(configArea));
    if (view.equals("Giao dịch")) tabs.setSelectedIndex(1);
    if (view.equals("Nhật ký đồng bộ")) tabs.setSelectedIndex(2);
    if (view.equals("Cấu hình")) tabs.setSelectedIndex(3);
    tables.add(tabs, "grow,hmin 260");
    JPanel actions = panel("insets 0", "[][][grow,fill]", "");
    JButton details = button("Chi tiết đồng bộ GD", "network", false),
        export = button("Xuất nhật ký", "file-text", false);
    actions.add(details);
    actions.add(export);
    tables.add(actions);
    content.add(tables, "grow");
    work.add(content, "grow");
    filter.addActionListener(e -> applySnapshot());
    resync.addActionListener(
        e -> {
          Bank remote = bank;
          String token = login.token();
          async(
              () -> {
                remote.resync(token);
                return true;
              },
              v -> success("Đã yêu cầu gửi bù các sự kiện thiếu."),
              this::error);
        });
    lock.addActionListener(
        e -> {
          int row = accounts.getSelectedRow();
          if (row < 0) return;
          long id = Long.parseLong(accounts.getValueAt(row, 0).toString());
          Account a =
              snapshot.accounts().stream().filter(x -> x.id() == id).findFirst().orElseThrow();
          Bank remote = bank;
          String token = login.token();
          async(
              () -> {
                remote.setActive(token, id, !a.active());
                return true;
              },
              v -> {
                success("Đã cập nhật trạng thái tài khoản tại PRIMARY.");
                refresh();
              },
              this::error);
        });
    details.addActionListener(
        e -> {
          int row = transfers.getSelectedRow();
          if (row < 0) {
            error(new Exception("Chọn giao dịch trong thẻ Giao dịch"));
            return;
          }
          String id = transfers.getValueAt(row, 0).toString();
          Bank remote = bank;
          String token = login.token();
          async(
              () -> remote.deliveries(token, id),
              d -> {
                StringBuilder b = new StringBuilder("PRIMARY: COMMITTED\n");
                for (Delivery x : d)
                  b.append(x.node())
                      .append(": ")
                      .append(x.acknowledged() ? "Đã xác nhận" : "Đang chờ")
                      .append(" • sequence ")
                      .append(x.sequence())
                      .append(" • thử ")
                      .append(x.attempts())
                      .append("\n");
                JOptionPane.showMessageDialog(
                    this, b.toString(), id, JOptionPane.INFORMATION_MESSAGE);
              },
              this::error);
        });
    export.addActionListener(
        e -> {
          JFileChooser chooser = new JFileChooser();
          if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            Path file = chooser.getSelectedFile().toPath();
            String logs = logArea.getText();
            async(
                () -> {
                  Files.writeString(file, logs);
                  return true;
                },
                v -> success("Đã xuất nhật ký: " + file),
                this::error);
          }
        });
    if (snapshot != null) applySnapshot();
  }

  void applySnapshot() {
    if (snapshot == null || nodeCards == null) return;
    StringBuilder settings = new StringBuilder("Cấu hình vận hành (chỉ xem)\n\n");
    new TreeMap<>(snapshot.settings())
        .forEach((key, value) -> settings.append(key).append(" = ").append(value).append('\n'));
    configArea.setText(settings.toString());
    nodeCards.removeAll();
    for (Node n : snapshot.nodes()) {
      JPanel c = card("Server " + n.id(), "server");
      c.add(label(n.role() + " • " + n.host() + ":" + n.port()));
      JLabel state = label(n.online() ? "Trực tuyến" : "Ngoại tuyến");
      state.setForeground(n.online() ? new Color(0, 135, 70) : new Color(180, 35, 45));
      state.setIcon(
          icon(
              n.online() ? "circle-check" : "x",
              18,
              n.online() ? BankTheme.SUCCESS : BankTheme.ERROR));
      state.setIconTextGap(8);
      c.add(state);
      c.add(label("Sequence " + n.sequence() + " • Chờ " + n.pending() + " sự kiện"));
      c.add(label("<html>" + n.detail().replace(" • ", "<br>") + "</html>"));
      nodeCards.add(c, "grow");
    }
    Node n = snapshot.local();
    adminMeta.setText(
        "CSDL cục bộ Server "
            + n.id()
            + " • "
            + n.role()
            + " • sequence "
            + n.sequence()
            + " • "
            + n.detail());
    String q = adminSearch.getText().strip().toLowerCase(Locale.ROOT);
    rows(
        accounts,
        snapshot.accounts().stream()
            .filter(a -> (a.id() + " " + a.name()).toLowerCase(Locale.ROOT).contains(q))
            .map(
                a ->
                    new Object[] {
                      a.id(),
                      a.name(),
                      money(a.balance()),
                      a.version(),
                      a.active() ? "Hoạt động" : "Đã khóa"
                    })
            .toList());
    rows(
        transfers,
        snapshot.transfers().stream()
            .filter(
                t ->
                    (t.id() + " " + t.note() + " " + t.sender() + " " + t.recipient())
                        .toLowerCase(Locale.ROOT)
                        .contains(q))
            .map(
                t ->
                    new Object[] {
                      t.id(),
                      t.time(),
                      t.sender(),
                      t.recipient(),
                      money(t.amount()),
                      t.note(),
                      t.status()
                    })
            .toList());
    int caret = logArea.getCaretPosition();
    logArea.setText(String.join("\n", snapshot.logs()));
    logArea.setCaretPosition(Math.min(caret, logArea.getDocument().getLength()));
    nodeCards.revalidate();
    nodeCards.repaint();
  }

  void refresh() {
    if (login == null || refreshing) return;
    refreshing = true;
    Bank remote = bank;
    String token = login.token();
    boolean admin = login.role() == Role.ADMIN;
    String current = view;
    String q = search == null ? "" : search.getText(),
        f = from == null ? "" : from.getText(),
        t = to == null ? "" : to.getText();
    int requestedPage = page;
    async(
        () -> {
          if (admin) return remote.snapshot(token);
          Account a = remote.me(token);
          History h =
              remote.history(
                  token,
                  current.equals("Lịch sử giao dịch") ? q : "",
                  current.equals("Lịch sử giao dịch") ? f : "",
                  current.equals("Lịch sử giao dịch") ? t : "",
                  current.equals("Lịch sử giao dịch") ? requestedPage : 0,
                  current.equals("Lịch sử giao dịch") ? 20 : 5);
          return new Object[] {a, h};
        },
        data -> {
          refreshing = false;
          status.setText("Server " + connected.id() + " • Đã kết nối");
          status.setToolTipText("Máy chủ " + connected.host() + ":" + connected.port());
          status.setIcon(icon("wifi", 18, TEAL));
          status.setForeground(TEAL);
          if (admin) {
            snapshot = (Snapshot) data;
            applySnapshot();
            return;
          }
          Object[] values = (Object[]) data;
          account = (Account) values[0];
          History h = (History) values[1];
          if (!current.equals(view)) return;
          if (view.equals("Lịch sử giao dịch")) {
            rows(
                history,
                h.rows().stream()
                    .map(
                        x ->
                            new Object[] {
                              x.id(),
                              x.time(),
                              x.sender(),
                              x.recipient(),
                              x.note(),
                              money(x.amount()),
                              x.sender() == account.id() ? "Tiền ra" : "Tiền vào",
                              x.status()
                            })
                    .toList());
            pageLabel.setText(
                "Trang "
                    + (page + 1)
                    + " • "
                    + h.total()
                    + " giao dịch"
                    + (h.rows().isEmpty() ? " — Không có dữ liệu" : ""));
          } else {
            owner.setText(accountIdentity(account.name(), account.id()));
            balance.setText(money(account.balance()));
            if (sourceAccount != null)
              sourceAccount.setText("Tài khoản nguồn: " + account.id() + " — " + account.name());
            rows(
                recent,
                h.rows().stream()
                    .map(
                        x ->
                            new Object[] {
                              x.id(),
                              x.note(),
                              (x.sender() == account.id() ? "−" : "+") + money(x.amount()),
                              x.status()
                            })
                    .toList());
            if (pending == null && view.equals("Chuyển khoản")) loadPending();
          }
        },
        e -> {
          refreshing = false;
          error(e);
        });
  }

  void leave() {
    epoch++;
    if (timer != null) timer.stop();
    Bank remote = bank;
    Login old = login;
    login = null;
    bank = null;
    account = null;
    pending = null;
    snapshot = null;
    refreshing = false;
    page = 0;
    search = from = to = null;
    if (remote != null && old != null)
      new SwingWorker<Void, Void>() {
        protected Void doInBackground() throws Exception {
          remote.logout(old.token());
          return null;
        }
      }.execute();
  }

  public static void main(String[] args) throws Exception {
    Properties p = Net.load(args.length == 0 ? "config/client.properties" : args[0]);
    SwingUtilities.invokeLater(
        () -> {
          Ui.setup();
          new ClientMain(p).setVisible(true);
        });
  }
}
