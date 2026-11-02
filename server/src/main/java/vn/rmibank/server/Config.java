package vn.rmibank.server;

import java.util.*;
import vn.rmibank.common.Net;

public record Config(Properties p) {
  public static Config load(String path) throws Exception {
    return new Config(Net.load(path));
  }

  public String get(String key) {
    String v = p.getProperty(key);
    if (v == null || v.isBlank()) throw new IllegalArgumentException("Thiếu cấu hình: " + key);
    return v;
  }

  public int number(String key) {
    return Integer.parseInt(get(key));
  }

  public String id() {
    return get("server.id");
  }

  public boolean primary() {
    return get("server.role").equals("PRIMARY");
  }

  public List<String> peers() {
    return Arrays.stream(get("peers").split(",")).map(String::trim).toList();
  }

  public String host(String node) {
    return get("node." + node + ".host");
  }

  public int port(String node) {
    return number("node." + node + ".port");
  }

  public String secret() {
    String s = get("replication.secret");
    if (s.length() < 24 || s.contains("CHANGE_ME"))
      throw new IllegalArgumentException(
          "Đặt replication.secret chung, ngẫu nhiên ít nhất 24 ký tự");
    return s;
  }
}
