package vn.rmibank.server;

import static vn.rmibank.server.Store.*;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.util.*;

final class Backup {
  static final List<String> TABLES =
      List.of(
          "users",
          "accounts",
          "account_keys",
          "deleted_accounts",
          "transfers",
          "node_state",
          "replication_events",
          "replication_deliveries",
          "applied_events",
          "sync_logs",
          "direct_update_queue",
          "capture_state",
          "confirmed_users",
          "confirmed_accounts",
          "change_requests",
          "external_requests",
          "admin_operations");

  static boolean exists(Connection c, String table) throws Exception {
    try (var r = c.getMetaData().getTables(c.getCatalog(), null, table, null)) {
      while (r.next()) if (r.getString("TABLE_NAME").equalsIgnoreCase(table)) return true;
      return false;
    }
  }

  static String write(Connection c, Config cfg, String reason) throws Exception {
    Map<String, Object> data = new TreeMap<>();
    data.put("node", cfg.id());
    data.put("role", cfg.get("server.role"));
    data.put("reason", reason);
    data.put("time", now());
    Map<String, Object> tables = new TreeMap<>();
    for (String table : TABLES)
      if (exists(c, table)) tables.put(table, rows(c, "SELECT * FROM " + table));
    data.put("tables", tables);
    Path folder =
        Path.of(cfg.p().getProperty("backup.dir", "backups/server" + cfg.id())).toAbsolutePath();
    Files.createDirectories(folder);
    Path path = folder.resolve(reason + "-" + UUID.randomUUID() + ".json");
    byte[] bytes = JSON.toJson(data).getBytes(StandardCharsets.UTF_8);
    if (Files.getFileStore(folder).supportsFileAttributeView("posix"))
      Files.createFile(
          path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    else Files.createFile(path);
    try (FileChannel file = FileChannel.open(path, StandardOpenOption.WRITE)) {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) file.write(buffer);
      file.force(true);
    }
    return path + " sha256=" + digest(new String(bytes, StandardCharsets.UTF_8));
  }
}
