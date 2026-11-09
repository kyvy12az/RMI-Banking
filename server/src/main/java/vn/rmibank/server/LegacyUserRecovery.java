package vn.rmibank.server;

import static vn.rmibank.server.Store.*;

import java.sql.*;
import java.util.*;
import vn.rmibank.common.Api.*;

/** Explicit, content-bound recovery of legacy phantom-user-only events. */
public final class LegacyUserRecovery {
  static final String KEY = "recovery.legacy-user-noop-digests";

  static boolean candidate(Event e) {
    return e.schema() == 1 && "DIRECT_UPDATE".equals(e.type())
        && e.payload() != null && e.payload().users() != null
        && !e.payload().users().isEmpty()
        && e.payload().users().stream().allMatch(u -> u.id() == 0)
        && e.payload().accounts() != null && e.payload().accounts().isEmpty()
        && e.payload().transfer() == null;
  }

  static boolean approved(Event e, Config cfg) throws Exception {
    return candidate(e) && Arrays.stream(cfg.p().getProperty(KEY, "").split(","))
        .map(String::trim).anyMatch(digest(JSON.toJson(e))::equalsIgnoreCase);
  }

  static List<UserImage> users(Event e, Config cfg) throws Exception {
    return approved(e, cfg) ? List.of() : e.payload().users();
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1 && !(args.length == 3 && args[1].equals("--publish-current-user"))) throw new IllegalArgumentException("LegacyUserRecovery <primary.properties> [--publish-current-user <id>]");
    Config cfg = Config.load(args[0]);
    if (!cfg.primary()) throw new IllegalArgumentException("Run proof against PRIMARY only");
    if (args.length == 3) {
      long id = Long.parseLong(args[2]);
      if (id < 1) throw new IllegalArgumentException("Existing positive user ID required");
      String row;
      try (Connection c = DriverManager.getConnection(cfg.get("jdbc.url"), cfg.get("jdbc.user"), cfg.p().getProperty("jdbc.password", ""))) {
        c.setAutoCommit(false);
        rows(c, "SELECT * FROM node_state WHERE id=1 FOR UPDATE");
        var current = rows(c, "SELECT * FROM users WHERE id=? FOR UPDATE", id);
        var confirmed = rows(c, "SELECT * FROM confirmed_users WHERE id=?", id);
        if (current.size() != 1 || confirmed.size() != 1
            || !ChangeCapture.normalized("users", current.get(0)).equals(ChangeCapture.normalized("users", confirmed.get(0)))
            || !fingerprint(c).equals(str(rows(c, "SELECT * FROM node_state WHERE id=1").get(0), "fingerprint")))
          throw new IllegalStateException("PRIMARY state unconfirmed; refusing publication");
        var desired = new TreeMap<>(current.get(0));
        ChangeCapture.validate(c, "users", desired, current.get(0));
        if (!ChangeCapture.normalized("users", desired).equals(ChangeCapture.normalized("users", current.get(0))))
          throw new IllegalStateException("Validation would change user; refusing publication");
        System.out.println("Backup=" + Backup.write(c, cfg, "before-republish-confirmed-user"));
        row = JSON.toJson(current.get(0));
        c.rollback();
      }
      Replication primary = (Replication) java.rmi.registry.LocateRegistry.getRegistry(
          cfg.host(cfg.get("primary.id")), cfg.port(cfg.get("primary.id")),
          new vn.rmibank.common.Net.Sockets(cfg.number("timeout.ms"))).lookup("Replication");
      ChangeResult result = primary.submitChange(cfg.secret(), new ChangeRequest(
          UUID.randomUUID().toString(), cfg.id(), "users", id, row, row));
      System.out.println("REPUBLISHED user=" + id + " status=" + result.status() + " sequence=" + result.sequence());
      return;
    }
    try (Connection c = DriverManager.getConnection(cfg.get("jdbc.url"), cfg.get("jdbc.user"),
        cfg.p().getProperty("jdbc.password", ""))) {
      c.setAutoCommit(false);
      try {
        rows(c, "SELECT * FROM node_state WHERE id=1 FOR UPDATE");
        rows(c, "SELECT * FROM users ORDER BY id FOR UPDATE");
        rows(c, "SELECT * FROM accounts ORDER BY id FOR UPDATE");
        String backup = Backup.write(c, cfg, "legacy-user-recovery-proof");
        System.out.println("Backup=" + backup);
        long seq = sequence(c);
        String actual = fingerprint(c);
        String stored = str(rows(c, "SELECT * FROM node_state WHERE id=1").get(0), "fingerprint");
        if (!actual.equals(stored)) throw new IllegalStateException("PRIMARY fingerprint mismatch; no approval produced");
        if (count(c, "SELECT COUNT(*) FROM users WHERE id=0") != 0
            || count(c, "SELECT COUNT(*) FROM accounts WHERE user_id=0") != 0)
          throw new IllegalStateException("User 0 is present/referenced; no approval produced");
        if (count(c, "SELECT COUNT(*) FROM change_requests WHERE state='PENDING'") != 0)
          throw new IllegalStateException("PRIMARY has pending SQL changes; wait before proof");
        List<Event> events = rows(c, "SELECT * FROM replication_events ORDER BY seq").stream()
            .map(Store::event).toList();
        List<Event> replayEvents = new ArrayList<>();
        List<String> digests = new ArrayList<>();
        for (Event e : events) {
          if (candidate(e)) {
            digests.add(digest(JSON.toJson(e)));
            replayEvents.add(new Event(e.id(), e.sequence(), e.type(), e.time(), e.schema(),
                new Payload(List.of(), List.of(), null)));
            System.out.println("Candidate sequence=" + e.sequence() + " event=" + e.id());
          } else replayEvents.add(e);
        }
        Maintenance.Replay replay = new Maintenance.Replay(replayEvents, seq);
        if (!actual.equals(replay.fingerprint())) {
          for (String table : List.of("users", "accounts", "transfers")) {
            var expected = table.equals("users") ? replay.userRows() : table.equals("accounts") ? replay.accountRows() : replay.transferRows();
            var physical = rows(c, "SELECT * FROM " + table + " ORDER BY id");
            System.out.println("COMPARE table=" + table + " actualRows=" + physical.size() + " replayRows=" + expected.size());
            for (var row : physical) {
              var match = expected.stream().filter(r -> String.valueOf(r.get("id")).equals(String.valueOf(row.get("id")))).findFirst();
              if (match.isEmpty()) { System.out.println("MISSING_REPLAY table=" + table + " id=" + row.get("id")); continue; }
              var before = match.get();
              for (String key : row.keySet()) {
                Object a = row.get(key), b = before.get(key);
                boolean equal = Objects.equals(a, b);
                if (a instanceof Number && b instanceof Number)
                  equal = new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString())) == 0;
                if (!equal) System.out.println("DIFF table=" + table + " id=" + row.get("id") + " column=" + key);
              }
            }
          }
        }
        if (!actual.equals(replay.fingerprint()))
          throw new IllegalStateException("Replay differs from PRIMARY; manual reconciliation required; no approval produced");
        c.rollback();
        System.out.println("VERIFIED sequence=" + seq + " fingerprint=" + actual);
        System.out.println(KEY + "=" + String.join(",", digests));
      } catch (Exception e) { c.rollback(); throw e; }
    }
  }
}
