package vn.rmibank.server;

import static org.junit.jupiter.api.Assertions.*;
import static vn.rmibank.server.Store.*;

import java.math.BigDecimal;
import java.rmi.registry.*;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.mindrot.jbcrypt.BCrypt;
import vn.rmibank.common.Api.*;
import vn.rmibank.common.Net;

class ChangeIntegrationTest extends BankIntegrationTest {

  @Test
  void mysqlDeleteFromEveryNodeConvergesAndNeverReusesAccountNumbers() throws Exception {
    requireMysql();
    try (Cluster cluster = new Cluster()) {
      long previous = 100002;
      for (int node = 0; node < 3; node++) {
        String username = "delete_node_" + node;
        Account account = bank.register(new Registration("Delete test", username, "0901234567", PASSWORD));
        assertTrue(account.id() > previous);
        previous = account.id();
        String session = bank.login(username, PASSWORD, Role.CUSTOMER).token();
        cluster.sync();
        raw(cluster.stores[node], "DELETE FROM accounts WHERE id=" + account.id());
        cluster.sync();
        for (Store store : cluster.stores) {
          assertEquals(0L, (long) store.read(c -> count(c, "SELECT COUNT(*) FROM accounts WHERE id=?", account.id())));
          assertEquals(1L, (long) store.read(c -> count(c, "SELECT COUNT(*) FROM deleted_accounts WHERE id=?", account.id())));
          assertEquals(1L, (long) store.read(c -> count(c, "SELECT COUNT(*) FROM users WHERE username=?", username)));
        }
        assertThrows(BankException.class, () -> bank.me(session));
        assertThrows(BankException.class, () -> bank.login(username, PASSWORD, Role.CUSTOMER));
        cluster.equal();
      }
    }
  }

  @Test
  void mysqlDeletePreservesHistoryAndRejectsMoneyLoss() throws Exception {
    requireMysql();
    try (Cluster cluster = new Cluster()) {
      assertThrows(java.sql.SQLException.class, () -> raw(db, "DELETE FROM accounts WHERE id=100001"));
      bank.transfer(token, request(100002, "1000000"));
      cluster.sync();
      raw(cluster.stores[2], "DELETE FROM accounts WHERE id=100001");
      cluster.sync();
      for (Store store : cluster.stores) {
        assertEquals(0L, (long) store.read(c -> count(c, "SELECT COUNT(*) FROM accounts WHERE id=100001")));
        assertEquals(1L, (long) store.read(c -> count(c, "SELECT COUNT(*) FROM transfers WHERE sender=100001")));
        assertEquals(1L, (long) store.read(c -> count(c, "SELECT COUNT(*) FROM account_keys WHERE id=100001")));
      }
      assertThrows(java.sql.SQLException.class, () -> raw(db, "DELETE FROM users WHERE id=2"));
      assertThrows(java.sql.SQLException.class, () -> raw(db, "DELETE FROM transfers"));
      cluster.restart(2);
      cluster.sync();
      cluster.equal();
      var all = db.read(c -> rows(c, "SELECT * FROM replication_events ORDER BY seq").stream().map(Store::event).toList());
      assertEquals(db.read(Store::fingerprint), new Maintenance.Replay(all, all.size(), cfg).fingerprint());
    }
  }

  @Test
  void mysqlMultirowDeleteRollsBackAndSurvivesOfflineReplicaRestart() throws Exception {
    requireMysql();
    try (Cluster cluster = new Cluster()) {
      Account a = bank.register(new Registration("Delete A", "delete_a", "0901234567", PASSWORD));
      Account b = bank.register(new Registration("Delete B", "delete_b", "0901234567", PASSWORD));
      cluster.sync();
      try (var c = cluster.stores[1].pool.getConnection()) {
        c.setAutoCommit(false);
        exec(c, "DELETE FROM accounts WHERE id IN (?,?)", a.id(), b.id());
        c.rollback();
      }
      assertEquals(0, cluster.stores[1].pendingChanges().size());
      cluster.offline(2);
      raw(cluster.stores[1], "DELETE FROM accounts WHERE id IN (" + a.id() + "," + b.id() + ")");
      assertEquals(2, cluster.stores[1].pendingChanges().size());
      cluster.restart(1);
      assertEquals(2, cluster.stores[1].pendingChanges().size());
      cluster.sync();
      cluster.restart(2);
      cluster.sync();
      for (Store store : cluster.stores)
        assertEquals(0L, (long) store.read(c -> count(c, "SELECT COUNT(*) FROM accounts WHERE id IN (?,?)", a.id(), b.id())));
      cluster.equal();
    }
  }

