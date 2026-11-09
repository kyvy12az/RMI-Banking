package vn.rmibank.server;

import com.google.gson.Gson;
import com.zaxxer.hikari.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import vn.rmibank.common.Api.*;

public final class Store implements AutoCloseable {
  final HikariDataSource pool;
  final Config cfg;
  final ChangeCapture capture;
  final boolean mysql;
  private Connection lease;
  static final Gson JSON = new Gson();
  static final ThreadLocal<List<String>> LOG_BUFFER = new ThreadLocal<>();

  interface Work<T> {
    T run(Connection c) throws Exception;
  }

  public Store(Config cfg) throws Exception {
    this.cfg = cfg;
    mysql = cfg.get("jdbc.url").startsWith("jdbc:mysql:");
    HikariConfig h = new HikariConfig();
    h.setJdbcUrl(cfg.get("jdbc.url"));
    h.setUsername(cfg.get("jdbc.user"));
    h.setPassword(cfg.p().getProperty("jdbc.password", ""));
    h.setMaximumPoolSize(8);
    h.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
    h.setConnectionTimeout(5000);
    pool = new HikariDataSource(h);
    capture = new ChangeCapture(this, cfg);
    try {
      if (mysql) {
        lease = pool.getConnection();
        if (count(lease, "SELECT GET_LOCK(?,0)", leaseName(lease)) != 1)
          throw new IllegalStateException(
              "Node is already running for this database; stop it before maintenance");
      }
      try (Connection c = pool.getConnection()) {
        String ddl =
            new String(
                Objects.requireNonNull(getClass().getResourceAsStream("/schema.sql"))
                    .readAllBytes(),
                StandardCharsets.UTF_8);
        for (String sql : ddl.split(";"))
          if (!sql.isBlank())
            try (Statement st = c.createStatement()) {
              try {
                st.execute(sql);
              } catch (SQLException ex) {
                if (!(sql.strip().startsWith("CREATE INDEX")
                    && (ex.getErrorCode() == 1061 || ex.getErrorCode() == 42111))) throw ex;
              }
            }
        c.setAutoCommit(false);
        try {
          rows(c, "SELECT seq FROM node_state WHERE id=1 FOR UPDATE");
          var state = rows(c, "SELECT * FROM capture_state WHERE id=1");
          String expected =
              str(rows(c, "SELECT * FROM node_state WHERE id=1").get(0), "fingerprint");
          long seq = sequence(c);
          String ledger = cfg.primary() ? "replication_events" : "applied_events";
          if (count(c, "SELECT COUNT(*) FROM " + ledger) != seq
              || count(c, "SELECT COALESCE(MAX(seq),0) FROM " + ledger) != seq)
            throw new IllegalStateException(
                "Event log không liên tục hoặc sai nguồn node; run Maintenance audit");
          if (state.isEmpty()) {
            if ((expected.isEmpty() && count(c, "SELECT COUNT(*) FROM users") != 0)
                || (!expected.isEmpty() && !expected.equals(fingerprint(c))))
              throw new IllegalStateException(
                  "CSDL không tương thích: fingerprint sai; run Maintenance audit, backup and repair plan first");
            ChangeCapture.saveConfirmed(c);
            seal(c, seq);
            exec(
                c,
                "INSERT INTO capture_state VALUES(1,?,?,TRUE)",
                cfg.id(),
                cfg.get("server.role"));
          } else {
            if (!str(state.get(0), "node_id").equals(cfg.id())
                || !str(state.get(0), "node_role").equals(cfg.get("server.role")))
              throw new IllegalStateException("Database belongs to another node/role");
            if (!expected.equals(confirmedFingerprint(c)))
              throw new IllegalStateException("Confirmed fingerprint sai; run Maintenance audit");
            appFlag(c, true);
            try {
              capture.restore(c);
            } catch (BankException ex) {
              throw new IllegalStateException("fingerprint sai: " + ex.getMessage(), ex);
            }
            appFlag(c, false);
          }
          c.commit();
        } catch (Exception e) {
          c.rollback();
          throw e;
        } finally {
          appFlag(c, false);
          c.setAutoCommit(true);
        }
        if (mysql) capture.install(c);
      }
    } catch (Exception ex) {
      close();
      throw ex;
    }
  }

