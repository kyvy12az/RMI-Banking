package vn.rmibank.common;

import java.io.*;
import java.net.*;
import java.rmi.server.RMIClientSocketFactory;
import java.util.Properties;

public final class Net {
  private Net() {}

  public static Properties load(String path) throws IOException {
    Properties p = new Properties();
    try (Reader r =
        new InputStreamReader(new FileInputStream(path), java.nio.charset.StandardCharsets.UTF_8)) {
      p.load(r);
    }
    for (String k : p.stringPropertyNames()) {
      String e = System.getenv(k.toUpperCase(java.util.Locale.ROOT).replace('.', '_'));
      if (e != null) p.setProperty(k, e);
    }
    return p;
  }

  public static class Sockets implements RMIClientSocketFactory, Serializable {
    private final int timeout;

    public Sockets(int timeout) {
      this.timeout = timeout;
    }

    public Socket createSocket(String host, int port) throws IOException {
      Socket s = new Socket();
      s.connect(new InetSocketAddress(host, port), timeout);
      s.setSoTimeout(timeout);
      return s;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Sockets s && timeout == s.timeout;
    }

    @Override
    public int hashCode() {
      return timeout;
    }
  }
}