  @Test
  void mysqlDeleteLosesConflictToConcurrentTransferWithoutLosingMoney() throws Exception {
    requireMysql();
    try (Cluster cluster = new Cluster()) {
      Account a = bank.register(new Registration("Delete conflict", "delete_conflict", "0901234567", PASSWORD));
      cluster.sync();
      raw(cluster.stores[2], "DELETE FROM accounts WHERE id=" + a.id());
      bank.transfer(token, request(a.id(), "1000"));
      cluster.sync();
      for (Store store : cluster.stores) {
        assertEquals(1000, balance(store, a.id()));
        assertEquals(999000, balance(store, 100001));
      }
      assertEquals(1L, (long) cluster.stores[2].read(c -> count(c, "SELECT COUNT(*) FROM change_requests WHERE row_id=? AND state='CONFLICT'", a.id())));
      cluster.equal();
    }
  }

  @Test
  void mysqlLateUpdateAndRepeatedDeleteCannotResurrectAccount() throws Exception {
    requireMysql();
    try (Cluster cluster = new Cluster()) {
      Account a = bank.register(new Registration("Delete replay", "delete_replay", "0901234567", PASSWORD));
      cluster.sync();
      raw(cluster.stores[1], "DELETE FROM accounts WHERE id=" + a.id());
      ChangeRequest request = cluster.stores[1].pendingChanges().get(0);
      ChangeResult first = bank.submitChange(SECRET, request);
      assertEquals(first, bank.submitChange(SECRET, request));
      assertThrows(BankException.class, () -> bank.submitChange(SECRET,
          new ChangeRequest(request.requestId(), request.origin(), request.table(), request.rowId(), "{}", request.after())));
      raw(cluster.stores[2], "UPDATE accounts SET balance=500 WHERE id=" + a.id());
      cluster.sync();
      for (Store store : cluster.stores) {
        assertEquals(0L, (long) store.read(c -> count(c, "SELECT COUNT(*) FROM accounts WHERE id=?", a.id())));
        assertEquals(1L, (long) store.read(c -> count(c, "SELECT COUNT(*) FROM deleted_accounts WHERE id=?", a.id())));
      }
      var deletes = db.read(c -> rows(c, "SELECT * FROM replication_events WHERE event_type='ACCOUNT_DELETE' ORDER BY seq"));
      Event last = Store.event(deletes.get(deletes.size()-1));
      cluster.stores[2].apply(last);
      cluster.equal();
    }
  }

  @Test
  void mysqlMigratesLegacyHistoryForeignKeysWithoutDeletingHistory() throws Exception {
    requireMysql();
    bank.transfer(token, request(100002, "1000000"));
    bank.close();
    db.close();
    try (var c = java.sql.DriverManager.getConnection(cfg.get("jdbc.url"), cfg.get("jdbc.user"), cfg.p().getProperty("jdbc.password", ""))) {
      List<String> oldNames = new ArrayList<>();
      try (var keys = c.getMetaData().getImportedKeys(c.getCatalog(), null, "transfers")) {
        while (keys.next()) if (keys.getString("PKTABLE_NAME").equals("account_keys")) oldNames.add(keys.getString("FK_NAME"));
      }
      exec(c, "ALTER TABLE transfers ADD CONSTRAINT legacy_sender FOREIGN KEY(sender) REFERENCES accounts(id)");
      exec(c, "ALTER TABLE transfers ADD CONSTRAINT legacy_recipient FOREIGN KEY(recipient) REFERENCES accounts(id)");
      for (String name : oldNames) exec(c, "ALTER TABLE transfers DROP FOREIGN KEY `" + name.replace("`", "``") + "`");
    }
    db = new Store(cfg);
    bank = new BankService(db, cfg);
    raw(db, "DELETE FROM accounts WHERE id=100001");
    db.captureDirectUpdates();
    assertEquals(0L, (long) db.read(c -> count(c, "SELECT COUNT(*) FROM accounts WHERE id=100001")));
    assertEquals(1L, (long) db.read(c -> count(c, "SELECT COUNT(*) FROM transfers")));
    try (var c = db.pool.getConnection(); var keys = c.getMetaData().getImportedKeys(c.getCatalog(), null, "transfers")) {
      int count = 0;
      while (keys.next()) { assertEquals("account_keys", keys.getString("PKTABLE_NAME")); count++; }
      assertEquals(2, count);
    }
  }