  static String leaseName(Connection c) throws Exception {
    return "rmibank_" + digest(c.getCatalog()).substring(0, 40);
  }

  void appFlag(Connection c, boolean enabled) throws SQLException {
    if (mysql) exec(c, "SET @rmibank_app_write=" + (enabled ? "1" : "NULL"));
  }

  <T> T read(Work<T> work) throws Exception {
    try (Connection c = pool.getConnection()) {
      return work.run(c);
    }
  }

  <T> T tx(Work<T> work) throws Exception {
    for (int attempt = 0; ; attempt++) {
      try (Connection c = pool.getConnection()) {
        c.setAutoCommit(false);
        LOG_BUFFER.set(new ArrayList<>());
        try {
          rows(c, "SELECT seq FROM node_state WHERE id=1 FOR UPDATE");
          appFlag(c, true);
          if (!str(rows(c, "SELECT * FROM node_state WHERE id=1").get(0), "fingerprint")
              .equals(confirmedFingerprint(c)))
            throw new BankException(
                "Confirmed data/history changed outside RMI; run Maintenance audit");
          capture.restore(c);
          if (cfg.primary()) capture.drain(c);
          T result = work.run(c);
          String actual = fingerprint(c);
          String sealed = str(rows(c, "SELECT * FROM node_state WHERE id=1").get(0), "fingerprint");
          if (!actual.equals(sealed))
            throw new BankException(
                "Business write missing replication event; transaction rolled back");
          ChangeCapture.saveConfirmed(c);
          c.commit();
          for (String message : LOG_BUFFER.get())
            org.slf4j.LoggerFactory.getLogger(Store.class).info(message);
          return result;
        } catch (Exception ex) {
          c.rollback();
          if (ex instanceof SQLException sql
              && attempt < 4
              && (sql.getErrorCode() == 1213
                  || sql.getErrorCode() == 1205
                  || "40001".equals(sql.getSQLState()))) {
            Thread.sleep(10L * (attempt + 1));
            continue;
          }
          throw ex;
        } finally {
          LOG_BUFFER.remove();
          try {
            appFlag(c, false);
          } catch (SQLException ex) {
            pool.evictConnection(c);
          }
        }
      }
    }
  }

  void captureDirectUpdates() throws Exception {
    tx(c -> null);
  }

  static String confirmedFingerprint(Connection c) throws Exception {
    return digest(
        JSON.toJson(rows(c, "SELECT * FROM confirmed_users ORDER BY id"))
            + JSON.toJson(rows(c, "SELECT * FROM confirmed_accounts ORDER BY id"))
            + JSON.toJson(rows(c, "SELECT * FROM transfers ORDER BY id")));
  }

  List<ChangeRequest> pendingChanges() throws Exception {
    return read(
        c ->
            rows(
                    c,
                    "SELECT * FROM change_requests WHERE state='PENDING' AND next_attempt<=? ORDER BY local_order LIMIT 50",
                    System.currentTimeMillis())
                .stream()
                .map(ChangeCapture::request)
                .toList());
  }

  ChangeResult accept(ChangeRequest r) throws Exception {
    return tx(c -> capture.accept(c, r));
  }

  void acknowledge(ChangeRequest r, ChangeResult result) throws Exception {
    tx(
        c -> {
          capture.acknowledge(c, r.requestId(), result);
          return null;
        });
  }

  void retry(ChangeRequest r, Exception error) throws Exception {
    tx(
        c -> {
          var entry =
              rows(c, "SELECT * FROM change_requests WHERE request_id=?", r.requestId()).get(0);
          long attempts = num(entry, "attempts") + 1;
          long delay =
              Math.min(
                  cfg.number("retry.max.ms"),
                  cfg.number("retry.base.ms") * (1L << Math.min(10, attempts - 1)));
          String detail =
              error instanceof BankException ? error.getMessage() : "PRIMARY/RMI unavailable";
          exec(
              c,
              "UPDATE change_requests SET attempts=?,next_attempt=?,detail=? WHERE request_id=?",
              attempts,
              System.currentTimeMillis() + delay,
              detail,
              r.requestId());
          log(
              c,
              "[CHANGE_RETRY] node="
                  + cfg.id()
                  + " request="
                  + r.requestId()
                  + " table="
                  + r.table()
                  + " id="
                  + r.rowId()
                  + " reason="
                  + detail);
          return null;
        });
  }

