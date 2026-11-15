/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A TCP listener on {@code 127.0.0.1} for the transport tests that look at the agent's connections
 * from outside: it counts and timestamps every accepted connection, and then
 *
 * <ul>
 *   <li>{@link #holding()}: keeps it open without answering (a proxy the agent must not use);
 *   <li>{@link #closing()}: closes it at once (a collector address where every connect fails right
 *       after the TCP handshake);
 *   <li>{@link #relay(int)}: forwards it to {@code 127.0.0.1:<port>} (the FakeCollector), so that
 *       {@link #open()} tells whether the agent still holds a connection to the collector.
 * </ul>
 *
 * Daemon threads; {@link #close()} ends everything.
 */
final class TcpProbe implements AutoCloseable {
  private enum Mode {
    HOLD,
    CLOSE,
    RELAY
  }

  private final Mode mode;
  private final int target;
  private final ServerSocket server;
  private final List<Instant> accepted = new CopyOnWriteArrayList<>();
  private final List<Socket> sockets = new CopyOnWriteArrayList<>();
  private final AtomicInteger open = new AtomicInteger();
  private volatile boolean closed;

  private TcpProbe(Mode mode, int target) throws IOException {
    this.mode = mode;
    this.target = target;
    this.server = new ServerSocket();
    server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 50);
    Thread acceptor = new Thread(this::acceptLoop, "tcp-probe-" + server.getLocalPort());
    acceptor.setDaemon(true);
    acceptor.start();
  }

  static TcpProbe holding() throws IOException {
    return new TcpProbe(Mode.HOLD, 0);
  }

  static TcpProbe closing() throws IOException {
    return new TcpProbe(Mode.CLOSE, 0);
  }

  static TcpProbe relay(int targetPort) throws IOException {
    return new TcpProbe(Mode.RELAY, targetPort);
  }

  int port() {
    return server.getLocalPort();
  }

  /** {@code 127.0.0.1:<port>}. */
  String address() {
    return "127.0.0.1:" + port();
  }

  /** Connections accepted so far. */
  int accepted() {
    return accepted.size();
  }

  /** When each connection was accepted. */
  List<Instant> acceptTimes() {
    return new ArrayList<>(accepted);
  }

  /** Relayed connections open now (both directions still flowing). */
  int open() {
    return open.get();
  }

  private void acceptLoop() {
    while (!closed) {
      Socket socket;
      try {
        socket = server.accept();
      } catch (IOException e) {
        return;
      }
      accepted.add(Instant.now());
      switch (mode) {
        case CLOSE -> closeQuietly(socket);
        case HOLD -> sockets.add(socket);
        case RELAY -> relay(socket);
      }
    }
  }

  private void relay(Socket client) {
    Socket upstream;
    try {
      upstream = new Socket(InetAddress.getLoopbackAddress(), target);
    } catch (IOException e) {
      closeQuietly(client);
      return;
    }
    sockets.add(client);
    sockets.add(upstream);
    open.incrementAndGet();
    AtomicInteger ended = new AtomicInteger();
    Runnable finish =
        () -> {
          if (ended.incrementAndGet() == 1) {
            closeQuietly(client);
            closeQuietly(upstream);
            open.decrementAndGet();
          }
        };
    pump(client, upstream, finish);
    pump(upstream, client, finish);
  }

  private static void pump(Socket from, Socket to, Runnable finish) {
    Thread thread =
        new Thread(
            () -> {
              byte[] buffer = new byte[16 * 1024];
              try (InputStream in = from.getInputStream()) {
                OutputStream out = to.getOutputStream();
                int read;
                while ((read = in.read(buffer)) >= 0) {
                  out.write(buffer, 0, read);
                  out.flush();
                }
              } catch (IOException ignored) {
                // either side closed
              } finally {
                finish.run();
              }
            },
            "tcp-probe-pump");
    thread.setDaemon(true);
    thread.start();
  }

  private static void closeQuietly(Socket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
      // already closed
    }
  }

  @Override
  public void close() {
    closed = true;
    try {
      server.close();
    } catch (IOException ignored) {
      // already closed
    }
    for (Socket socket : sockets) {
      closeQuietly(socket);
    }
  }
}