  void requireMysql() {
    Assumptions.assumeTrue(
        cfg.get("jdbc.url").startsWith("jdbc:mysql:"), "MySQL triggers require -Dbank.test.mysql");
  }

  void raw(Store store, String sql) throws Exception {
    try (var c = store.pool.getConnection()) {
      exec(c, sql);
    }
  }

  void raw(Config config, String sql) throws Exception {
    try (var c =
        java.sql.DriverManager.getConnection(
            config.get("jdbc.url"),
            config.get("jdbc.user"),
            config.p().getProperty("jdbc.password", ""))) {
      exec(c, sql);
    }
  }

  long balance(Store store, long account) throws Exception {
    return store.read(c -> count(c, "SELECT balance FROM accounts WHERE id=?", account));
  }

  final class Cluster implements AutoCloseable {
    final Store[] stores = new Store[3];
    final BankService[] services = new BankService[3];
    final Config[] configs = new Config[3];
    final Registry[] registries = new Registry[3];

    Cluster() throws Exception {
      System.setProperty("java.rmi.server.hostname", "127.0.0.1");
      stores[0] = db;
      services[0] = bank;
      configs[0] = cfg;
      for (int i = 0; i < 3; i++) {
        if (i > 0) {
          configs[i] = config("" + (i + 1), url(), cfg.port("1"), cfg.port("2"), cfg.port("3"));
          stores[i] = new Store(configs[i]);
          services[i] = new BankService(stores[i], configs[i]);
        }
        registries[i] = LocateRegistry.createRegistry(cfg.port("" + (i + 1)));
        bind(i);
      }
      sync();
    }

    void bind(int i) throws Exception {
      var stub = UnicastRemoteObject.exportObject(services[i], 0, new Net.Sockets(1000), null);
      registries[i].rebind("Bank", stub);
      registries[i].rebind("Replication", stub);
    }

    void offline(int i) throws Exception {
      UnicastRemoteObject.unexportObject(services[i], true);
      services[i].close();
      stores[i].close();
      services[i] = null;
      stores[i] = null;
    }

    void restart(int i) throws Exception {
      if (services[i] != null) offline(i);
      stores[i] = new Store(configs[i]);
      services[i] = new BankService(stores[i], configs[i]);
      bind(i);
      if (i == 0) {
        db = stores[0];
        bank = services[0];
        token = bank.login("user1", PASSWORD, Role.CUSTOMER).token();
        admin = bank.login("user3", PASSWORD, Role.ADMIN).token();
      }
    }

    void sync() throws Exception {
      for (int round = 0; round < 3; round++) {
        for (int i = 1; i < 3; i++) if (services[i] != null) services[i].relayChanges();
        services[0].nextTry.clear();
        services[0].replicate();
      }
    }

    void equal() throws Exception {
      String expected = stores[0].read(Store::fingerprint);
      for (Store store : stores)
        if (store != null) assertEquals(expected, store.read(Store::fingerprint));
    }

    public void close() throws Exception {
      for (int i = 0; i < 3; i++) {
        if (services[i] != null) {
          try {
            UnicastRemoteObject.unexportObject(services[i], true);
          } catch (Exception ignored) {
          }
          if (i > 0) {
            services[i].close();
            stores[i].close();
          }
        }
        if (registries[i] != null) UnicastRemoteObject.unexportObject(registries[i], true);
      }
    }
  }