  ResetResult resetBalances(String requestId, long actor) throws Exception {
    requireId(requestId);
    return tx(
        c -> {
          requireAdmin(c, actor);
          var old =
              rows(c, "SELECT * FROM admin_operations WHERE operation_key='RESET_BALANCES_100000'");
          if (!old.isEmpty())
            return JSON.fromJson(str(old.get(0), "result_json"), ResetResult.class);
          if (count(c, "SELECT COUNT(*) FROM admin_operations WHERE request_id=?", requestId) > 0)
            throw new BankException("requestId already used by another administrative operation");
          String backup = Backup.write(c, cfg, "before-reset-100000");
          exec(c, "UPDATE accounts SET balance=100000,version=version+1");
          List<AccountImage> images =
              rows(c, "SELECT * FROM accounts ORDER BY id").stream().map(Store::image).toList();
          Event event =
              append(c, "BALANCE_RESET", new Payload(List.of(), images, null), cfg.peers());
          ResetResult result = new ResetResult(requestId, event.sequence(), images.size(), backup);
          exec(
              c,
              "INSERT INTO admin_operations VALUES('RESET_BALANCES_100000',?,?,?)",
              requestId,
              JSON.toJson(result),
              now());
          log(
              c,
              "[ADMIN_RESET] node="
                  + cfg.id()
                  + " accounts="
                  + images.size()
                  + " request="
                  + requestId
                  + " backup="
                  + backup);
          return result;
        });
  }

  record BalanceOperation(String hash, ChangeResult result) {}

  ChangeResult setBalance(
      long id, BigDecimal amount, long expectedVersion, String requestId, long actor)
      throws Exception {
    requireId(requestId);
    if (amount == null
        || amount.signum() < 0
        || amount.stripTrailingZeros().scale() > 0
        || amount.compareTo(new BigDecimal("9999999999999999999")) > 0)
      throw new BankException("Invalid VND balance");
    String hash =
        digest(id + ":" + amount.stripTrailingZeros().toPlainString() + ":" + expectedVersion);
    return tx(
        c -> {
          requireAdmin(c, actor);
          var previous = rows(c, "SELECT * FROM admin_operations WHERE request_id=?", requestId);
          if (!previous.isEmpty()) {
            BalanceOperation saved =
                JSON.fromJson(str(previous.get(0), "result_json"), BalanceOperation.class);
            if (saved == null || !hash.equals(saved.hash()))
              throw new BankException("requestId reused with different administrative data");
            return saved.result();
          }
          var found = rows(c, "SELECT * FROM accounts WHERE id=?", id);
          if (found.isEmpty()) throw new BankException("Account not found");
          var before = new TreeMap<>(found.get(0));
          before.put("version", expectedVersion);
          var after = new TreeMap<>(before);
          after.put("balance", amount);
          ChangeResult result =
              capture.accept(
                  c,
                  new ChangeRequest(
                      requestId,
                      cfg.id(),
                      "accounts",
                      id,
                      JSON.toJson(before),
                      JSON.toJson(after)));
          exec(
              c,
              "INSERT INTO admin_operations VALUES(?,?,?,?)",
              "BALANCE:" + digest(requestId).substring(0, 55),
              requestId,
              JSON.toJson(new BalanceOperation(hash, result)),
              now());
          return result;
        });
  }

  static void requireAdmin(Connection c, long actor) throws Exception {
    if (count(c, "SELECT COUNT(*) FROM users WHERE id=? AND role=? AND active=TRUE", actor, "ADMIN")
        != 1) throw new BankException("Administrative privileges revoked");
  }

  static void requireId(String id) throws BankException {
    if (id == null || !id.matches("[A-Za-z0-9-]{16,64}"))
      throw new BankException("Use a unique requestId (UUID)");
  }

