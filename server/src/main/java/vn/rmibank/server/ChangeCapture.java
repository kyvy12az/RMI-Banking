package vn.rmibank.server;

import static vn.rmibank.server.Store.*;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import vn.rmibank.common.Api.*;

/**
 * Durable row proposals; physical SQL edits are provisional until PRIMARY accepts their before
 * image.
 */
final class ChangeCapture {
  static final List<String> TABLES = List.of("users", "accounts");
  static final String USER_COLS = "id,username,full_name,phone,password_hash,role,active";
  static final String ACCOUNT_COLS = "id,user_id,balance,version,active";
  final Store db;
  final Config cfg;

  ChangeCapture(Store db, Config cfg) {
    this.db = db;
    this.cfg = cfg;
  }

  static String jsonRow(String table, String prefix) {
    String[] columns = (table.equals("users") ? USER_COLS : ACCOUNT_COLS).split(",");
    List<String> parts = new ArrayList<>();
    for (String col : columns) {
      parts.add("'" + col + "'");
      parts.add(prefix + "." + col);
    }
    return "JSON_OBJECT(" + String.join(",", parts) + ")";
  }

  void install(Connection c) throws Exception {
    for (String table : List.of("users", "accounts", "transfers")) {
      String old = "rmibank_capture_" + table;
      var triggers =
          rows(
              c,
              "SELECT ACTION_STATEMENT FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME=?",
              old);
      boolean current =
          !triggers.isEmpty()
              && (str(triggers.get(0), "action_statement").contains("change_requests")
                  || (table.equals("transfers")
                      && str(triggers.get(0), "action_statement")
                          .contains("transfer history is immutable")));
      if (!current) {
        exec(c, "DROP TRIGGER IF EXISTS " + old);
        if (!table.equals("transfers")) {
          String different =
              table.equals("accounts")
                  ? "NOT (OLD.user_id <=> NEW.user_id AND OLD.balance <=> NEW.balance AND OLD.active <=> NEW.active AND OLD.version <=> NEW.version)"
                  : "NOT (OLD.username <=> NEW.username AND OLD.full_name <=> NEW.full_name AND OLD.phone <=> NEW.phone AND OLD.password_hash <=> NEW.password_hash AND OLD.role <=> NEW.role AND OLD.active <=> NEW.active)";
          exec(
              c,
              "CREATE TRIGGER "
                  + old
                  + " BEFORE UPDATE ON "
                  + table
                  + " FOR EACH ROW BEGIN "
                  + "IF COALESCE(@rmibank_app_write,0)=0 THEN "
                  + "IF NOT (OLD.id <=> NEW.id) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='RMI Banking: immutable primary key'; END IF; "
                  + "IF "
                  + different
                  + " THEN "
                  + (table.equals("accounts") ? "SET NEW.version=OLD.version+1; " : "")
                  + "INSERT INTO change_requests(request_id,origin_node,table_name,row_id,before_image,after_image,state,attempts,next_attempt,detail,created_at) "
                  + "VALUES(UUID(),(SELECT node_id FROM capture_state WHERE id=1),'"
                  + table
                  + "',NEW.id,"
                  + jsonRow(table, "OLD")
                  + ","
                  + jsonRow(table, "NEW")
                  + ",'PENDING',0,0,'',DATE_FORMAT(UTC_TIMESTAMP(6),'%Y-%m-%dT%H:%i:%s.%f000Z')); END IF; END IF; END");
        } else {
          exec(
              c,
              "CREATE TRIGGER "
                  + old
                  + " BEFORE UPDATE ON transfers FOR EACH ROW BEGIN IF COALESCE(@rmibank_app_write,0)=0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='RMI Banking: transfer history is immutable'; END IF; END");
        }
      }
      if (table.equals("accounts")) {
        String name = "rmibank_capture_accounts_delete";
        var existing = rows(c, "SELECT ACTION_STATEMENT FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME=?", name);
        if (existing.isEmpty() || !str(existing.get(0), "action_statement").contains("account-delete-v1")) {
          if (!existing.isEmpty()) throw new SQLException("Unexpected account DELETE trigger version; run maintenance");
          exec(c, "CREATE TRIGGER " + name + " BEFORE DELETE ON accounts FOR EACH ROW BEGIN "
              + "IF COALESCE(@rmibank_app_write,0)=0 THEN "
              + "IF OLD.balance<>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='RMI Banking: account balance must be zero before DELETE'; END IF; "
              + "INSERT INTO change_requests(request_id,origin_node,table_name,row_id,before_image,after_image,state,attempts,next_attempt,detail,created_at) "
              + "VALUES(UUID(),(SELECT node_id FROM capture_state WHERE id=1),'accounts',OLD.id,"
              + jsonRow("accounts", "OLD")
              + ",'null','PENDING',0,0,'account-delete-v1',DATE_FORMAT(UTC_TIMESTAMP(6),'%Y-%m-%dT%H:%i:%s.%f000Z')); END IF; END");
        }
        // The old guard stays in place if CREATE TRIGGER fails (for example missing DBA privileges).
        exec(c, "DROP TRIGGER IF EXISTS rmibank_guard_accounts_delete");
      }
      for (String operation : table.equals("accounts") ? List.of("INSERT") : List.of("INSERT", "DELETE")) {
        String name = "rmibank_guard_" + table + "_" + operation.toLowerCase(Locale.ROOT);
        if (count(
                c,
                "SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME=?",
                name)
            == 0)
          exec(
              c,
              "CREATE TRIGGER "
                  + name
                  + " BEFORE "
                  + operation
                  + " ON "
                  + table
                  + " FOR EACH ROW BEGIN IF COALESCE(@rmibank_app_write,0)=0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='RMI Banking: use application for INSERT or DELETE'; END IF; END");
      }
    }
  }