  @Test
  void resetAllAccountsOnceIncludesLockedAndKeepsHistory() throws Exception {
    try (Cluster cluster = new Cluster()) {
      bank.transfer(token, request(100002, "20000"));
      bank.setActive(admin, 100002, false);
      cluster.sync();
      String users = db.read(c -> JSON.toJson(rows(c, "SELECT * FROM users ORDER BY id")));
      long history = db.read(c -> count(c, "SELECT COUNT(*) FROM transfers"));
      String id = UUID.randomUUID().toString();
      Bank replicaClient = (Bank) cluster.registries[1].lookup("Bank");
      ResetResult reset = replicaClient.resetBalances(admin, id);
      cluster.sync();
      assertEquals(2, reset.accounts());
      assertTrue(
          java.nio.file.Files.exists(java.nio.file.Path.of(reset.backup().split(" sha256=")[0])));
      for (Store store : cluster.stores) {
        assertEquals(100000, balance(store, 100001));
        assertEquals(100000, balance(store, 100002));
        assertEquals(
            1, store.<Long>read(c -> count(c, "SELECT COUNT(*) FROM accounts WHERE active=FALSE")));
        assertEquals(history, store.<Long>read(c -> count(c, "SELECT COUNT(*) FROM transfers")));
        assertEquals(
            users, store.read(c -> JSON.toJson(rows(c, "SELECT * FROM users ORDER BY id"))));
      }
      bank.setActive(admin, 100002, true);
      bank.transfer(token, request(100002, "1000"));
      assertEquals(reset, bank.resetBalances(admin, id));
      assertEquals(reset, bank.resetBalances(admin, UUID.randomUUID().toString()));
      cluster.restart(0);
      assertEquals(reset, bank.resetBalances(admin, id));
      cluster.sync();
      assertEquals(99000, balance(db, 100001));
      assertEquals(101000, balance(db, 100002));
      cluster.equal();
    }
  }

  @Test
  void mysqlSqlUpdatesFromAllThreeNodesConverge() throws Exception {
    requireMysql();
    try (Cluster cluster = new Cluster()) {
      for (int i = 0; i < 3; i++) {
        raw(
            cluster.stores[i],
            "UPDATE accounts SET balance=" + (150000 + i * 10000) + " WHERE id=100001");
        cluster.sync();
        for (Store store : cluster.stores) assertEquals(150000 + i * 10000, balance(store, 100001));
        cluster.equal();
      }
      assertEquals(
          3,
          db.<Long>read(
              c -> count(c, "SELECT COUNT(*) FROM external_requests WHERE state='ACCEPTED'")));
      for (Store store : cluster.stores)
        assertEquals(
            0,
            store.<Long>read(
                c -> count(c, "SELECT COUNT(*) FROM change_requests WHERE state='PENDING'")));
    }
  }

  @Test
  void mysqlMultiRowAccountsAndUsers() throws Exception {
    requireMysql();
    try (Cluster cluster = new Cluster()) {
      raw(cluster.stores[1], "UPDATE accounts SET balance=balance+500 WHERE id IN(100001,100002)");
      raw(cluster.stores[2], "UPDATE users SET full_name=CONCAT(full_name,' SQL')");
      cluster.sync();
      for (Store store : cluster.stores) {
        assertEquals(1000500, balance(store, 100001));
        assertEquals(1000500, balance(store, 100002));
        assertEquals(
            3,
            store.<Long>read(
                c -> count(c, "SELECT COUNT(*) FROM users WHERE full_name LIKE '% SQL'")));
      }
      cluster.equal();
    }
  }

  @Test
  void mysqlOfflineReplicaAndConflictRecovery() throws Exception {
    requireMysql();
    try (Cluster cluster = new Cluster()) {
      cluster.offline(2);
      raw(db, "UPDATE accounts SET balance=150000 WHERE id=100001");
      bank.nextTry.clear();
      bank.replicate();
      assertTrue(
          db.<Long>read(
                  c ->
                      count(
                          c,
                          "SELECT COUNT(*) FROM replication_deliveries WHERE node_id='3' AND ack=FALSE"))
              > 0);
      raw(cluster.configs[2], "UPDATE accounts SET balance=175000 WHERE id=100001");
      cluster.restart(2);
      cluster.sync();
      cluster.equal();
      assertEquals(150000, balance(db, 100001));
      assertEquals(
          1,
          cluster.stores[2].<Long>read(
              c -> count(c, "SELECT COUNT(*) FROM change_requests WHERE state='CONFLICT'")));
    }
  }

  @Test
  void mysqlPendingProposalSurvivesNodeRestart() throws Exception {
    requireMysql();
    try (Cluster cluster = new Cluster()) {
      raw(cluster.stores[2], "UPDATE accounts SET balance=150000 WHERE id=100001");
      assertEquals(1, cluster.stores[2].pendingChanges().size());
      cluster.restart(2);
      assertEquals(1, cluster.stores[2].pendingChanges().size());
      cluster.sync();
      cluster.equal();
      assertEquals(150000, balance(db, 100001));
    }
  }

