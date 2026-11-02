package vn.rmibank.client;

import java.math.BigDecimal;
import java.rmi.registry.LocateRegistry;
import java.util.Properties;
import vn.rmibank.common.Api.*;
import vn.rmibank.common.Net;

/** Console diagnostics use exactly the same RMI API as Swing. */
public final class BankCli {
  public static void main(String[] args) throws Exception {
    if (args.length < 4) {
      System.out.println(
          "BankCli <client.properties> <nodeId> <username> me|history|transfer|lookup|snapshot|resync [arguments]");
      return;
    }
    Properties p = Net.load(args[0]);
    String id = args[1], command = args[3];
    Bank bank =
        (Bank)
            LocateRegistry.getRegistry(
                    p.getProperty("node." + id + ".host"),
                    Integer.parseInt(p.getProperty("node." + id + ".port")),
                    new Net.Sockets(4000))
                .lookup("Bank");
    String password = System.getenv("RMI_BANK_PASSWORD");
    if (password == null && System.console() != null)
      password = new String(System.console().readPassword("Mật khẩu: "));
    if (password == null)
      throw new IllegalArgumentException(
          "Đặt RMI_BANK_PASSWORD hoặc chạy trong terminal tương tác");
    boolean admin = command.equals("snapshot") || command.equals("resync");
    Login login = bank.login(args[2], password, admin ? Role.ADMIN : Role.CUSTOMER);
    try {
      switch (command) {
        case "me" -> System.out.println(bank.me(login.token()));
        case "history" ->
            bank.history(login.token(), "", "", "", 0, 100).rows().forEach(System.out::println);
        case "transfer" -> {
          if (args.length < 7)
            throw new IllegalArgumentException("transfer <recipient> <amount> <requestId> [note]");
          System.out.println(
              bank.transfer(
                  login.token(),
                  new TransferRequest(
                      args[6],
                      Long.parseLong(args[4]),
                      new BigDecimal(args[5]),
                      args.length > 7 ? args[7] : "Demo RMI")));
        }
        case "lookup" -> System.out.println(bank.findRequest(login.token(), args[4]));
        case "snapshot" -> {
          Snapshot s = bank.snapshot(login.token());
          System.out.println(s.local());
          s.nodes().forEach(System.out::println);
          s.accounts().forEach(System.out::println);
          s.transfers().forEach(System.out::println);
        }
        case "resync" -> {
          bank.resync(login.token());
          System.out.println("Đã yêu cầu đồng bộ bù");
        }
        default -> throw new IllegalArgumentException("Lệnh không hỗ trợ");
      }
    } finally {
      bank.logout(login.token());
    }
  }
}