  static Map<String, Object> parse(String json) {
    var element = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
    Map<String, Object> result = new TreeMap<>();
    for (var e : element.entrySet()) {
      var v = e.getValue();
      if (v.isJsonNull()) result.put(e.getKey(), null);
      else if (v.getAsJsonPrimitive().isBoolean()) result.put(e.getKey(), v.getAsBoolean());
      else if (v.getAsJsonPrimitive().isNumber()) result.put(e.getKey(), v.getAsBigDecimal());
      else result.put(e.getKey(), v.getAsString());
    }
    return result;
  }

  static String normalized(String table, Map<String, Object> row) {
    return table.equals("users") ? JSON.toJson(user(row)) : JSON.toJson(image(row));
  }

  static ChangeRequest request(Map<String, Object> row) {
    return new ChangeRequest(
        str(row, "request_id"),
        str(row, "origin_node"),
        str(row, "table_name"),
        num(row, "row_id"),
        str(row, "before_image"),
        str(row, "after_image"));
  }

  void restore(Connection c) throws Exception {
    // Locks are held through the business commit. Trigger transactions never acquire node_state.
    for (String table : TABLES) {
      var actual = rows(c, "SELECT * FROM " + table + " ORDER BY id FOR UPDATE");
      var confirmed = rows(c, "SELECT * FROM confirmed_" + table + " ORDER BY id");
      if (table.equals("accounts")) {
        Set<Long> present = new HashSet<>();
        for (var row : actual) present.add(num(row, "id"));
        for (var row : confirmed) {
          long id = num(row, "id");
          if (!present.contains(id) && count(c,
              "SELECT COUNT(*) FROM change_requests WHERE table_name='accounts' AND row_id=? AND state='PENDING' AND after_image='null'", id) > 0) {
            AccountDeletion.key(c, id);
            exec(c, "INSERT INTO accounts VALUES(?,?,?,?,?)", id, num(row, "user_id"),
                row.get("balance"), num(row, "version"), bool(row, "active"));
          }
        }
        actual = rows(c, "SELECT * FROM accounts ORDER BY id FOR UPDATE");
      }
      if (actual.size() != confirmed.size())
        throw new BankException("Untracked INSERT/DELETE in " + table + "; run Maintenance audit");
      for (int i = 0; i < actual.size(); i++) {
        var a = actual.get(i);
        var expected = confirmed.get(i);
        long id = num(a, "id");
        if (id != num(expected, "id"))
          throw new BankException("Untracked primary key change in " + table);
        if (normalized(table, a).equals(normalized(table, expected))) continue;
        boolean captured = false;
        for (var pending :
            rows(
                c,
                "SELECT after_image FROM change_requests WHERE table_name=? AND row_id=? AND state='PENDING' ORDER BY local_order DESC",
                table,
                id))
          if (!str(pending, "after_image").trim().equals("null")
              && normalized(table, a).equals(normalized(table, parse(str(pending, "after_image"))))) {
            captured = true;
            break;
          }
        if (!captured)
          throw new BankException(
              "Untracked UPDATE node="
                  + cfg.id()
                  + " table="
                  + table
                  + " id="
                  + id
                  + "; run Maintenance audit");
      }
    }
    restoreRows(
        c,
        rows(c, "SELECT * FROM confirmed_users ORDER BY id"),
        rows(c, "SELECT * FROM confirmed_accounts ORDER BY id"));
  }