  @Test
  void mysqlDuplicateProposalIsIdempotentOverRmi() throws Exception {
    requireMysql();
    try (Cluster cluster = new Cluster()) {
      raw(cluster.stores[1], "UPDATE accounts SET balance=150000 WHERE id=100001");
      ChangeRequest request = cluster.stores[1].pendingChanges().get(0);
      Replication remote = (Replication) cluster.registries[0].lookup("Replication");
      ChangeResult first = remote.submitChange(SECRET, request);
      assertEquals(first, remote.submitChange(SECRET, request));
      assertThrows(
          BankException.class,
          () ->
              remote.submitChange(
                  SECRET,
                  new ChangeRequest(
                      request.requestId(),
                      request.origin(),
                      request.table(),
                      request.rowId(),
                      request.before(),
                      request.after().replace("150000", "180000"))));
      cluster.sync();
      cluster.equal();
      assertEquals(2, db.read(Store::sequence));
      assertEquals(150000, balance(db, 100001));
    }
  }

  @Test
  void mysqlConcurrentUpdatesAtTwoReplicasDetectConflict() throws Exception {
    requireMysql();
    try (Cluster cluster = new Cluster()) {
      ExecutorService executor = Executors.newFixedThreadPool(2);
      try {
        var jobs =
            executor.invokeAll(
                List.<Callable<Void>>of(
                    () -> {
                      raw(cluster.stores[1], "UPDATE accounts SET balance=150000 WHERE id=100001");
                      return null;
                    },
                    () -> {
                      raw(cluster.stores[2], "UPDATE accounts SET balance=175000 WHERE id=100001");
                      return null;
                    }));
        for (var job : jobs) job.get();
      } finally {
        executor.shutdownNow();
      }
      cluster.sync();
      assertEquals(150000, balance(db, 100001));
      assertEquals(
          1,
          db.<Long>read(
              c -> count(c, "SELECT COUNT(*) FROM external_requests WHERE state='ACCEPTED'")));
      assertEquals(
          1,
          db.<Long>read(
              c -> count(c, "SELECT COUNT(*) FROM external_requests WHERE state='CONFLICT'")));
      cluster.equal();
    }
  }

  @Test
  void mysqlConcurrentTransferDoesNotUseProvisionalReplicaBalance() throws Exception {
    requireMysql();
    try (Cluster cluster = new Cluster()) {
      ExecutorService executor = Executors.newFixedThreadPool(2);
      try {
        var jobs =
            executor.invokeAll(
                List.<Callable<Void>>of(
                    () -> {
                      raw(cluster.stores[1], "UPDATE accounts SET balance=150000 WHERE id=100001");
                      return null;
                    },
                    () -> {
                      bank.transfer(token, request(100002, "10000"));
                      return null;
                    }));
        for (var job : jobs) job.get();
      } finally {
        executor.shutdownNow();
      }
      cluster.sync();
      assertEquals(990000, balance(db, 100001));
      assertEquals(1010000, balance(db, 100002));
      assertEquals(1, bank.history(token, "", "", "", 0, 20).total());
      assertEquals(
          1,
          db.<Long>read(
              c -> count(c, "SELECT COUNT(*) FROM external_requests WHERE state='CONFLICT'")));
      cluster.equal();
    }
  }

  @Test
  void mysqlUserUpdatesAndAdministrativeApplicationEdits() throws Exception {
    requireMysql();
    try (Cluster cluster = new Cluster()) {
      String password = "changed-test-password", hash = BCrypt.hashpw(password, BCrypt.gensalt(4));
      raw(
          cluster.stores[2],
          "UPDATE users SET username='renamed',full_name='Tên mới',phone='0912345678',password_hash='"
              + hash
              + "' WHERE id=1");
      cluster.sync();
      bank.logout(token);
      assertThrows(BankException.class, () -> bank.login("user1", PASSWORD, Role.CUSTOMER));
      token = bank.login("renamed", password, Role.CUSTOMER).token();
      assertEquals("Tên mới", bank.me(token).name());
      Bank viaReplica = (Bank) cluster.registries[1].lookup("Bank");
      String id = UUID.randomUUID().toString();
      long version = bank.me(token).version();
      ChangeResult first =
          viaReplica.setBalance(admin, 100001, new BigDecimal("150000"), version, id);
      assertEquals(
          first, viaReplica.setBalance(admin, 100001, new BigDecimal("150000"), version, id));
      bank.transfer(token, request(100002, "1000"));
      cluster.sync();
      assertEquals(149000, balance(db, 100001));
      cluster.equal();
    }
  }

