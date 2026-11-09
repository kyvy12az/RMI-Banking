package vn.rmibank.server;

import static vn.rmibank.server.Store.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.rmi.registry.LocateRegistry;
import java.sql.*;
import java.util.*;
import vn.rmibank.common.Api.*;
import vn.rmibank.common.Net;

/** Offline, hash-bound reconciliation. No fingerprint bypass is exposed by normal startup. */
public final class Maintenance {
  record Difference(String table, long id, String before, String after) {}

  record Plan(
      String node,
      String role,
      String database,
      long sequence,
      String observedHash,
      String storedFingerprint,
      String replayFingerprint,
      String backup,
      List<Event> events,
      List<Difference> changes,
      List<String> blockers) {}

  static Connection open(Config cfg) throws Exception {
    Connection c =
        DriverManager.getConnection(
            cfg.get("jdbc.url"), cfg.get("jdbc.user"), cfg.p().getProperty("jdbc.password", ""));
    if (cfg.get("jdbc.url").startsWith("jdbc:mysql:")
        && count(c, "SELECT GET_LOCK(?,0)", Store.leaseName(c)) != 1) {
      c.close();
      throw new IllegalStateException("Dừng node trước khi chạy Maintenance");
    }
    return c;
  }

  static String observedHash(Connection c) throws Exception {
    Map<String, Object> data = new TreeMap<>();
    for (String table : Backup.TABLES)
      if (Backup.exists(c, table)) data.put(table, rows(c, "SELECT * FROM " + table));
    return digest(JSON.toJson(data));
  }

  static List<Event> sourceEvents(Connection c, Config cfg, long target) throws Exception {
    if (cfg.primary())
      return rows(c, "SELECT * FROM replication_events WHERE seq<=? ORDER BY seq", target).stream()
          .map(Store::event)
          .toList();
    Replication primary =
        (Replication)
            LocateRegistry.getRegistry(
                    cfg.host(cfg.get("primary.id")),
                    cfg.port(cfg.get("primary.id")),
                    new Net.Sockets(cfg.number("timeout.ms")))
                .lookup("Replication");
    List<Event> result = new ArrayList<>();
    long after = 0;
    while (after < target) {
      List<Event> page = primary.recoveryEvents(cfg.secret(), after, 200);
      if (page.isEmpty())
        throw new IllegalStateException("PRIMARY thiếu event để phục hồi replica");
      for (Event e : page)
        if (e.sequence() <= target) {
          result.add(e);
          after = e.sequence();
        }
      if (page.get(0).sequence() > target) break;
    }
    return result;
  }

  static final class Replay {
    final Map<Long, UserImage> users = new TreeMap<>();
    final Map<Long, AccountImage> accounts = new TreeMap<>();
    final Map<String, Transfer> transfers = new TreeMap<>();

    Replay(List<Event> events, long target) throws Exception {
      this(events, target, new Config(new Properties()));
    }

    Replay(List<Event> events, long target, Config cfg) throws Exception {
      long next = 1;
      Set<String> ids = new HashSet<>();
      for (Event event : events) {
        if (event.sequence() != next++
            || !ids.add(event.id())
            || (event.schema() != 1 && event.schema() != 2 && event.schema() != 3)
            || !List.of(
                    "BOOTSTRAP",
                    "REGISTER",
                    "TRANSFER",
                    "ACCOUNT_STATE",
                    "DIRECT_UPDATE",
                    "ROW_UPDATE",
                    "BALANCE_RESET",
                    "ACCOUNT_DELETE")
                .contains(event.type()))
          throw new IllegalStateException(
              "Event log không hợp lệ tại sequence " + event.sequence());
        for (UserImage u : LegacyUserRecovery.users(event, cfg)) users.put(u.id(), u);
        for (AccountImage a : event.payload().accounts()) {
          if (event.type().equals(AccountDeletion.TYPE)) {
            if (event.schema() != 3) throw new IllegalStateException("Invalid deletion schema");
            accounts.remove(a.id());
          } else accounts.put(a.id(), a);
        }
        if (event.payload().transfer() != null)
          transfers.put(event.payload().transfer().id(), event.payload().transfer());
      }
      if (next - 1 != target) throw new IllegalStateException("Event log thiếu sequence");
    }

    List<Map<String, Object>> userRows() {
      List<Map<String, Object>> list = new ArrayList<>();
      for (UserImage u : users.values()) {
        Map<String, Object> m = new TreeMap<>();
        m.put("id", u.id());
        m.put("username", u.username());
        m.put("full_name", u.name());
        m.put("phone", u.phone());
        m.put("password_hash", u.passwordHash());
        m.put("role", u.role());
        m.put("active", u.active());
        list.add(m);
      }
      return list;
    }

