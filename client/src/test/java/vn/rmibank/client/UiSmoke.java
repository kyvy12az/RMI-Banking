package vn.rmibank.client;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.Properties;
import javax.imageio.ImageIO;
import javax.swing.*;
import vn.rmibank.common.Api.*;
import vn.rmibank.common.Net;

/** Manual UI verification against running nodes; never injects mock banking data. */
public final class UiSmoke {
  static ClientMain frame;
  static Path out;

  static void capture(String name) throws Exception {
    SwingUtilities.invokeAndWait(
        () -> {
          try {
            frame.validate();
            BufferedImage image =
                new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
            var g = image.createGraphics();
            frame.paint(g);
            g.dispose();
            ImageIO.write(image, "png", out.resolve(name + ".png").toFile());
          } catch (Exception ex) {
            throw new RuntimeException(ex);
          }
        });
  }

  static void waitRefresh() throws Exception {
    for (int i = 0; i < 100; i++) {
      boolean[] busy = {true};
      SwingUtilities.invokeAndWait(() -> busy[0] = frame.refreshing);
      if (!busy[0]) return;
      Thread.sleep(100);
    }
    throw new IllegalStateException("UI refresh timeout");
  }

  public static void main(String[] args) throws Exception {
    try {
      Properties p = Net.load(args[0]);
      out = Path.of(args[1]);
      Files.createDirectories(out);
      SwingUtilities.invokeAndWait(
          () -> {
            Ui.setup();
            frame = new ClientMain(p);
            frame.setVisible(true);
          });
      capture("01-login");
      SwingUtilities.invokeAndWait(() -> frame.showAuth(true));
      capture("02-register");
      ClientMain.Endpoint endpoint = frame.nodes()[0];
      Bank remote = frame.connect(endpoint);
      String password = System.getenv("UI_CUSTOMER_PASSWORD");
      Login customer = remote.login("kyvy", password, Role.CUSTOMER);
      Account account = remote.me(customer.token());
      SwingUtilities.invokeAndWait(
          () -> {
            frame.bank = remote;
            frame.connected = endpoint;
            frame.login = customer;
            frame.account = account;
            frame.openWorkspace();
          });
      waitRefresh();
      capture("03-transfer");
      SwingUtilities.invokeAndWait(() -> frame.leave());
      Login admin = remote.login("admin", System.getenv("UI_ADMIN_PASSWORD"), Role.ADMIN);
      SwingUtilities.invokeAndWait(
          () -> {
            frame.bank = remote;
            frame.connected = endpoint;
            frame.login = admin;
            frame.openWorkspace();
          });
      waitRefresh();
      capture("04-server-console");
      SwingUtilities.invokeAndWait(
          () -> {
            frame.setSize(1040, 750);
            frame.validate();
          });
      capture("05-server-compact");
      System.out.println("UI_SMOKE_OK: " + out.toAbsolutePath());
    } finally {
      if (frame != null)
        SwingUtilities.invokeAndWait(
            () -> {
              frame.leave();
              frame.dispose();
            });
    }
  }
}