  @Test
  void approvedRecoveryBacksUpAndPreservesUntrackedEditsAsProposals() throws Exception {
    bank.transfer(token, request(100002, "1000.0"));
    try (var c = db.pool.getConnection()) {
      if (db.mysql) exec(c, "SET @rmibank_app_write=1");
      exec(c, "UPDATE accounts SET balance=888888 WHERE id=100001");
      if (db.mysql) exec(c, "SET @rmibank_app_write=NULL");
    }
    bank.close();
    db.close();
    assertThrows(IllegalStateException.class, () -> new Store(cfg));
    java.nio.file.Path file =
        java.nio.file.Path.of("target/test-plans/" + UUID.randomUUID() + ".json");
    var plan = Maintenance.audit(cfg, file);
    assertTrue(plan.blockers().isEmpty());
    assertEquals(1, plan.changes().size());
    assertThrows(IllegalArgumentException.class, () -> Maintenance.repair(cfg, file, "wrong-hash"));
    Maintenance.repair(cfg, file, digest(java.nio.file.Files.readString(file)));
    db = new Store(cfg);
    bank = new BankService(db, cfg);
    db.captureDirectUpdates();
    assertEquals(888888, balance(db, 100001));
    admin = bank.login("user3", PASSWORD, Role.ADMIN).token();
    bank.resetBalances(admin, UUID.randomUUID().toString());
    assertEquals(100000, balance(db, 100001));
  }

  @Test
  void mysqlReplicaRecoveryUsesPrimaryHistory() throws Exception {
    requireMysql();
    try (Cluster cluster = new Cluster()) {
      try (var c = cluster.stores[1].pool.getConnection()) {
        exec(c, "SET @rmibank_app_write=1");
        exec(c, "UPDATE accounts SET balance=777777 WHERE id=100001");
        exec(c, "SET @rmibank_app_write=NULL");
      }
      cluster.offline(1);
      java.nio.file.Path file =
          java.nio.file.Path.of("target/test-plans/" + UUID.randomUUID() + ".json");
      Maintenance.audit(cluster.configs[1], file);
      Maintenance.repair(cluster.configs[1], file, digest(java.nio.file.Files.readString(file)));
      cluster.restart(1);
      cluster.sync();
      assertEquals(777777, balance(db, 100001));
      cluster.equal();
    }
  }

  @Test
  void mysqlOwnershipAndUsernameChainsPreserveExistingKeys() throws Exception {
    requireMysql();
    try (Cluster cluster = new Cluster()) {
      raw(cluster.stores[1], "UPDATE accounts SET user_id=3 WHERE id=100001");
      raw(cluster.stores[1], "UPDATE accounts SET user_id=1 WHERE id=100002");
      raw(cluster.stores[1], "UPDATE accounts SET user_id=2 WHERE id=100001");
      raw(cluster.stores[2], "UPDATE users SET username='temporary_name' WHERE id=1");
      raw(cluster.stores[2], "UPDATE users SET username='user1' WHERE id=2");
      raw(cluster.stores[2], "UPDATE users SET username='user2' WHERE id=1");
      cluster.sync();
      cluster.equal();
      assertEquals(100002, bank.me(token).id());
      assertEquals(3, db.<Long>read(c -> count(c, "SELECT COUNT(*) FROM users")));
      assertEquals(
          6,
          db.<Long>read(
              c -> count(c, "SELECT COUNT(*) FROM external_requests WHERE state='ACCEPTED'")));
    }
  }

  @Test
  void mysqlPrimaryPendingSurvivesRestartAndAutoWorkersCatchUp() throws Exception {
    requireMysql();
    try (Cluster cluster = new Cluster()) {
      raw(db, "UPDATE accounts SET balance=150000 WHERE id=100001");
      cluster.restart(0);
      assertEquals(150000, balance(db, 100001));
      for (BankService service : cluster.services) service.start();
      raw(cluster.stores[2], "UPDATE accounts SET balance=160000 WHERE id=100002");
      long deadline = System.currentTimeMillis() + 15000;
      boolean converged = false;
      while (System.currentTimeMillis() < deadline) {
        converged = true;
        for (Store store : cluster.stores)
          if (store.<Long>read(
                  c -> count(c, "SELECT balance FROM confirmed_accounts WHERE id=100002"))
              != 160000) converged = false;
        if (converged) break;
        Thread.sleep(80);
      }
      assertTrue(
          converged, "Background workers must relay pending changes and catch up automatically");
    }
  }
}
