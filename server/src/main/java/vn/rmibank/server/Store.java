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
  static final Gson JSON = new Gson();
  static final ThreadLocal<List<String>> LOG_BUFFER = new ThreadLocal<>();

  interface Work<T> {
    T run(Connection c) throws Exception;
  }

  public Store(Config cfg) throws Exception {
    HikariConfig h = new HikariConfig();
    h.setJdbcUrl(cfg.get("jdbc.url"));
    h.setUsername(cfg.get("jdbc.user"));
    h.setPassword(cfg.p().getProperty("jdbc.password", ""));
    h.setMaximumPoolSize(8);
    h.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
    h.setConnectionTimeout(5000);
    pool = new HikariDataSource(h);
    try {
      String ddl =
          new String(
              Objects.requireNonNull(getClass().getResourceAsStream("/schema.sql")).readAllBytes(),
              StandardCharsets.UTF_8);
      try (Connection c = pool.getConnection()) {
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
      }
      read(
          c -> {
            List<Map<String, Object>> s = rows(c, "SELECT * FROM node_state WHERE id=1");
            String expected = str(s.get(0), "fingerprint");
            if (expected.isEmpty()) {
              if (count(c, "SELECT COUNT(*) FROM users") != 0)
                throw new IllegalStateException("CSDL có dữ liệu không rõ nguồn, không tự ghi đè");
            } else if (!expected.equals(fingerprint(c)))
              throw new IllegalStateException(
                  "CSDL không tương thích: fingerprint sai; kiểm tra sửa dữ liệu ngoài luồng RMI");
            long seq = sequence(c);
            String table = cfg.primary() ? "replication_events" : "applied_events";
            if (count(c, "SELECT COUNT(*) FROM " + table) != seq
                || count(c, "SELECT COALESCE(MAX(seq),0) FROM " + table) != seq)
              throw new IllegalStateException("Event log không liên tục hoặc sai nguồn node");
            return null;
          });
    } catch (Exception ex) {
      pool.close();
      throw ex;
    }
  }

  <T> T read(Work<T> work) throws Exception {
    try (Connection c = pool.getConnection()) {
      return work.run(c);
    }
  }

  <T> T tx(Work<T> work) throws Exception {
    try (Connection c = pool.getConnection()) {
      c.setAutoCommit(false);
      LOG_BUFFER.set(new ArrayList<>());
      try {
        rows(c, "SELECT seq FROM node_state WHERE id=1 FOR UPDATE");
        T value = work.run(c);
        c.commit();
        for (String message : LOG_BUFFER.get())
          org.slf4j.LoggerFactory.getLogger(Store.class).info(message);
        return value;
      } catch (Exception ex) {
        c.rollback();
        throw ex;
      } finally {
        LOG_BUFFER.remove();
      }
    }
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
    return Boolean.parseBoolean(str(m, k));
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
    Event e = new Event(UUID.randomUUID().toString(), seq, type, now(), 1, p);
    exec(
        c,
        "INSERT INTO replication_events VALUES(?,?,?,?,?,?)",
        seq,
        e.id(),
        type,
        e.time(),
        1,
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
          if (e == null || e.schema() != 1 || e.payload() == null)
            throw new BankException("Schema sự kiện không hỗ trợ");
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
          for (UserImage u : e.payload().users()) {
            if (count(c, "SELECT COUNT(*) FROM users WHERE id=?", u.id()) == 0)
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
            else throw new BankException("User đã tồn tại ngoài event log");
          }
          for (AccountImage a : e.payload().accounts()) {
            var old = rows(c, "SELECT * FROM accounts WHERE id=?", a.id());
            if (old.isEmpty())
              exec(
                  c,
                  "INSERT INTO accounts VALUES(?,?,?,?,?)",
                  a.id(),
                  a.userId(),
                  a.balance(),
                  a.version(),
                  a.active());
            else {
              if (num(old.get(0), "version") + 1 != a.version())
                throw new BankException("Phiên bản tài khoản không tương thích");
              exec(
                  c,
                  "UPDATE accounts SET balance=?,version=?,active=? WHERE id=?",
                  a.balance(),
                  a.version(),
                  a.active(),
                  a.id());
            }
          }
          if (e.payload().transfer() != null) insertTransfer(c, e.payload().transfer());
          exec(c, "INSERT INTO applied_events VALUES(?,?,?)", e.sequence(), e.id(), dg);
          seal(c, e.sequence());
          log(c, "[SYNC] Áp dụng EVT=" + e.id() + " sequence=" + e.sequence());
          return new Ack(e.sequence());
        });
  }

  public void close() {
    pool.close();
  }
}
