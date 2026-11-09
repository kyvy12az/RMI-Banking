package vn.rmibank.server;

import static vn.rmibank.server.Store.*;

import java.sql.*;
import java.util.*;
import vn.rmibank.common.Api.*;

/** Durable physical account deletion; permanent identities keep transfer history referentially valid. */
final class AccountDeletion {
  static final String TYPE = "ACCOUNT_DELETE";

  static boolean proposal(ChangeRequest r) {
    return r.table().equals("accounts") && "null".equals(r.after().trim());
  }

  static void key(Connection c, long id) throws Exception {
    if (count(c, "SELECT COUNT(*) FROM account_keys WHERE id=?", id) == 0)
      exec(c, "INSERT INTO account_keys VALUES(?)", id);
  }

  static void migrate(Connection c, Config cfg) throws Exception {
    List<String> oldKeys = new ArrayList<>();
    Set<String> newColumns = new HashSet<>();
    try (var rs = c.getMetaData().getImportedKeys(c.getCatalog(), null, "transfers")) {
      while (rs.next()) {
        String table = rs.getString("PKTABLE_NAME"), col = rs.getString("FKCOLUMN_NAME");
        if (table.equalsIgnoreCase("accounts")) oldKeys.add(rs.getString("FK_NAME"));
        if (table.equalsIgnoreCase("account_keys")) newColumns.add(col.toLowerCase(Locale.ROOT));
      }
    }
    if (!oldKeys.isEmpty())
      org.slf4j.LoggerFactory.getLogger(AccountDeletion.class).info("[DELETE_MIGRATION] backup={}",
          Backup.write(c, cfg, "before-account-delete-fk-migration"));
    for (String table : List.of("accounts", "confirmed_accounts", "deleted_accounts"))
      exec(c, "INSERT INTO account_keys(id) SELECT id FROM " + table
          + " WHERE id NOT IN (SELECT id FROM account_keys)");
    // Install the history identity constraints before dropping the old live-row constraints.
    for (String col : List.of("sender", "recipient"))
      if (!newColumns.contains(col))
        exec(c, "ALTER TABLE transfers ADD CONSTRAINT fk_history_" + col
            + " FOREIGN KEY (" + col + ") REFERENCES account_keys(id)");
    for (String name : new LinkedHashSet<>(oldKeys)) {
      String quoted = "`" + name.replace("`", "``") + "`";
      exec(c, "ALTER TABLE transfers DROP "
          + (cfg.get("jdbc.url").startsWith("jdbc:mysql:") ? "FOREIGN KEY " : "CONSTRAINT ") + quoted);
    }
  }

  static void remove(Connection c, AccountImage image) throws Exception {
    if (image.id() < 1 || image.balance().signum() != 0 || image.active())
      throw new BankException("Invalid account deletion image");
    key(c, image.id());
    var old = rows(c, "SELECT * FROM deleted_accounts WHERE id=?", image.id());
    if (old.isEmpty())
      exec(c, "INSERT INTO deleted_accounts VALUES(?,?,?,?,FALSE)", image.id(), image.userId(),
          image.balance(), image.version());
    else if (!JSON.toJson(Store.image(old.get(0))).equals(JSON.toJson(image)))
      throw new BankException("Account tombstone mismatch id=" + image.id());
    exec(c, "DELETE FROM accounts WHERE id=?", image.id());
  }

  static ChangeResult accept(ChangeCapture capture, Connection c, ChangeRequest r, String hash)
      throws Exception {
    var found = rows(c, "SELECT * FROM accounts WHERE id=? FOR UPDATE", r.rowId());
    String state = "ACCEPTED", detail = "node=" + r.origin() + " table=accounts id=" + r.rowId();
    AccountImage deleted = null;
    if (found.isEmpty()) {
      var tombstone = rows(c, "SELECT * FROM deleted_accounts WHERE id=?", r.rowId());
      if (tombstone.isEmpty()) throw new BankException("Missing account and deletion record id=" + r.rowId());
      deleted = Store.image(tombstone.get(0));
      state = "CONFLICT";
      detail += " reason=account already deleted";
    } else {
      var current = found.get(0);
      try {
        var before = ChangeCapture.parse(r.before());
        if (num(before, "id") != r.rowId()) throw new BankException("Immutable primary key");
        if (!ChangeCapture.normalized("accounts", before).equals(ChangeCapture.normalized("accounts", current))) {
          state = "CONFLICT";
          detail += " reason=before-image differs from PRIMARY";
        } else {
          var a = Store.image(current);
          if (a.balance().signum() != 0) throw new BankException("Account balance must be zero before DELETE");
          deleted = new AccountImage(a.id(), a.userId(), a.balance(), a.version() + 1, false);
          remove(c, deleted);
        }
      } catch (BankException | RuntimeException ex) {
        deleted = null;
        state = "REJECTED";
        detail += " reason=" + (ex instanceof BankException ? ex.getMessage() : "invalid deletion before-image");
      }
    }
    Event event = capture.db.append(c, deleted == null ? "ROW_UPDATE" : TYPE,
        new Payload(List.of(), List.of(deleted == null ? Store.image(found.get(0)) : deleted), null),
        capture.cfg.peers());
    return capture.record(c, r, hash, state, detail, event);
  }
}