  static void restoreRows(
      Connection c, List<Map<String, Object>> users, List<Map<String, Object>> accounts)
      throws Exception {
    List<Map<String, Object>> changedUsers = new ArrayList<>(), changedAccounts = new ArrayList<>();
    for (var row : users) {
      var actual = rows(c, "SELECT * FROM users WHERE id=?", num(row, "id")).get(0);
      if (!normalized("users", row).equals(normalized("users", actual))) changedUsers.add(row);
    }
    for (var row : accounts) {
      var actual = rows(c, "SELECT * FROM accounts WHERE id=?", num(row, "id")).get(0);
      if (!normalized("accounts", row).equals(normalized("accounts", actual)))
        changedAccounts.add(row);
    }
    for (var row : changedUsers)
      exec(
          c,
          "UPDATE users SET username=? WHERE id=?",
          "_sync_" + UUID.randomUUID().toString().replace("-", ""),
          num(row, "id"));
    for (var row : changedUsers) write(c, "users", row);
    List<Long> temporaryUsers = new ArrayList<>();
    long next = count(c, "SELECT COALESCE(MAX(id),0)+1 FROM users");
    for (var row : changedAccounts) {
      long id = num(row, "id");
      if (count(c, "SELECT user_id FROM accounts WHERE id=?", id) != num(row, "user_id")) {
        long temporary = next++;
        temporaryUsers.add(temporary);
        exec(
            c,
            "INSERT INTO users VALUES(?,?,?,'','', 'CUSTOMER',FALSE)",
            temporary,
            "_sync_" + UUID.randomUUID().toString().replace("-", ""),
            "Temporary transaction row");
        exec(c, "UPDATE accounts SET user_id=? WHERE id=?", temporary, id);
      }
    }
    for (var row : changedAccounts) write(c, "accounts", row);
    for (Long id : temporaryUsers) exec(c, "DELETE FROM users WHERE id=?", id);
  }

  static void saveConfirmed(Connection c) throws Exception {
    for (String table : TABLES) {
      exec(c, "DELETE FROM confirmed_" + table);
      exec(c, "INSERT INTO confirmed_" + table + " SELECT * FROM " + table);
    }
  }

  static void write(Connection c, String table, Map<String, Object> row) throws Exception {
    if (table.equals("accounts")) {
      AccountImage a = image(row);
      exec(
          c,
          "UPDATE accounts SET user_id=?,balance=?,version=?,active=? WHERE id=?",
          a.userId(),
          a.balance(),
          a.version(),
          a.active(),
          a.id());
    } else {
      UserImage u = user(row);
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
    }
  }

  void drain(Connection c) throws Exception {
    for (var row :
        rows(c, "SELECT * FROM change_requests WHERE state='PENDING' ORDER BY local_order")) {
      ChangeResult result = accept(c, request(row));
      acknowledge(c, str(row, "request_id"), result);
    }
  }

  void acknowledge(Connection c, String id, ChangeResult result) throws Exception {
    exec(
        c,
        "UPDATE change_requests SET state=?,detail=?,confirmed_seq=?,attempts=attempts+1 WHERE request_id=?",
        result.status(),
        result.detail(),
        result.sequence(),
        id);
    log(
        c,
        "[CHANGE] node="
            + cfg.id()
            + " request="
            + id
            + " status="
            + result.status()
            + " sequence="
            + result.sequence()
            + " "
            + result.detail());
  }

