package vn.rmibank.server;

import static vn.rmibank.server.Store.*;

import java.math.BigDecimal;
import java.rmi.*;
import java.rmi.registry.LocateRegistry;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.mindrot.jbcrypt.BCrypt;
import vn.rmibank.common.*;
import vn.rmibank.common.Api.*;

public final class BankService implements Bank, Replication, AutoCloseable {
  final Store db;
  final Config cfg;
  final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
  final ScheduledExecutorService worker =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "rmi-outbox");
            t.setDaemon(true);
            return t;
          });
  final Map<String, Node> health = new ConcurrentHashMap<>();
  final Map<String, Long> nextTry = new ConcurrentHashMap<>();
  final Map<String, Integer> failures = new ConcurrentHashMap<>();

  record Session(long user, Role role, long expiry) {}

  public BankService(Store db, Config cfg) {
    this.db = db;
    this.cfg = cfg;
  }

  public void start() {
    worker.scheduleWithFixedDelay(
        this::cycle, 0, cfg.number("heartbeat.ms"), TimeUnit.MILLISECONDS);
  }

  Bank primary() throws RemoteException {
    try {
      return (Bank)
          LocateRegistry.getRegistry(
                  cfg.host(cfg.get("primary.id")),
                  cfg.port(cfg.get("primary.id")),
                  new Net.Sockets(cfg.number("timeout.ms")))
              .lookup("Bank");
    } catch (Exception e) {
      throw new RemoteException("PRIMARY không hoạt động; không thể thực hiện nghiệp vụ.");
    }
  }

  interface Operation<T> {
    T run() throws Exception;
  }

  <T> T safe(Operation<T> op) throws BankException {
    try {
      if (cfg.primary()) db.captureDirectUpdates();
      return op.run();
    } catch (BankException e) {
      throw e;
    } catch (Exception e) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .error("Lỗi xử lý nội bộ: {}", e.getClass().getSimpleName());
      // A JDBC commit error can have an uncertain outcome; never invite a new requestId.
      throw new BankException(
          "Không thể xác định kết quả xử lý. Kiểm tra CSDL và nhật ký server.", false);
    }
  }

  Session session(String token, Role role) throws Exception {
    Session s = token == null ? null : sessions.get(token);
    if (s == null || s.expiry() < System.currentTimeMillis()) {
      if (token != null) sessions.remove(token);
      throw new BankException("Phiên đã hết hạn. Vui lòng đăng nhập lại.");
    }
    var users = db.read(c -> rows(c, "SELECT * FROM confirmed_users WHERE id=?", s.user()));
    if (users.isEmpty()
        || !bool(users.get(0), "active")
        || !str(users.get(0), "role").equals(s.role().name())
        || (role != null && s.role() != role)) throw new BankException("Không có quyền truy cập");
    if (s.role() == Role.CUSTOMER
        && !db
            .read(c -> rows(c, "SELECT active FROM confirmed_accounts WHERE user_id=?", s.user()))
            .stream()
            .allMatch(a -> bool(a, "active"))) throw new BankException("Tài khoản đã bị khóa");
    return s;
  }

  public String ping() {
    return cfg.id();
  }

  public Account register(Registration r) throws RemoteException, BankException {
    if (!cfg.primary()) return primary().register(r);
    return safe(
        () -> {
          if (r == null
              || r.name() == null
              || r.name().isBlank()
              || r.name().length() > 120
              || r.username() == null
              || !r.username().strip().matches("[A-Za-z0-9_]{3,32}")
              || r.phone() == null
              || !r.phone().matches("(?:0[0-9]{9}|\\+84[0-9]{9})")
              || r.password() == null
              || r.password().length() < 8
              || r.password().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw new BankException(
                "Kiểm tra họ tên, username (3–32 ký tự), số điện thoại và mật khẩu (8–72 byte).");
          String username = r.username().strip().toLowerCase(Locale.ROOT),
              hash = BCrypt.hashpw(r.password(), BCrypt.gensalt(12));
          return db.tx(
              c -> {
                if (count(c, "SELECT COUNT(*) FROM users WHERE username=?", username) > 0)
                  throw new BankException("Tên đăng nhập đã tồn tại");
                long id = count(c, "SELECT COALESCE(MAX(id),0)+1 FROM users"),
                    account = count(c, "SELECT COALESCE(MAX(id),100000)+1 FROM accounts");
                exec(
                    c,
                    "INSERT INTO users VALUES(?,?,?,?,?,'CUSTOMER',TRUE)",
                    id,
                    username,
                    r.name().strip(),
                    r.phone(),
                    hash);
                exec(c, "INSERT INTO accounts VALUES(?,?,0,1,TRUE)", account, id);
                db.append(
                    c,
                    "REGISTER",
                    new Payload(
                        List.of(
                            new UserImage(
                                id, username, r.name().strip(), r.phone(), hash, "CUSTOMER", true)),
                        List.of(new AccountImage(account, id, BigDecimal.ZERO, 1, true)),
                        null),
                    cfg.peers());
                return new Account(account, r.name().strip(), BigDecimal.ZERO, 1, true);
              });
        });
  }

  public Login login(String username, String password, Role expected)
      throws RemoteException, BankException {
    if (!cfg.primary()) return primary().login(username, password, expected);
    return safe(
        () -> {
          if (username == null || password == null || password.length() > 200)
            throw new BankException("Thông tin đăng nhập không hợp lệ");
          var users =
              db.read(
                  c ->
                      rows(
                          c,
                          "SELECT * FROM confirmed_users WHERE username=?",
                          username.strip().toLowerCase(Locale.ROOT)));
          if (users.isEmpty()
              || !BCrypt.checkpw(password, str(users.get(0), "password_hash"))
              || !bool(users.get(0), "active")
              || !str(users.get(0), "role").equals(String.valueOf(expected)))
            throw new BankException("Tên đăng nhập, mật khẩu hoặc vai trò không đúng");
          var u = users.get(0);
          if (expected == Role.CUSTOMER
              && db.read(
                      c ->
                          count(
                              c,
                              "SELECT COUNT(*) FROM confirmed_accounts WHERE user_id=? AND active=TRUE",
                              num(u, "id")))
                  != 1) throw new BankException("Tài khoản đã bị khóa");
          byte[] bytes = new byte[32];
          new SecureRandom().nextBytes(bytes);
          String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
          long userId = num(u, "id");
          synchronized (sessions) {
            long now = System.currentTimeMillis();
            sessions.entrySet().removeIf(e -> e.getValue().expiry() < now);
            if (sessions.values().stream().anyMatch(session -> session.user() == userId))
              throw new BankException(
                  "Không thể đăng nhập. Tài khoản đang có phiên đăng nhập hoạt động.");
            long expiry = now + cfg.number("session.minutes") * 60000L;
            sessions.put(token, new Session(userId, expected, expiry));
            return new Login(token, expected, str(u, "full_name"), expiry);
          }
        });
  }

  public void logout(String token) throws RemoteException, BankException {
    if (!cfg.primary()) {
      primary().logout(token);
      return;
    }
    if (token != null) sessions.remove(token);
  }

  public void authorizeAdmin(String token) throws RemoteException, BankException {
    if (!cfg.primary()) {
      primary().authorizeAdmin(token);
      return;
    }
    safe(
        () -> {
          session(token, Role.ADMIN);
          return null;
        });
  }

  public Account me(String token) throws RemoteException, BankException {
    if (!cfg.primary()) return primary().me(token);
    return safe(
        () -> {
          var s = session(token, Role.CUSTOMER);
          return db.read(
              c ->
                  account(
                      rows(
                              c,
                              "SELECT a.*,u.full_name FROM confirmed_accounts a JOIN confirmed_users u ON u.id=a.user_id WHERE a.user_id=?",
                              s.user())
                          .get(0)));
        });
  }

  public Recipient recipient(String token, long id) throws RemoteException, BankException {
    if (!cfg.primary()) return primary().recipient(token, id);
    return safe(
        () -> {
          session(token, Role.CUSTOMER);
          return db.read(
              c -> {
                var r =
                    rows(
                        c,
                        "SELECT a.id,u.full_name FROM confirmed_accounts a JOIN confirmed_users u ON u.id=a.user_id WHERE a.id=? AND a.active=TRUE AND u.active=TRUE",
                        id);
                if (r.isEmpty())
                  throw new BankException("Tài khoản nhận không tồn tại hoặc bị khóa");
                return new Recipient(id, str(r.get(0), "full_name"));
              });
        });
  }

  public Transfer transfer(String token, TransferRequest r) throws RemoteException, BankException {
    if (!cfg.primary()) return primary().transfer(token, r);
    return safe(
        () -> {
          Session s = session(token, Role.CUSTOMER);
          if (r == null
              || r.requestId() == null
              || !r.requestId().matches("[A-Za-z0-9-]{16,64}")
              || r.amount() == null
              || r.amount().signum() <= 0
              || r.amount().stripTrailingZeros().scale() > 0
              || r.amount().compareTo(new BigDecimal("1000000000")) > 0
              || r.note() == null
              || r.note().length() > 255)
            throw new BankException(
                "Yêu cầu không hợp lệ; số tiền nguyên từ 1 đến 1.000.000.000 đồng.");
          return db.tx(
              c -> {
                if (count(
                        c,
                        "SELECT COUNT(*) FROM users WHERE id=? AND active=TRUE AND role=?",
                        s.user(),
                        "CUSTOMER")
                    != 1) throw new BankException("Customer privileges revoked");
                var src = rows(c, "SELECT * FROM accounts WHERE user_id=?", s.user());
                if (src.isEmpty()) throw new BankException("Không tìm thấy tài khoản nguồn");
                long sender = num(src.get(0), "id");
                var old = rows(c, "SELECT * FROM transfers WHERE request_id=?", r.requestId());
                if (!old.isEmpty()) {
                  Transfer t = Store.transfer(old.get(0));
                  if (t.sender() != sender
                      || t.recipient() != r.recipient()
                      || t.amount().compareTo(r.amount()) != 0
                      || !t.note().equals(r.note()))
                    throw new BankException("requestId đã dùng với dữ liệu khác");
                  return t;
                }
                if (sender == r.recipient())
                  throw new BankException("Không thể chuyển cho chính mình");
                var locked = new ArrayList<Map<String, Object>>();
                for (long id :
                    new long[] {Math.min(sender, r.recipient()), Math.max(sender, r.recipient())})
                  locked.addAll(rows(c, "SELECT * FROM accounts WHERE id=? FOR UPDATE", id));
                if (locked.size() != 2) throw new BankException("Tài khoản nhận không tồn tại");
                var a =
                    locked.stream().filter(m -> num(m, "id") == sender).findFirst().orElseThrow();
                var b =
                    locked.stream()
                        .filter(m -> num(m, "id") == r.recipient())
                        .findFirst()
                        .orElseThrow();
                if (!bool(a, "active") || !bool(b, "active"))
                  throw new BankException("Tài khoản đã bị khóa");
                if (money(a, "balance").compareTo(r.amount()) < 0)
                  throw new BankException("Số dư không đủ");
                exec(
                    c,
                    "UPDATE accounts SET balance=balance-?,version=version+1 WHERE id=?",
                    r.amount(),
                    sender);
                afterDebit.run();
                exec(
                    c,
                    "UPDATE accounts SET balance=balance+?,version=version+1 WHERE id=?",
                    r.amount(),
                    r.recipient());
                Transfer t =
                    new Transfer(
                        "GD-" + UUID.randomUUID(),
                        r.requestId(),
                        sender,
                        r.recipient(),
                        r.amount(),
                        r.note(),
                        now(),
                        "COMMITTED");
                insertTransfer(c, t);
                List<AccountImage> images = new ArrayList<>();
                for (var m :
                    rows(
                        c,
                        "SELECT * FROM accounts WHERE id IN (?,?) ORDER BY id",
                        sender,
                        r.recipient())) images.add(image(m));
                db.append(c, "TRANSFER", new Payload(List.of(), images, t), cfg.peers());
                return t;
              });
        });
  }

  // Package-private fault injection exercises the actual SQL rollback path in tests.
  Runnable afterDebit = () -> {};

  public Transfer findRequest(String token, String requestId)
      throws RemoteException, BankException {
    if (!cfg.primary()) return primary().findRequest(token, requestId);
    return safe(
        () -> {
          Account a = me(token);
          return db.read(
              c -> {
                var r =
                    rows(
                        c,
                        "SELECT * FROM transfers WHERE request_id=? AND sender=?",
                        requestId,
                        a.id());
                return r.isEmpty() ? null : Store.transfer(r.get(0));
              });
        });
  }

  public History history(String token, String search, String from, String to, int page, int size)
      throws RemoteException, BankException {
    if (!cfg.primary()) return primary().history(token, search, from, to, page, size);
    return safe(
        () -> {
          Account a = me(token);
          if (page < 0
              || page > 100000
              || size < 1
              || size > 100
              || search == null
              || search.length() > 100) throw new BankException("Bộ lọc không hợp lệ");
          String start =
              from == null || from.isBlank()
                  ? ""
                  : timestamp(LocalDate.parse(from).atStartOfDay().toInstant(ZoneOffset.UTC));
          String end =
              to == null || to.isBlank()
                  ? "9999"
                  : timestamp(
                      LocalDate.parse(to).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
          return db.read(
              c -> {
                String where =
                    " WHERE (sender=? OR recipient=?) AND (note LIKE ? OR id LIKE ?) AND created_at>=? AND created_at<?";
                Object[] args = {
                  a.id(), a.id(), "%" + search + "%", "%" + search + "%", start, end
                };
                int n = (int) count(c, "SELECT COUNT(*) FROM transfers" + where, args);
                var params = new ArrayList<>(Arrays.asList(args));
                params.add(size);
                params.add(page * size);
                return new History(
                    rows(
                            c,
                            "SELECT * FROM transfers"
                                + where
                                + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                            params.toArray())
                        .stream()
                        .map(Store::transfer)
                        .toList(),
                    n);
              });
        });
  }

  public void setActive(String token, long id, boolean active)
      throws RemoteException, BankException {
    if (!cfg.primary()) {
      primary().setActive(token, id, active);
      return;
    }
    safe(
        () -> {
          session(token, Role.ADMIN);
          db.tx(
              c -> {
                if (count(c, "SELECT COUNT(*) FROM accounts WHERE id=?", id) == 0)
                  throw new BankException("Tài khoản không tồn tại");
                exec(c, "UPDATE accounts SET active=?,version=version+1 WHERE id=?", active, id);
                db.append(
                    c,
                    "ACCOUNT_STATE",
                    new Payload(
                        List.of(),
                        List.of(image(rows(c, "SELECT * FROM accounts WHERE id=?", id).get(0))),
                        null),
                    cfg.peers());
                return null;
              });
          return null;
        });
  }

  public ResetResult resetBalances(String token, String requestId)
      throws RemoteException, BankException {
    if (!cfg.primary()) return primary().resetBalances(token, requestId);
    return safe(
        () -> {
          var actor = session(token, Role.ADMIN);
          return db.resetBalances(requestId, actor.user());
        });
  }

  public ChangeResult setBalance(
      String token, long account, BigDecimal balance, long expectedVersion, String requestId)
      throws RemoteException, BankException {
    if (!cfg.primary())
      return primary().setBalance(token, account, balance, expectedVersion, requestId);
    return safe(
        () -> {
          var actor = session(token, Role.ADMIN);
          return db.setBalance(account, balance, expectedVersion, requestId, actor.user());
        });
  }

  public ChangeResult submitChange(String secret, ChangeRequest request) throws BankException {
    internal(secret);
    if (!cfg.primary()) throw new BankException("Only PRIMARY confirms changes");
    return safe(
        () -> {
          try {
            return db.accept(request);
          } catch (Exception failure) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                .error(
                    "[CHANGE_FAILURE] primary={} origin={} request={} table={} id={} reason={}",
                    cfg.id(),
                    request == null ? "unknown" : request.origin(),
                    request == null ? "unknown" : request.requestId(),
                    request == null ? "unknown" : request.table(),
                    request == null ? 0 : request.rowId(),
                    failureReason(failure));
            throw failure;
          }
        });
  }

  public List<Event> recoveryEvents(String secret, long after, int limit) throws BankException {
    internal(secret);
    if (!cfg.primary() || after < 0 || limit < 1 || limit > 500)
      throw new BankException("Invalid recovery event request");
    return safe(
        () ->
            db.read(
                c ->
                    rows(
                            c,
                            "SELECT * FROM replication_events WHERE seq>? ORDER BY seq LIMIT ?",
                            after,
                            limit)
                        .stream()
                        .map(Store::event)
                        .toList()));
  }

  void cycle() {
    try {
      if (cfg.primary()) replicate();
      else relayChanges();
    } catch (Exception ex) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .error("[CYCLE] node={} failure={}", cfg.id(), ex.getClass().getSimpleName());
    }
  }

  void relayChanges() throws Exception {
    db.captureDirectUpdates();
    for (ChangeRequest request : db.pendingChanges()) {
      if (Thread.currentThread().isInterrupted()) return;
      try {
        Replication remote =
            (Replication)
                LocateRegistry.getRegistry(
                        cfg.host(cfg.get("primary.id")),
                        cfg.port(cfg.get("primary.id")),
                        new Net.Sockets(cfg.number("timeout.ms")))
                    .lookup("Replication");
        ChangeResult result = remote.submitChange(cfg.secret(), request);
        db.acknowledge(request, result);
      } catch (Exception ex) {
        db.retry(request, ex);
        break;
      }
    }
  }

  private static String failureReason(Exception failure) {
    if (failure instanceof BankException) return failure.getMessage();
    if (failure instanceof SQLException sql)
      return sql.getClass().getSimpleName()
          + " SQLState="
          + sql.getSQLState()
          + " code="
          + sql.getErrorCode();
    return failure.getClass().getSimpleName();
  }

  void internal(String secret) throws BankException {
    if (secret == null
        || !MessageDigest.isEqual(
            cfg.secret().getBytes(java.nio.charset.StandardCharsets.UTF_8),
            secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
      throw new BankException("Xác thực nội bộ thất bại");
  }

  public Node heartbeat(String secret) throws BankException {
    internal(secret);
    return safe(this::local);
  }

  Node local() throws Exception {
    return db.read(
        c ->
            new Node(
                cfg.id(),
                cfg.get("server.role"),
                cfg.get("server.host"),
                cfg.number("rmi.registry.port"),
                true,
                sequence(c),
                cfg.primary()
                    ? count(c, "SELECT COUNT(*) FROM replication_deliveries WHERE ack=FALSE")
                    : 0,
                "CSDL " + c.getCatalog() + " • RMI object " + cfg.number("rmi.service.port")));
  }

  public Ack apply(String secret, Event e) throws BankException {
    internal(secret);
    if (cfg.primary()) throw new BankException("PRIMARY không nhận sự kiện replica");
    return safe(
        () -> {
          try {
            return db.apply(e);
          } catch (Exception failure) {
            String accounts =
                e == null || e.payload() == null
                    ? "unknown"
                    : e.payload().accounts().stream()
                        .map(a -> Long.toString(a.id()))
                        .toList()
                        .toString();
            String users =
                e == null || e.payload() == null
                    ? "unknown"
                    : e.payload().users().stream()
                        .map(u -> Long.toString(u.id()))
                        .toList()
                        .toString();
            org.slf4j.LoggerFactory.getLogger(getClass())
                .error(
                    "[APPLY_FAILURE] node={} event={} accounts={} users={} reason={}",
                    cfg.id(),
                    e == null ? "unknown" : e.id(),
                    accounts,
                    users,
                    failureReason(failure));
            throw failure;
          }
        });
  }

  public Snapshot snapshot(String token) throws RemoteException, BankException {
    authorizeAdmin(token);
    return safe(
        () ->
            db.read(
                c -> {
                  var nodes = new ArrayList<Node>();
                  nodes.add(local());
                  for (String id : cfg.peers())
                    nodes.add(
                        health.getOrDefault(
                            id,
                            new Node(
                                id,
                                "REPLICA",
                                cfg.host(id),
                                cfg.port(id),
                                false,
                                0,
                                0,
                                "Chưa nhận heartbeat")));
                  if (!cfg.primary()) {
                    try {
                      Snapshot p = primary().snapshot(token);
                      nodes = new ArrayList<>(p.nodes());
                      Node here = local();
                      long lag = Math.max(0, p.local().sequence() - here.sequence());
                      nodes.replaceAll(
                          n ->
                              n.id().equals(cfg.id())
                                  ? new Node(
                                      here.id(),
                                      here.role(),
                                      here.host(),
                                      here.port(),
                                      true,
                                      here.sequence(),
                                      lag,
                                      here.detail())
                                  : n);
                    } catch (Exception ignored) {
                    }
                  }
                  return new Snapshot(
                      local(),
                      nodes,
                      rows(
                              c,
                              "SELECT a.*,u.full_name FROM accounts a JOIN users u ON a.user_id=u.id ORDER BY a.id")
                          .stream()
                          .map(Store::account)
                          .toList(),
                      rows(c, "SELECT * FROM transfers ORDER BY created_at DESC LIMIT 1000")
                          .stream()
                          .map(Store::transfer)
                          .toList(),
                      rows(c, "SELECT * FROM sync_logs ORDER BY created_at DESC LIMIT 200").stream()
                          .map(m -> str(m, "created_at") + " " + str(m, "message"))
                          .toList(),
                      publicSettings());
                }));
  }

  private Map<String, String> publicSettings() {
    Map<String, String> result = new TreeMap<>();
    for (String key :
        List.of(
            "server.id",
            "server.role",
            "server.host",
            "rmi.registry.port",
            "rmi.service.port",
            "java.rmi.server.hostname",
            "primary.id",
            "peers",
            "heartbeat.ms",
            "retry.base.ms",
            "retry.max.ms",
            "timeout.ms",
            "session.minutes")) result.put(key, cfg.get(key));
    return result;
  }

  private Node safeLocal() {
    try {
      return local();
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  public List<Delivery> deliveries(String token, String transferId)
      throws RemoteException, BankException {
    if (!cfg.primary()) return primary().deliveries(token, transferId);
    return safe(
        () -> {
          session(token, Role.ADMIN);
          return db.read(
              c -> {
                List<Delivery> result = new ArrayList<>();
                for (var r :
                    rows(c, "SELECT * FROM replication_events WHERE event_type='TRANSFER'")) {
                  Event e = event(r);
                  if (e.payload().transfer().id().equals(transferId))
                    for (var d :
                        rows(c, "SELECT * FROM replication_deliveries WHERE seq=?", e.sequence()))
                      result.add(
                          new Delivery(
                              str(d, "node_id"),
                              e.sequence(),
                              bool(d, "ack"),
                              (int) num(d, "attempts"),
                              str(d, "last_error")));
                }
                return result;
              });
        });
  }

  public void resync(String token) throws RemoteException, BankException {
    if (!cfg.primary()) {
      primary().resync(token);
      return;
    }
    authorizeAdmin(token);
    nextTry.clear();
    worker.execute(this::replicate);
  }

  long safeSequence() {
    try {
      return db.read(Store::sequence);
    } catch (Exception e) {
      return 0;
    }
  }

  void replicate() {
    try {
      db.captureDirectUpdates();
    } catch (Exception ex) {
      org.slf4j.LoggerFactory.getLogger(getClass()).error("Không thể ghi sự kiện UPDATE MySQL", ex);
      return;
    }
    for (String peer : cfg.peers()) {
      if (Thread.currentThread().isInterrupted()) return;
      if (System.currentTimeMillis() < nextTry.getOrDefault(peer, 0L)) continue;
      try {
        Replication remote =
            (Replication)
                LocateRegistry.getRegistry(
                        cfg.host(peer), cfg.port(peer), new Net.Sockets(cfg.number("timeout.ms")))
                    .lookup("Replication");
        Node n = remote.heartbeat(cfg.secret());
        long max = db.read(Store::sequence);
        if (n.sequence() > max)
          throw new BankException("Replica vượt sequence PRIMARY; CSDL không tương thích");
        db.tx(
            c -> {
              exec(
                  c,
                  "UPDATE replication_deliveries SET ack=FALSE WHERE node_id=? AND seq>?",
                  peer,
                  n.sequence());
              return null;
            });
        var events =
            db.read(
                c ->
                    rows(
                        c,
                        "SELECT * FROM replication_events WHERE seq>? ORDER BY seq LIMIT 50",
                        n.sequence()));
        long applied = n.sequence();
        // Revalidate the last applied event, detecting another cluster with the same sequence.
        if (applied > 0) {
          long at = applied;
          Event prev =
              db.read(
                  c -> event(rows(c, "SELECT * FROM replication_events WHERE seq=?", at).get(0)));
          remote.apply(cfg.secret(), prev);
        }
        for (var row : events) {
          if (Thread.currentThread().isInterrupted()) return;
          Event e = event(row);
          db.tx(
              c -> {
                log(c, "[RMI] Gửi EVT=" + e.id() + " tới " + peer);
                return null;
              });
          Ack ack = remote.apply(cfg.secret(), e);
          if (ack.sequence() < e.sequence()) throw new BankException("ACK không hợp lệ");
          applied = e.sequence();
          db.tx(
              c -> {
                exec(
                    c,
                    "UPDATE replication_deliveries SET ack=TRUE,attempts=attempts+1,last_error='' WHERE seq=? AND node_id=?",
                    e.sequence(),
                    peer);
                log(c, "[ACK] " + peer + " đã áp dụng EVT=" + e.id());
                return null;
              });
        }
        long done = applied;
        db.tx(
            c -> {
              exec(
                  c,
                  "UPDATE replication_deliveries SET ack=TRUE,last_error='' WHERE node_id=? AND seq<=?",
                  peer,
                  done);
              return null;
            });
        health.put(
            peer,
            new Node(
                peer,
                n.role(),
                n.host(),
                n.port(),
                true,
                done,
                max - done,
                (done == max ? "Đã đồng bộ" : "Đang gửi bù") + " • " + n.detail()));
        failures.remove(peer);
        nextTry.remove(peer);
      } catch (Exception e) {
        int attempt = failures.merge(peer, 1, Integer::sum);
        long delay =
            Math.min(
                cfg.number("retry.max.ms"),
                cfg.number("retry.base.ms") * (1L << Math.min(10, attempt - 1)));
        long next = System.currentTimeMillis() + delay;
        nextTry.put(peer, next);
        String message = e instanceof BankException ? e.getMessage() : "Mất kết nối RMI";
        Node prev = health.get(peer);
        health.put(
            peer,
            new Node(
                peer,
                "REPLICA",
                cfg.host(peer),
                cfg.port(peer),
                false,
                prev == null ? 0 : prev.sequence(),
                Math.max(0, safeSequence() - (prev == null ? 0 : prev.sequence())),
                message));
        try {
          db.tx(
              c -> {
                exec(
                    c,
                    "UPDATE replication_deliveries SET attempts=attempts+1,next_attempt=?,last_error=? WHERE node_id=? AND ack=FALSE",
                    next,
                    message,
                    peer);
                log(c, "[RETRY] " + peer + ": " + message);
                return null;
              });
        } catch (Exception ignored) {
          org.slf4j.LoggerFactory.getLogger(getClass()).error("Không thể lưu trạng thái outbox");
        }
      }
    }
  }

  public void close() {
    worker.shutdownNow();
    try {
      worker.awaitTermination(cfg.number("timeout.ms") * 2L + 1000, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    sessions.clear();
  }
}
