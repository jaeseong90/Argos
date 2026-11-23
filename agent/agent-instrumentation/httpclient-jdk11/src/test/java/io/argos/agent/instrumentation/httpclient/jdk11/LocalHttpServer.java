/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.jdk11;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * The remote side of the module tests, on 127.0.0.1 only (JDK {@code HttpServer}): {@code /ok}
 * (200), {@code /status/<code>}, {@code /slow} (answers after {@link #SLOW_MILLIS}), {@code
 * /redirect} (302 to {@code /ok}); every request's method, path, body length and propagation
 * headers are recorded. A second socket ({@link #dropUrl}) accepts connections and closes them
 * without a response, and {@link #closedPort()} gives a port nothing listens on (connection
 * refused).
 */
final class LocalHttpServer implements AutoCloseable {
  static final long SLOW_MILLIS = 1500;

  /** One request as the server saw it. */
  static final class Seen {
    final String method;
    final String path;
    final int bodyLength;
    final String traceparent;
    final String tracestate;

    Seen(String method, String path, int bodyLength, String traceparent, String tracestate) {
      this.method = method;
      this.path = path;
      this.bodyLength = bodyLength;
      this.traceparent = traceparent;
      this.tracestate = tracestate;
    }

    @Override
    public String toString() {
      return method
          + " "
          + path
          + " body="
          + bodyLength
          + " traceparent="
          + traceparent
          + " tracestate="
          + tracestate;
    }
  }

  private final HttpServer server;
  private final ExecutorService executor;
  private final ServerSocket drop;
  private final List<Seen> seen = Collections.synchronizedList(new ArrayList<Seen>());

  private LocalHttpServer() throws IOException {
    InetAddress loopback = loopback();
    executor = Executors.newCachedThreadPool(daemons("local-http"));
    server = HttpServer.create(new InetSocketAddress(loopback, 0), 50);
    server.createContext("/", this::handle);
    server.setExecutor(executor);
    server.start();
    drop = new ServerSocket(0, 50, loopback);
    daemons("local-drop").newThread(this::dropConnections).start();
  }

  static LocalHttpServer start() throws IOException {
    return new LocalHttpServer();
  }

  int port() {
    return server.getAddress().getPort();
  }

  String url(String path) {
    return "http://127.0.0.1:" + port() + path;
  }

  /** A server that closes every connection without answering. */
  String dropUrl() {
    return "http://127.0.0.1:" + drop.getLocalPort() + "/drop";
  }

  /** A loopback port that refuses connections. */
  static int closedPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0, 1, loopback())) {
      return socket.getLocalPort();
    }
  }

  /** The requests seen since the last {@link #clear()}, oldest first. */
  List<Seen> seen() {
    synchronized (seen) {
      return new ArrayList<Seen>(seen);
    }
  }

  void clear() {
    seen.clear();
  }

  /**
   * Waits at most {@code timeoutMillis} until {@code count} requests to {@code path} were seen. A
   * test whose client gave up before the server read its request (request timeout) calls this
   * before it ends, so that the request is not recorded after the next test's {@link #clear()}
   * (under load the server may read it hundreds of milliseconds later). A request the client never
   * sent is not waited for longer than the timeout.
   */
  void awaitSeen(String path, int count, long timeoutMillis) throws InterruptedException {
    long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
    while (System.nanoTime() - deadline < 0) {
      int found = 0;
      for (Seen s : seen()) {
        if (s.path.equals(path)) {
          found++;
        }
      }
      if (found >= count) {
        return;
      }
      Thread.sleep(10);
    }
  }

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    try (InputStream in = exchange.getRequestBody()) {
      byte[] buffer = new byte[1024];
      int n;
      while ((n = in.read(buffer)) >= 0) {
        body.write(buffer, 0, n);
      }
    }
    seen.add(
        new Seen(
            exchange.getRequestMethod(),
            exchange.getRequestURI().toString(),
            body.size(),
            exchange.getRequestHeaders().getFirst("traceparent"),
            exchange.getRequestHeaders().getFirst("tracestate")));
    int status = 200;
    if (path.startsWith("/status/")) {
      status = Integer.parseInt(path.substring("/status/".length()));
    } else if (path.equals("/redirect")) {
      exchange.getResponseHeaders().set("Location", url("/ok"));
      status = 302;
    } else if (path.equals("/slow")) {
      try {
        Thread.sleep(SLOW_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    byte[] answer = ("status " + status).getBytes(StandardCharsets.UTF_8);
    try {
      exchange.sendResponseHeaders(status, answer.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(answer);
      }
    } catch (IOException e) {
      // The client gave up (timeout test).
    } finally {
      exchange.close();
    }
  }

  private void dropConnections() {
    while (!drop.isClosed()) {
      try (Socket socket = drop.accept()) {
        socket.setSoTimeout(500);
        InputStream in = socket.getInputStream();
        byte[] buffer = new byte[1024];
        // Read the request head, then close without a byte of response.
        in.read(buffer);
      } catch (IOException e) {
        // closed by close() or by the client
      }
    }
  }

  @Override
  public void close() throws IOException {
    server.stop(0);
    executor.shutdownNow();
    drop.close();
  }

  /** 127.0.0.1 without a name lookup. */
  private static InetAddress loopback() throws IOException {
    return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
  }

  private static ThreadFactory daemons(String name) {
    return runnable -> {
      Thread thread = new Thread(runnable, name);
      thread.setDaemon(true);
      return thread;
    };
  }
}