  static void exec(Connection c, String sql, Object... args) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      bind(s, args);
      s.executeUpdate();
    }
  }

  static List<Map<String, Object>> rows(Connection c, String sql, Object... args)
      throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      bind(s, args);
      try (ResultSet r = s.executeQuery()) {
        List<Map<String, Object>> out = new ArrayList<>();
        while (r.next()) {
          Map<String, Object> m = new TreeMap<>();
          for (int i = 1; i <= r.getMetaData().getColumnCount(); i++)
            m.put(r.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT), r.getObject(i));
          out.add(m);
        }
        return out;
      }
    }
  }

  static void bind(PreparedStatement s, Object... a) throws SQLException {
    for (int i = 0; i < a.length; i++) s.setObject(i + 1, a[i]);
  }

  static long count(Connection c, String sql, Object... a) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      bind(s, a);
      try (ResultSet r = s.executeQuery()) {
        r.next();
        return r.getLong(1);
      }
    }
  }

  static String str(Map<String, Object> m, String k) {
    return String.valueOf(m.get(k));
  }

  static long num(Map<String, Object> m, String k) {
    return ((Number) m.get(k)).longValue();
  }

  static boolean bool(Map<String, Object> m, String k) {
    return m.get(k) instanceof Number n
        ? n.intValue() != 0
        : Boolean.parseBoolean(str(m, k)) || "1".equals(str(m, k));
  }

  static BigDecimal money(Map<String, Object> m, String k) {
    return new BigDecimal(str(m, k));
  }

  static Account account(Map<String, Object> m) {
    return new Account(
        num(m, "id"),
        str(m, "full_name"),
        money(m, "balance"),
        num(m, "version"),
        bool(m, "active"));
  }

  static Transfer transfer(Map<String, Object> m) {
    return new Transfer(
        str(m, "id"),
        str(m, "request_id"),
        num(m, "sender"),
        num(m, "recipient"),
        money(m, "amount"),
        str(m, "note"),
        str(m, "created_at"),
        str(m, "status"));
  }

  static AccountImage image(Map<String, Object> m) {
    return new AccountImage(
        num(m, "id"), num(m, "user_id"), money(m, "balance"), num(m, "version"), bool(m, "active"));
  }

  static UserImage user(Map<String, Object> m) {
    return new UserImage(
        num(m, "id"),
        str(m, "username"),
        str(m, "full_name"),
        str(m, "phone"),
        str(m, "password_hash"),
        str(m, "role"),
        bool(m, "active"));
  }

  static String digest(String s) throws Exception {
    return HexFormat.of()
        .formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
  }

  static String fingerprint(Connection c) throws Exception {
    StringBuilder b = new StringBuilder();
    for (String t : List.of("users", "accounts", "transfers"))
      b.append(JSON.toJson(rows(c, "SELECT * FROM " + t + " ORDER BY id")));
    return digest(b.toString());
  }

  static long sequence(Connection c) throws SQLException {
    return count(c, "SELECT seq FROM node_state WHERE id=1");
  }

  static void seal(Connection c, long seq) throws Exception {
    exec(c, "UPDATE node_state SET seq=?,fingerprint=? WHERE id=1", seq, fingerprint(c));
  }

  static String timestamp(Instant instant) {
    return new java.time.format.DateTimeFormatterBuilder()
        .appendInstant(9)
        .toFormatter()
        .format(instant);
  }

  static String now() {
    return timestamp(Instant.now());
  }

  static void log(Connection c, String message) throws SQLException {
    exec(c, "INSERT INTO sync_logs VALUES(?,?,?)", UUID.randomUUID().toString(), now(), message);
    if (LOG_BUFFER.get() != null) LOG_BUFFER.get().add(message);
  }

  Event append(Connection c, String type, Payload p, List<String> peers) throws Exception {
    long seq = sequence(c) + 1;
    Event e = new Event(UUID.randomUUID().toString(), seq, type, now(), 2, p);
    exec(
        c,
        "INSERT INTO replication_events VALUES(?,?,?,?,?,?)",
        seq,
        e.id(),
        type,
        e.time(),
        2,
        JSON.toJson(p));
    for (String peer : peers)
      exec(c, "INSERT INTO replication_deliveries VALUES(?,?,FALSE,0,0,'')", seq, peer);
    seal(c, seq);
    log(
        c,
        "[PRIMARY] "
            + (p.transfer() == null ? type : p.transfer().id())
            + " đã commit EVT="
            + e.id()
            + " sequence="
            + seq);
    return e;
  }

  static Event event(Map<String, Object> r) {
    return new Event(
        str(r, "event_id"),
        num(r, "seq"),
        str(r, "event_type"),
        str(r, "created_at"),
        (int) num(r, "schema_version"),
        JSON.fromJson(str(r, "payload"), Payload.class));
  }

  static void insertTransfer(Connection c, Transfer t) throws SQLException {
    exec(
        c,
        "INSERT INTO transfers VALUES(?,?,?,?,?,?,?,?)",
        t.id(),
        t.requestId(),
        t.sender(),
        t.recipient(),
        t.amount(),
        t.note(),
        t.time(),
        t.status());
  }

  Ack apply(Event e) throws Exception {
    return tx(
        c -> {
          if (e == null || (e.schema() != 1 && e.schema() != 2) || e.payload() == null)
            throw new BankException("Unsupported event schema; upgrade all nodes");
          long seq = sequence(c);
          String dg = digest(JSON.toJson(e));
          if (e.sequence() <= seq) {
            var old = rows(c, "SELECT * FROM applied_events WHERE seq=?", e.sequence());
            if (old.isEmpty()
                || !str(old.get(0), "event_id").equals(e.id())
                || !str(old.get(0), "digest").equals(dg))
              throw new BankException("Sự kiện cũ không tương thích");
            return new Ack(seq);
          }
          if (e.sequence() != seq + 1) throw new BankException("GAP: cần sequence " + (seq + 1));
          boolean authoritative =
              List.of("DIRECT_UPDATE", "ROW_UPDATE", "BALANCE_RESET").contains(e.type());
          for (UserImage u : e.payload().users()) {
            boolean exists = count(c, "SELECT COUNT(*) FROM users WHERE id=?", u.id()) == 1;
            if (authoritative) {
              if (!exists) throw new BankException("Replica missing user " + u.id());
              exec(
                  c,
                  "UPDATE users SET username=?,full_name=?,phone=?,password_hash=?,role=?,active=? WHERE id=?",
                  u.username(),
                  u.name(),
                  u.phone(),
                  u.passwordHash(),
                  u.role(),
                  u.active(),
                  u.id());
            } else {
              if (exists) throw new BankException("User already exists outside event log");
              exec(
                  c,
                  "INSERT INTO users VALUES(?,?,?,?,?,?,?)",
                  u.id(),
                  u.username(),
                  u.name(),
                  u.phone(),
                  u.passwordHash(),
                  u.role(),
                  u.active());
            }
          }
          for (AccountImage a : e.payload().accounts()) {
            var old = rows(c, "SELECT * FROM accounts WHERE id=?", a.id());
            if (old.isEmpty()) {
              if (authoritative) throw new BankException("Replica missing account " + a.id());
              exec(
                  c,
                  "INSERT INTO accounts VALUES(?,?,?,?,?)",
                  a.id(),
                  a.userId(),
                  a.balance(),
                  a.version(),
                  a.active());
            } else {
              long previous = num(old.get(0), "version");
              if (!e.type().equals("DIRECT_UPDATE")
                  && (a.version() < previous || (!authoritative && a.version() != previous + 1)))
                throw new BankException("Account version mismatch id=" + a.id());
              exec(
                  c,
                  "UPDATE accounts SET user_id=?,balance=?,version=?,active=? WHERE id=?",
                  a.userId(),
                  a.balance(),
                  a.version(),
                  a.active(),
                  a.id());
            }
          }
          Transfer t = e.payload().transfer();
          if (t != null) {
            if (e.type().equals("DIRECT_UPDATE")) {
              if (count(c, "SELECT COUNT(*) FROM transfers WHERE id=?", t.id()) != 1)
                throw new BankException("Missing legacy transfer");
              exec(
                  c,
                  "UPDATE transfers SET request_id=?,sender=?,recipient=?,amount=?,note=?,created_at=?,status=? WHERE id=?",
                  t.requestId(),
                  t.sender(),
                  t.recipient(),
                  t.amount(),
                  t.note(),
                  t.time(),
                  t.status(),
                  t.id());
            } else insertTransfer(c, t);
          }
          exec(c, "INSERT INTO applied_events VALUES(?,?,?)", e.sequence(), e.id(), dg);
          seal(c, e.sequence());
          log(c, "[SYNC] node=" + cfg.id() + " event=" + e.id() + " sequence=" + e.sequence());
          return new Ack(e.sequence());
        });
  }

  public void close() {
    if (lease != null) {
      try {
        exec(lease, "DO RELEASE_LOCK(?)", leaseName(lease));
        lease.close();
      } catch (Exception ignored) {
      }
      lease = null;
    }
    pool.close();
  }
}
