/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * An HTTP server on the loopback interface that answers the shop routes of the mixes like the
 * sample apps do (error-500: 500, send-error: 503, slow: sleeps {@code ms}, others: 200 after
 * {@code delayMillis}) and counts requests and the highest concurrency it saw.
 */
final class FakeShopServer implements AutoCloseable {

  private final HttpServer server;
  private final ExecutorService workers;
  private final long delayMillis;
  private final long slowCapMillis;
  private final Map<String, LongAdder> requestsByRoute = new ConcurrentHashMap<>();
  private final AtomicInteger active = new AtomicInteger();
  private final AtomicInteger maxActive = new AtomicInteger();

  /**
   * @param delayMillis answer time of the normal routes
   * @param slowCapMillis upper bound of the slow route's sleep (keeps tests short)
   */
  FakeShopServer(long delayMillis, long slowCapMillis) throws IOException {
    this.delayMillis = delayMillis;
    this.slowCapMillis = slowCapMillis;
    this.workers = Executors.newFixedThreadPool(256);
    this.server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1024);
    server.createContext("/", this::handle);
    server.setExecutor(workers);
    server.start();
  }

  URI baseUri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
  }

  long requests(String route) {
    LongAdder n = requestsByRoute.get(route);
    return n == null ? 0 : n.sum();
  }

  long totalRequests() {
    return requestsByRoute.values().stream().mapToLong(LongAdder::sum).sum();
  }

  int maxConcurrency() {
    return maxActive.get();
  }

  private void handle(HttpExchange exchange) throws IOException {
    int now = active.incrementAndGet();
    maxActive.accumulateAndGet(now, Math::max);
    try (InputStream in = exchange.getRequestBody()) {
      in.readAllBytes();
      URI uri = exchange.getRequestURI();
      String path = uri.getPath();
      String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
      String route = route(path);
      requestsByRoute.computeIfAbsent(route, r -> new LongAdder()).increment();
      int status;
      switch (route) {
        case "error-500" -> status = 500;
        case "send-error" -> status = 503;
        case "slow" -> {
          sleep(Math.min(slowCapMillis, millis(query)));
          status = 200;
        }
        default -> {
          sleep(delayMillis);
          status = 200;
        }
      }
      byte[] body = ("{\"route\":\"" + route + "\"}").getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(status, body.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    } finally {
      exchange.close();
      active.decrementAndGet();
    }
  }

  private static String route(String path) {
    if (path.equals("/api/orders")) {
      return "orders.page";
    }
    if (path.startsWith("/api/orders/") && path.endsWith("/items")) {
      return "orders.items";
    }
    if (path.startsWith("/api/orders/")) {
      return "orders.get";
    }
    if (path.startsWith("/api/pay/")) {
      return "pay";
    }
    if (path.equals("/api/slow")) {
      return "slow";
    }
    if (path.equals("/api/error-500")) {
      return "error-500";
    }
    if (path.equals("/api/send-error")) {
      return "send-error";
    }
    return "other";
  }

  private static long millis(String query) {
    for (String pair : query.split("&", -1)) {
      if (pair.startsWith("ms=")) {
        return Long.parseLong(pair.substring(3));
      }
    }
    return 0;
  }

  private static void sleep(long millis) {
    if (millis <= 0) {
      return;
    }
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Override
  public void close() {
    server.stop(0);
    workers.shutdownNow();
  }
}