    List<Map<String, Object>> accountRows() {
      List<Map<String, Object>> list = new ArrayList<>();
      for (AccountImage a : accounts.values()) {
        Map<String, Object> m = new TreeMap<>();
        m.put("id", a.id());
        m.put("user_id", a.userId());
        m.put("balance", a.balance().setScale(0));
        m.put("version", a.version());
        m.put("active", a.active());
        list.add(m);
      }
      return list;
    }

    List<Map<String, Object>> transferRows() {
      List<Map<String, Object>> list = new ArrayList<>();
      for (Transfer t : transfers.values()) {
        Map<String, Object> m = new TreeMap<>();
        m.put("id", t.id());
        m.put("request_id", t.requestId());
        m.put("sender", t.sender());
        m.put("recipient", t.recipient());
        m.put("amount", t.amount().setScale(0));
        m.put("note", t.note());
        m.put("created_at", t.time());
        m.put("status", t.status());
        list.add(m);
      }
      return list;
    }

    String fingerprint() throws Exception {
      return digest(
          JSON.toJson(userRows()) + JSON.toJson(accountRows()) + JSON.toJson(transferRows()));
    }
  }

  static Plan audit(Config cfg, Path file) throws Exception {
    try (Connection c = open(cfg)) {
      c.setAutoCommit(false);
      rows(c, "SELECT * FROM node_state WHERE id=1 FOR UPDATE");
      String backup = Backup.write(c, cfg, "audit-before-repair");
      long seq = sequence(c);
      String observed = observedHash(c),
          stored = str(rows(c, "SELECT * FROM node_state WHERE id=1").get(0), "fingerprint");
      List<Event> events = sourceEvents(c, cfg, seq);
      Replay replay = new Replay(events, seq, cfg);
      List<String> blockers = new ArrayList<>();
      List<Difference> differences = new ArrayList<>();
      if (!JSON.toJson(rows(c, "SELECT * FROM transfers ORDER BY id"))
          .equals(JSON.toJson(replay.transferRows())))
        blockers.add("Lịch sử transfers khác event log: cần đối chiếu thủ công, không tự ghi đè");
      for (String table : ChangeCapture.TABLES) {
        var actual = rows(c, "SELECT * FROM " + table + " ORDER BY id");
        var expected = table.equals("users") ? replay.userRows() : replay.accountRows();
        if (actual.size() != expected.size()) {
          blockers.add("Khác số dòng " + table + ": không tự INSERT/DELETE");
          continue;
        }
        for (int i = 0; i < actual.size(); i++) {
          var a = actual.get(i);
          var e = expected.get(i);
          if (num(a, "id") != num(e, "id")) {
            blockers.add("Khác khóa chính " + table);
            continue;
          }
          if (!ChangeCapture.normalized(table, a).equals(ChangeCapture.normalized(table, e)))
            differences.add(new Difference(table, num(a, "id"), JSON.toJson(e), JSON.toJson(a)));
        }
      }
      Plan plan =
          new Plan(
              cfg.id(),
              cfg.get("server.role"),
              c.getCatalog(),
              seq,
              observed,
              stored,
              replay.fingerprint(),
              backup,
              events,
              differences,
              blockers);
      if (file.toAbsolutePath().getParent() != null)
        Files.createDirectories(file.toAbsolutePath().getParent());
      byte[] json = JSON.toJson(plan).getBytes(StandardCharsets.UTF_8);
      Files.write(file, json, StandardOpenOption.CREATE_NEW);
      if (Files.getFileStore(file.toAbsolutePath()).supportsFileAttributeView("posix"))
        Files.setPosixFilePermissions(
            file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
      c.commit();
      return plan;
    }
  }

  static void schema(Connection c) throws Exception {
    String ddl =
        new String(
            Objects.requireNonNull(Maintenance.class.getResourceAsStream("/schema.sql"))
                .readAllBytes(),
            StandardCharsets.UTF_8);
    for (String sql : ddl.split(";"))
      if (!sql.isBlank())
        try {
          exec(c, sql);
        } catch (SQLException e) {
          if (!(sql.strip().startsWith("CREATE INDEX")
              && (e.getErrorCode() == 1061 || e.getErrorCode() == 42111))) throw e;
        }
  }

  static void repair(Config cfg, Path file, String approvedHash) throws Exception {
    String json = Files.readString(file, StandardCharsets.UTF_8);
    if (!digest(json).equalsIgnoreCase(approvedHash))
      throw new IllegalArgumentException("Plan hash không khớp");
    Plan plan = JSON.fromJson(json, Plan.class);
    if (!plan.node().equals(cfg.id())
        || !plan.role().equals(cfg.get("server.role"))
        || !plan.blockers().isEmpty())
      throw new IllegalStateException("Plan có blocker hoặc sai node/role");
    try (Connection c = open(cfg)) {
      if (!plan.database().equals(c.getCatalog())) throw new IllegalStateException("Sai database");
      c.setAutoCommit(false);
      rows(c, "SELECT * FROM node_state WHERE id=1 FOR UPDATE");
      if (!observedHash(c).equals(plan.observedHash()))
        throw new IllegalStateException("Database đã thay đổi sau audit; tạo plan mới");
      String backup = Backup.write(c, cfg, "before-approved-repair");
      String physicalBefore = fingerprint(c);
      c.commit();
      c.setAutoCommit(true);
      schema(c);
      AccountDeletion.migrate(c, cfg);
      c.setAutoCommit(false);
      try {
        rows(c, "SELECT * FROM node_state WHERE id=1 FOR UPDATE");
        rows(c, "SELECT * FROM users ORDER BY id FOR UPDATE");
        rows(c, "SELECT * FROM accounts ORDER BY id FOR UPDATE");
        if (!physicalBefore.equals(fingerprint(c)))
          throw new IllegalStateException(
              "Database changed during maintenance migration; audit again");
        if (cfg.get("jdbc.url").startsWith("jdbc:mysql:")) exec(c, "SET @rmibank_app_write=1");
        Replay replay = new Replay(plan.events(), plan.sequence(), cfg);
        ChangeCapture.restoreRows(c, replay.userRows(), replay.accountRows());
        ChangeCapture.saveConfirmed(c);
        exec(c, "DELETE FROM capture_state WHERE id=1");
        exec(c, "INSERT INTO capture_state VALUES(1,?,?,TRUE)", cfg.id(), cfg.get("server.role"));
        for (Difference d : plan.changes()) {
          boolean captured = false;
          for (var row :
              rows(
                  c,
                  "SELECT * FROM change_requests WHERE state='PENDING' AND table_name=? AND row_id=?",
                  d.table(),
                  d.id()))
            if (ChangeCapture.normalized(d.table(), ChangeCapture.parse(str(row, "after_image")))
                .equals(ChangeCapture.normalized(d.table(), ChangeCapture.parse(d.after()))))
              captured = true;
          if (!captured)
            exec(
                c,
                "INSERT INTO change_requests(request_id,origin_node,table_name,row_id,before_image,after_image,state,attempts,next_attempt,detail,created_at) VALUES(?,?,?,?,?,?,'PENDING',0,0,'Recovered from approved audit',?)",
                UUID.randomUUID().toString(),
                cfg.id(),
                d.table(),
                d.id(),
                d.before(),
                d.after(),
                now());
        }
        if (!cfg.primary()) {
          exec(c, "DELETE FROM applied_events");
          for (Event e : plan.events())
            exec(
                c,
                "INSERT INTO applied_events VALUES(?,?,?)",
                e.sequence(),
                e.id(),
                digest(JSON.toJson(e)));
        }
        seal(c, plan.sequence());
        log(
            c,
            "[RECOVERY] node="
                + cfg.id()
                + " plan="
                + approvedHash
                + " pending="
                + plan.changes().size()
                + " backup="
                + backup);
        c.commit();
      } catch (Exception e) {
        c.rollback();
        throw e;
      } finally {
        if (cfg.get("jdbc.url").startsWith("jdbc:mysql:")) exec(c, "SET @rmibank_app_write=NULL");
        c.setAutoCommit(true);
      }
      if (cfg.get("jdbc.url").startsWith("jdbc:mysql:")) new ChangeCapture(null, cfg).install(c);
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 3)
      throw new IllegalArgumentException(
          "Maintenance audit <node.properties> <new-plan.json> | repair <node.properties> <plan.json> --apply <plan-sha256>");
    Config cfg = Config.load(args[1]);
    Path plan = Path.of(args[2]);
    if (args[0].equals("audit")) {
      Plan result = audit(cfg, plan);
      System.out.println(
          "Node="
              + result.node()
              + " sequence="
              + result.sequence()
              + " changedRows="
              + result.changes().size()
              + " blockers="
              + result.blockers());
      for (Difference d : result.changes())
        System.out.println("DIFF " + d.table() + " id=" + d.id());
      System.out.println("Backup=" + result.backup());
      System.out.println("Plan SHA256=" + digest(Files.readString(plan, StandardCharsets.UTF_8)));
    } else if (args[0].equals("repair") && args.length == 5 && args[3].equals("--apply")) {
      repair(cfg, plan, args[4]);
      System.out.println(
          "Repaired confirmed state; preserved SQL edits queued for PRIMARY review. Start node, then explicitly invoke reset-balances if required.");
    } else throw new IllegalArgumentException("Use audit or repair ... --apply <plan-sha256>");
  }
}