  ChangeResult accept(Connection c, ChangeRequest r) throws Exception {
    if (r == null
        || r.requestId() == null
        || !r.requestId().matches("[A-Za-z0-9-]{16,64}")
        || r.origin() == null
        || (!r.origin().equals(cfg.id()) && !cfg.peers().contains(r.origin()))
        || !TABLES.contains(r.table())
        || r.rowId() < 1
        || r.before() == null
        || r.after() == null
        || r.before().length() > 10000
        || r.after().length() > 10000) throw new BankException("Invalid change request envelope");
    String hash = digest(JSON.toJson(r));
    var processed = rows(c, "SELECT * FROM external_requests WHERE request_id=?", r.requestId());
    if (!processed.isEmpty()) {
      var old = processed.get(0);
      if (!str(old, "request_hash").equals(hash))
        throw new BankException("requestId reused with different change data");
      return new ChangeResult(
          r.requestId(), str(old, "state"), num(old, "confirmed_seq"), str(old, "detail"));
    }
    if (AccountDeletion.proposal(r)
        || (r.table().equals("accounts") && count(c, "SELECT COUNT(*) FROM deleted_accounts WHERE id=?", r.rowId()) > 0))
      return AccountDeletion.accept(this, c, r, hash);
    var found = rows(c, "SELECT * FROM " + r.table() + " WHERE id=? FOR UPDATE", r.rowId());
    if (found.isEmpty())
      throw new BankException("Missing existing row " + r.table() + ":" + r.rowId());
    var current = found.get(0);
    String state = "ACCEPTED",
        detail = "node=" + r.origin() + " table=" + r.table() + " id=" + r.rowId();
    Map<String, Object> desired = null;
    try {
      var before = parse(r.before());
      desired = parse(r.after());
      if (num(before, "id") != r.rowId() || num(desired, "id") != r.rowId())
        throw new BankException("Immutable primary key");
      if (!normalized(r.table(), before).equals(normalized(r.table(), current))) {
        state = "CONFLICT";
        detail += " reason=before-image differs from PRIMARY";
      } else validate(c, r.table(), desired, current);
    } catch (Exception ex) {
      state = "REJECTED";
      detail += " reason=" + (ex instanceof BankException ? ex.getMessage() : "invalid row image");
    }
    if (state.equals("ACCEPTED")) write(c, r.table(), desired);
    // Rejected/conflicting proposals also publish the confirmed image so origin converges.
    var authoritative = rows(c, "SELECT * FROM " + r.table() + " WHERE id=?", r.rowId()).get(0);
    Payload p =
        r.table().equals("users")
            ? new Payload(List.of(user(authoritative)), List.of(), null)
            : new Payload(List.of(), List.of(image(authoritative)), null);
    Event event = db.append(c, "ROW_UPDATE", p, cfg.peers());
    return record(c, r, hash, state, detail, event);
  }

  ChangeResult record(Connection c, ChangeRequest r, String hash, String state, String detail, Event event)
      throws Exception {
    exec(
        c,
        "INSERT INTO external_requests VALUES(?,?,?,?,?,?,?)",
        r.requestId(),
        r.origin(),
        hash,
        state,
        event.sequence(),
        detail,
        now());
    log(
        c,
        "["
            + state
            + "] request="
            + r.requestId()
            + " "
            + detail
            + " sequence="
            + event.sequence());
    return new ChangeResult(r.requestId(), state, event.sequence(), detail);
  }

  static void validate(
      Connection c, String table, Map<String, Object> desired, Map<String, Object> old)
      throws Exception {
    if (table.equals("accounts")) {
      var a = image(desired);
      if (a.balance().signum() < 0
          || a.balance().stripTrailingZeros().scale() > 0
          || a.balance().compareTo(new BigDecimal("9999999999999999999")) > 0)
        throw new BankException("Invalid VND balance");
      if (count(c, "SELECT COUNT(*) FROM users WHERE id=?", a.userId()) != 1
          || count(c, "SELECT COUNT(*) FROM accounts WHERE user_id=? AND id<>?", a.userId(), a.id())
              > 0) throw new BankException("Invalid or duplicate account owner");
      desired.put("version", num(old, "version") + 1);
    } else {
      var u = user(desired);
      String username = u.username().strip().toLowerCase(Locale.ROOT);
      if (!username.matches("[a-z0-9_]{3,32}")
          || u.name().isBlank()
          || u.name().length() > 120
          || !u.phone().matches("(?:0[0-9]{9}|\\+84[0-9]{9})")
          || !u.passwordHash().matches("\\$2[aby]\\$[0-9]{2}\\$[./A-Za-z0-9]{53}")
          || !List.of("ADMIN", "CUSTOMER").contains(u.role()))
        throw new BankException("Invalid user data (BCrypt hash and valid role required)");
      if (count(c, "SELECT COUNT(*) FROM users WHERE username=? AND id<>?", username, u.id()) > 0)
        throw new BankException("Duplicate username");
      desired.put("username", username);
    }
  }
}
