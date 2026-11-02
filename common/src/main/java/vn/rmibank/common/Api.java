package vn.rmibank.common;

import java.io.Serializable;
import java.math.BigDecimal;
import java.rmi.*;
import java.util.List;

public final class Api {
  private Api() {}

  public enum Role {
    CUSTOMER,
    ADMIN
  }

  public record Registration(String name, String username, String phone, String password)
      implements Serializable {
    @Override
    public String toString() {
      return "Registration[redacted]";
    }
  }

  public record Login(String token, Role role, String name, long expiresAt)
      implements Serializable {
    @Override
    public String toString() {
      return "Login[redacted]";
    }
  }

  public record Account(long id, String name, BigDecimal balance, long version, boolean active)
      implements Serializable {}

  public record Recipient(long id, String name) implements Serializable {}

  public record TransferRequest(String requestId, long recipient, BigDecimal amount, String note)
      implements Serializable {}

  public record Transfer(
      String id,
      String requestId,
      long sender,
      long recipient,
      BigDecimal amount,
      String note,
      String time,
      String status)
      implements Serializable {}

  public record History(List<Transfer> rows, int total) implements Serializable {}

  public record Node(
      String id,
      String role,
      String host,
      int port,
      boolean online,
      long sequence,
      long pending,
      String detail)
      implements Serializable {}

  public record Delivery(
      String node, long sequence, boolean acknowledged, int attempts, String error)
      implements Serializable {}

  public record Snapshot(
      Node local,
      List<Node> nodes,
      List<Account> accounts,
      List<Transfer> transfers,
      List<String> logs,
      java.util.Map<String, String> settings)
      implements Serializable {}

  // Internal replication DTOs are never returned by customer endpoints.
  public record UserImage(
      long id,
      String username,
      String name,
      String phone,
      String passwordHash,
      String role,
      boolean active)
      implements Serializable {
    @Override
    public String toString() {
      return "UserImage[id=" + id + "]";
    }
  }

  public record AccountImage(long id, long userId, BigDecimal balance, long version, boolean active)
      implements Serializable {}

  public record Payload(List<UserImage> users, List<AccountImage> accounts, Transfer transfer)
      implements Serializable {}

  public record Event(
      String id, long sequence, String type, String time, int schema, Payload payload)
      implements Serializable {}

  public record Ack(long sequence) implements Serializable {}

  public static class BankException extends Exception {
    private final boolean definitive;

    public BankException(String message) {
      this(message, true);
    }

    public BankException(String message, boolean definitive) {
      super(message);
      this.definitive = definitive;
    }

    public boolean definitive() {
      return definitive;
    }
  }

  public interface Bank extends Remote {
    String ping() throws RemoteException;

    Account register(Registration request) throws RemoteException, BankException;

    Login login(String username, String password, Role expected)
        throws RemoteException, BankException;

    void logout(String token) throws RemoteException, BankException;

    void authorizeAdmin(String token) throws RemoteException, BankException;

    Account me(String token) throws RemoteException, BankException;

    Recipient recipient(String token, long id) throws RemoteException, BankException;

    Transfer transfer(String token, TransferRequest request) throws RemoteException, BankException;

    Transfer findRequest(String token, String requestId) throws RemoteException, BankException;

    History history(String token, String search, String from, String to, int page, int size)
        throws RemoteException, BankException;

    Snapshot snapshot(String token) throws RemoteException, BankException;

    List<Delivery> deliveries(String token, String transferId)
        throws RemoteException, BankException;

    void resync(String token) throws RemoteException, BankException;

    void setActive(String token, long account, boolean active)
        throws RemoteException, BankException;
  }

  public interface Replication extends Remote {
    Node heartbeat(String secret) throws RemoteException, BankException;

    Ack apply(String secret, Event event) throws RemoteException, BankException;
  }
}
