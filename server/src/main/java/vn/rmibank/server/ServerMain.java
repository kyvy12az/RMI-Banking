package vn.rmibank.server;

import java.rmi.registry.*;
import java.rmi.server.UnicastRemoteObject;
import java.util.concurrent.CountDownLatch;
import vn.rmibank.common.*;

public final class ServerMain {
  public static final class Runtime implements AutoCloseable {
    private Store db;
    private BankService service;
    private Registry registry;

    public Runtime(Config c) throws Exception {
      c.secret();
      System.setProperty("java.rmi.server.hostname", c.get("java.rmi.server.hostname"));
      try {
        db = new Store(c);
        service = new BankService(db, c);
        var stub =
            UnicastRemoteObject.exportObject(
                service,
                c.number("rmi.service.port"),
                new Net.Sockets(c.number("timeout.ms")),
                null);
        registry = LocateRegistry.createRegistry(c.number("rmi.registry.port"));
        registry.rebind("Bank", stub);
        registry.rebind("Replication", stub);
        service.start();
      } catch (Exception e) {
        close();
        throw e;
      }
    }

    public void close() {
      if (service != null) {
        service.close();
        try {
          UnicastRemoteObject.unexportObject(service, true);
        } catch (Exception ignored) {
        }
      }
      if (registry != null)
        try {
          UnicastRemoteObject.unexportObject(registry, true);
        } catch (Exception ignored) {
        }
      if (db != null) db.close();
    }
  }

  public static void main(String[] args) throws Exception {
    String file = args.length == 0 ? "config/server1.local.properties" : args[0];
    Config c = Config.load(file);
    System.setProperty("bank.node", c.id());
    if (java.util.Arrays.asList(args).contains("--gui")) {
      javax.swing.SwingUtilities.invokeLater(
          () -> {
            vn.rmibank.common.ui.BankTheme.setup();
            new Launcher(c);
          });
      return;
    }
    Runtime runtime = new Runtime(c);
    java.lang.Runtime.getRuntime().addShutdownHook(new Thread(runtime::close));
    System.out.println("RMI Banking " + c.id() + " " + c.get("server.role") + " đang hoạt động");
    new CountDownLatch(1).await();
  }
}
