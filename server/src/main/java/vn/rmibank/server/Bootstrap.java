package vn.rmibank.server;

import static vn.rmibank.server.Store.*;

import java.math.BigDecimal;
import java.util.*;
import org.mindrot.jbcrypt.BCrypt;
import vn.rmibank.common.Api.*;

public final class Bootstrap {
  public static void main(String[] args) throws Exception {
    Config cfg = Config.load(args[0]);
    System.setProperty("bank.node", cfg.id());
    if (!cfg.primary())
      throw new IllegalArgumentException("Chỉ bootstrap PRIMARY, trước khi chạy server");
    String admin = System.getenv("DEMO_ADMIN_PASSWORD"),
        customer = System.getenv("DEMO_CUSTOMER_PASSWORD");
    if (admin == null
        || customer == null
        || admin.length() < 8
        || customer.length() < 8
        || admin.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72
        || customer.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      throw new IllegalArgumentException(
          "Đặt DEMO_ADMIN_PASSWORD và DEMO_CUSTOMER_PASSWORD 8–72 byte");
    try (Store db = new Store(cfg)) {
      db.tx(
          c -> {
            if (count(c, "SELECT COUNT(*) FROM users") > 0)
              throw new IllegalStateException("CSDL đã có dữ liệu, không bootstrap lại");
            List<UserImage> users = new ArrayList<>();
            List<AccountImage> accounts = new ArrayList<>();
            String[] names = {
              "Quản trị viên", "Nguyễn Kỳ Vỹ", "Võ Lê Mỹ Linh", "Trần Minh Anh", "Lê Hoàng Nam"
            };
            String[] logins = {"admin", "kyvy", "mylinh", "minhanh", "hoangnam"};
            for (int i = 0; i < 5; i++) {
              UserImage u =
                  new UserImage(
                      i + 1,
                      logins[i],
                      names[i],
                      "090123456" + i,
                      BCrypt.hashpw(i == 0 ? admin : customer, BCrypt.gensalt(12)),
                      i == 0 ? "ADMIN" : "CUSTOMER",
                      true);
              users.add(u);
              exec(
                  c,
                  "INSERT INTO users VALUES(?,?,?,?,?,?,?)",
                  u.id(),
                  u.username(),
                  u.name(),
                  u.phone(),
                  u.passwordHash(),
                  u.role(),
                  true);
              if (i > 0) {
                AccountImage a =
                    new AccountImage(100000 + i, i + 1, new BigDecimal("1000000"), 1, true);
                accounts.add(a);
                exec(
                    c,
                    "INSERT INTO accounts VALUES(?,?,?,?,?)",
                    a.id(),
                    a.userId(),
                    a.balance(),
                    a.version(),
                    true);
              }
            }
            db.append(c, "BOOTSTRAP", new Payload(users, accounts, null), cfg.peers());
            return null;
          });
    }
    System.out.println("Đã bootstrap PRIMARY; replica nhận cùng dữ liệu qua event sequence 1.");
  }
}
