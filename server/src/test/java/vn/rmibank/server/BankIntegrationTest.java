package vn.rmibank.server;

import static org.junit.jupiter.api.Assertions.*;
import static vn.rmibank.server.Store.*;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.rmi.registry.*;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.mindrot.jbcrypt.BCrypt;
import vn.rmibank.common.Api.*;
import vn.rmibank.common.Net;

class BankIntegrationTest {
  Store db;
  BankService bank;
  Config cfg;
  String token, other, admin;
  static final String PASSWORD = "test-only-password";
  static final String SECRET = "test-only-internal-secret-123456789";

  static Config config(String id, String url, int p1, int p2, int p3) {
    Properties p = new Properties();
    p.setProperty("server.id", id);
    p.setProperty("server.role", id.equals("1") ? "PRIMARY" : "REPLICA");
    p.setProperty("server.host", "127.0.0.1");
    p.setProperty("jdbc.url", url);
    p.setProperty(
        "jdbc.user",
        url.startsWith("jdbc:mysql:")
            ? System.getenv().getOrDefault("MYSQL_TEST_USER", "root")
            : "sa");
    p.setProperty(
        "jdbc.password",
        url.startsWith("jdbc:mysql:")
            ? System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", "")
            : "");
    p.setProperty("peers", "2,3");
    p.setProperty("primary.id", "1");
    p.setProperty("replication.secret", SECRET);
    p.setProperty("timeout.ms", "1000");
    p.setProperty("heartbeat.ms", "100");
    p.setProperty("retry.base.ms", "1");
    p.setProperty("retry.max.ms", "10");
    p.setProperty("session.minutes", "60");
    p.setProperty("rmi.registry.port", "" + (id.equals("1") ? p1 : id.equals("2") ? p2 : p3));
    p.setProperty("rmi.service.port", "0");
    p.setProperty("java.rmi.server.hostname", "127.0.0.1");
    for (int i = 1; i <= 3; i++) {
      p.setProperty("node." + i + ".host", "127.0.0.1");
      p.setProperty("node." + i + ".port", "" + (i == 1 ? p1 : i == 2 ? p2 : p3));
    }
    return new Config(p);
  }

  final List<String> createdSchemas = new ArrayList<>();

  String url() throws Exception {
    String server = System.getProperty("bank.test.mysql");
    if (server == null)
      return "jdbc:h2:mem:"
          + UUID.randomUUID()
          + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    String schema = "rmi_test_" + UUID.randomUUID().toString().replace("-", "");
    try (var c =
            java.sql.DriverManager.getConnection(
                server,
                System.getenv().getOrDefault("MYSQL_TEST_USER", "root"),
                System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", ""));
        var st = c.createStatement()) {
      st.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
    }
    createdSchemas.add(schema);
    return server + schema + "?connectionTimeZone=UTC&characterEncoding=UTF-8";
  }

  @BeforeEach
  void setup() throws Exception {
    cfg = config("1", url(), freePort(), freePort(), freePort());
    db = new Store(cfg);
    bank = new BankService(db, cfg);
    String hash = BCrypt.hashpw(PASSWORD, BCrypt.gensalt(4));
    db.tx(
        c -> {
          List<UserImage> users = new ArrayList<>();
          List<AccountImage> accounts = new ArrayList<>();
          for (int i = 1; i <= 3; i++) {
            String role = i == 3 ? "ADMIN" : "CUSTOMER";
            UserImage u =
                new UserImage(i, "user" + i, "Người dùng " + i, "0901234567", hash, role, true);
            users.add(u);
            exec(
                c,
                "INSERT INTO users VALUES(?,?,?,?,?,?,?)",
                i,
                u.username(),
                u.name(),
                u.phone(),
                hash,
                role,
                true);
            if (i < 3) {
              AccountImage a = new AccountImage(100000 + i, i, new BigDecimal("1000000"), 1, true);
              accounts.add(a);
              exec(c, "INSERT INTO accounts VALUES(?,?,?,?,?)", a.id(), i, a.balance(), 1, true);
            }
          }
          db.append(c, "BOOTSTRAP", new Payload(users, accounts, null), cfg.peers());
          return null;
        });
    token = bank.login("user1", PASSWORD, Role.CUSTOMER).token();
    other = bank.login("user2", PASSWORD, Role.CUSTOMER).token();
    admin = bank.login("user3", PASSWORD, Role.ADMIN).token();
  }

  @AfterEach
  void close() throws Exception {
    if (bank != null) bank.close();
    if (db != null) db.close();
    for (String schema : createdSchemas) {
      if (!schema.matches("rmi_test_[a-f0-9]{32}"))
        throw new IllegalStateException("Invalid test schema");
      try (var c =
              java.sql.DriverManager.getConnection(
                  System.getProperty("bank.test.mysql"),
                  System.getenv().getOrDefault("MYSQL_TEST_USER", "root"),
                  System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", ""));
          var st = c.createStatement()) {
        st.execute("DROP DATABASE " + schema);
      }
    }
  }

  static int freePort() throws Exception {
    try (ServerSocket s = new ServerSocket(0)) {
      return s.getLocalPort();
    }
  }

  TransferRequest request(long dest, String amount) {
    return new TransferRequest(
        UUID.randomUUID().toString(), dest, new BigDecimal(amount), "Kiểm thử");
  }

  @Test
  void successfulTransferCommitsOutbox() throws Exception {
    Transfer t = bank.transfer(token, request(100002, "200000"));
    assertEquals("COMMITTED", t.status());
    assertEquals(0, bank.me(token).balance().compareTo(new BigDecimal("800000")));
    assertEquals(0, bank.me(other).balance().compareTo(new BigDecimal("1200000")));
    assertEquals(2, db.read(Store::sequence));
    assertEquals(4, db.<Long>read(c -> count(c, "SELECT COUNT(*) FROM replication_deliveries")));
  }

  @Test
  void insufficientFunds() throws Exception {
    assertThrows(BankException.class, () -> bank.transfer(token, request(100002, "1000001")));
    assertEquals(1, db.read(Store::sequence));
  }

  @Test
  void recipientMissing() {
    assertThrows(BankException.class, () -> bank.transfer(token, request(999999, "1")));
  }

  @Test
  void selfTransfer() {
    assertThrows(BankException.class, () -> bank.transfer(token, request(100001, "1")));
  }

  @Test
  void invalidMoney() {
    for (String v : List.of("0", "-1", "0.1", "1000000001"))
      assertThrows(BankException.class, () -> bank.transfer(token, request(100002, v)));
  }

  @Test
  void duplicateRequestAndMismatch() throws Exception {
    TransferRequest r = request(100002, "200000");
    Transfer t = bank.transfer(token, r);
    assertEquals(t, bank.transfer(token, r));
    assertEquals(t, bank.findRequest(token, r.requestId()));
    assertThrows(
        BankException.class,
        () ->
            bank.transfer(
                token, new TransferRequest(r.requestId(), 100002, new BigDecimal("1"), r.note())));
    assertEquals(1, bank.history(token, "", "", "", 0, 20).total());
    assertNull(bank.findRequest(other, r.requestId()));
  }

  @Test
  void concurrentTransfersCannotOverdraw() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Callable<Boolean> work =
          () -> {
            try {
              bank.transfer(token, request(100002, "800000"));
              return true;
            } catch (BankException e) {
              return false;
            }
          };
      List<Future<Boolean>> result = pool.invokeAll(List.of(work, work));
      assertNotEquals(result.get(0).get(), result.get(1).get());
      assertEquals(0, bank.me(token).balance().compareTo(new BigDecimal("200000")));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void errorAfterDebitRollsBack() throws Exception {
    bank.afterDebit =
        () -> {
          throw new IllegalStateException("injected fault");
        };
    BankException failure =
        assertThrows(BankException.class, () -> bank.transfer(token, request(100002, "200000")));
    assertFalse(failure.definitive());
    assertEquals(0, bank.me(token).balance().compareTo(new BigDecimal("1000000")));
    assertEquals(0, bank.history(token, "", "", "", 0, 20).total());
    assertEquals(1, db.read(Store::sequence));
  }

  @Test
  void permissionsSessionsAndLocking() throws Exception {
    assertThrows(BankException.class, () -> bank.snapshot(token));
    assertThrows(BankException.class, () -> bank.setActive(token, 100002, false));
    assertThrows(BankException.class, () -> bank.login("user1", PASSWORD, Role.ADMIN));
    bank.setActive(admin, 100001, false);
    assertThrows(BankException.class, () -> bank.me(token));
    bank.setActive(admin, 100001, true);
    bank.logout(token);
    assertThrows(BankException.class, () -> bank.me(token));
    bank.sessions.put("expired", new BankService.Session(1, Role.CUSTOMER, 0));
    assertThrows(BankException.class, () -> bank.me("expired"));
  }

  @Test
  void registerNormalizesAndReplicatesZeroBalance() throws Exception {
    Account a =
        bank.register(new Registration("Nguyễn Văn A", " New_User ", "0901234567", PASSWORD));
    assertEquals(0, a.balance().signum());
    assertNotNull(bank.login("new_USER", PASSWORD, Role.CUSTOMER));
    assertThrows(
        BankException.class,
        () -> bank.register(new Registration("A", "new_user", "0901234567", PASSWORD)));
    assertThrows(
        BankException.class, () -> bank.register(new Registration("A", "another", "x", PASSWORD)));
  }

  Event eventAt(long seq) throws Exception {
    return db.read(c -> event(rows(c, "SELECT * FROM replication_events WHERE seq=?", seq).get(0)));
  }

  @Test
  void duplicateAndOutOfOrderReplication() throws Exception {
    bank.transfer(token, request(100002, "200000"));
    try (Store replica = new Store(config("2", url(), 1, 2, 3))) {
      assertThrows(BankException.class, () -> replica.apply(eventAt(2)));
      replica.apply(eventAt(1));
      replica.apply(eventAt(2));
      replica.apply(eventAt(2));
      assertEquals(2, replica.read(Store::sequence));
      assertEquals(db.read(Store::fingerprint), replica.read(Store::fingerprint));
      Event original = eventAt(2);
      assertThrows(
          BankException.class,
          () ->
              replica.apply(
                  new Event("wrong", 2, original.type(), original.time(), 1, original.payload())));
    }
  }

  @Test
  void restartKeepsOutboxAndDetectsTampering() throws Exception {
    bank.transfer(token, request(100002, "200000"));
    String url = cfg.get("jdbc.url");
    db.close();
    db = new Store(cfg);
    assertEquals(2, db.read(Store::sequence));
    assertEquals(
        4,
        db.<Long>read(
            c -> count(c, "SELECT COUNT(*) FROM replication_deliveries WHERE ack=FALSE")));
    db.read(
        c -> {
          exec(c, "UPDATE accounts SET balance=1 WHERE id=100001");
          return null;
        });
    assertThrows(IllegalStateException.class, () -> new Store(cfg));
  }

  @Test
  void realRmiOfflineRecoveryAndForwarding() throws Exception {
    System.setProperty("java.rmi.server.hostname", "127.0.0.1");
    Registry r1 = LocateRegistry.createRegistry(cfg.port("1")),
        r2 = LocateRegistry.createRegistry(cfg.port("2")),
        r3 = null;
    Store db2 = new Store(config("2", url(), cfg.port("1"), cfg.port("2"), cfg.port("3")));
    Config c3 = config("3", url(), cfg.port("1"), cfg.port("2"), cfg.port("3"));
    Store db3 = new Store(c3);
    BankService
        b2 =
            new BankService(
                db2,
                config("2", db2.pool.getJdbcUrl(), cfg.port("1"), cfg.port("2"), cfg.port("3"))),
        b3 = new BankService(db3, c3);
    try {
      var stub1 = UnicastRemoteObject.exportObject(bank, 0, new Net.Sockets(1000), null);
      r1.rebind("Bank", stub1);
      r1.rebind("Replication", stub1);
      var stub2 = UnicastRemoteObject.exportObject(b2, 0, new Net.Sockets(1000), null);
      r2.rebind("Bank", stub2);
      r2.rebind("Replication", stub2);
      Bank client = (Bank) LocateRegistry.getRegistry("127.0.0.1", cfg.port("2")).lookup("Bank");
      BankException duplicate =
          assertThrows(BankException.class, () -> client.login("user1", PASSWORD, Role.CUSTOMER));
      assertTrue(duplicate.getMessage().contains("Tài khoản đang có phiên đăng nhập hoạt động"));
      bank.logout(token);
      Login l = client.login("user1", PASSWORD, Role.CUSTOMER);
      token = l.token();
      client.transfer(l.token(), request(100002, "200000"));
      bank.replicate();
      assertEquals(2, db2.read(Store::sequence));
      assertEquals(0, db3.read(Store::sequence));
      assertEquals(
          2,
          b3.snapshot(admin).nodes().stream()
              .filter(n -> n.id().equals("3"))
              .findFirst()
              .orElseThrow()
              .pending());
      assertTrue(
          bank.deliveries(admin, bank.history(token, "", "", "", 0, 10).rows().get(0).id()).stream()
              .anyMatch(d -> !d.acknowledged()));
      r3 = LocateRegistry.createRegistry(cfg.port("3"));
      var stub3 = UnicastRemoteObject.exportObject(b3, 0, new Net.Sockets(1000), null);
      r3.rebind("Replication", stub3);
      bank.nextTry.clear();
      bank.replicate();
      assertEquals(db.read(Store::fingerprint), db3.read(Store::fingerprint));
      assertEquals(db.read(Store::fingerprint), db2.read(Store::fingerprint));
      assertThrows(BankException.class, () -> b3.heartbeat("invalid"));
      UnicastRemoteObject.unexportObject(bank, true);
      assertThrows(java.rmi.RemoteException.class, () -> client.me(l.token()));
    } finally {
      for (BankService b : List.of(bank, b2, b3))
        try {
          UnicastRemoteObject.unexportObject(b, true);
        } catch (Exception ignored) {
        }
      for (Registry r : Arrays.asList(r1, r2, r3))
        if (r != null) UnicastRemoteObject.unexportObject(r, true);
      b2.close();
      b3.close();
      db2.close();
      db3.close();
    }
  }
}
