/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.extstub;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Outgoing-call target of the sample apps (18 §4.1). Routes:
 *
 * <ul>
 *   <li>any path not listed below: 200, JSON echo (method, path, query, trace headers)
 *   <li>{@code /delay?ms=N}: 200 after N ms (at most {@value #MAX_DELAY_MS})
 *   <li>{@code /timeout?ms=N}: no answer; the connection is closed after N ms (default {@value
 *       #DEFAULT_HOLD_MS}, at most {@value #MAX_HOLD_MS}), so the client's read timeout fires first
 *   <li>{@code /status/{code}}: that status (200..599) with a JSON body
 *   <li>{@code /redirect?to=/path}: 302 to a path on ext-stub (absolute or protocol-relative
 *       targets are rejected: no open redirect)
 *   <li>{@code /close}: closes the connection without an answer
 *   <li>{@code /_stub/health}, {@code /_stub/requests} (GET: last 1000 requests; DELETE: clear)
 * </ul>
 *
 * <p>Connection refused cannot be served by a listening socket: callers use a port of the ext-stub
 * host where nothing listens (sample apps: SAMPLE_EXT_REFUSED_PORT, default 1). Delays and held
 * connections use timers, not worker threads; at most {@value #MAX_HELD} connections are held.
 */
public final class ExtStub implements AutoCloseable {

  public static final int DEFAULT_PORT = 18080;
  public static final long MAX_DELAY_MS = 60_000;
  public static final long DEFAULT_HOLD_MS = 30_000;
  public static final long MAX_HOLD_MS = 120_000;
  public static final int MAX_HELD = 1000;
  static final int LOG_CAPACITY = 1000;

  /**
   * JDK HTTP server option for TCP_NODELAY. The server writes the headers and the body of an answer
   * separately; with Nagle's algorithm on, a Linux client's delayed ACK holds the body for about 40
   * ms (measured in containers: median external call 42.8 ms, 0.99 ms with the option on).
   */
  static final String NODELAY_PROPERTY = "sun.net.httpserver.nodelay";

  private final HttpServer server;
  private final ThreadPoolExecutor workers;
  private final ScheduledExecutorService timers;
  private final RequestLog log = new RequestLog(LOG_CAPACITY);
  private final AtomicInteger held = new AtomicInteger();

  private ExtStub(HttpServer server, ThreadPoolExecutor workers, ScheduledExecutorService timers) {
    this.server = server;
    this.workers = workers;
    this.timers = timers;
  }

  /** Starts ext-stub on {@code address} (port 0: any free port) with {@code threads} workers. */
  public static ExtStub start(InetSocketAddress address, int threads) throws IOException {
    // The JDK reads the option once, when the first HTTP server of the JVM starts; -D wins.
    if (System.getProperty(NODELAY_PROPERTY) == null) {
      System.setProperty(NODELAY_PROPERTY, "true");
    }
    AtomicInteger workerId = new AtomicInteger();
    ThreadPoolExecutor workers =
        new ThreadPoolExecutor(
            threads,
            threads,
            60,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(10_000),
            r -> daemon(r, "ext-stub-worker-" + workerId.incrementAndGet()),
            new ThreadPoolExecutor.CallerRunsPolicy());
    AtomicInteger timerId = new AtomicInteger();
    ScheduledExecutorService timers =
        Executors.newScheduledThreadPool(
            2, r -> daemon(r, "ext-stub-timer-" + timerId.incrementAndGet()));
    HttpServer server = HttpServer.create(address, 512);
    ExtStub stub = new ExtStub(server, workers, timers);
    server.createContext("/", stub::handle);
    server.setExecutor(workers);
    server.start();
    return stub;
  }

  public int port() {
    return server.getAddress().getPort();
  }

  /** {@code http://<host>:<port>} as seen from this machine (loopback when bound to any). */
  public URI baseUri() {
    InetSocketAddress address = server.getAddress();
    String host =
        address.getAddress().isAnyLocalAddress()
            ? "127.0.0.1"
            : address.getAddress().getHostAddress();
    return URI.create("http://" + host + ":" + address.getPort());
  }

  @Override
  public void close() {
    server.stop(0);
    timers.shutdownNow();
    workers.shutdownNow();
  }

  private void handle(HttpExchange exchange) {
    try {
      URI uri = exchange.getRequestURI();
      String rawPath = uri.getRawPath() == null ? "/" : uri.getRawPath();
      String method = exchange.getRequestMethod();
      Map<String, String> query = parseQuery(uri.getRawQuery());
      drain(exchange.getRequestBody());
      if (!rawPath.startsWith("/_stub/")) {
        log.record(method, rawPath, uri.getRawQuery(), exchange.getRequestHeaders());
      }
      switch (rawPath) {
        case "/_stub/health" -> send(exchange, 200, Json.write(Map.of("status", "UP")));
        case "/_stub/requests" -> requests(exchange, method);
        case "/delay" -> {
          long ms = bounded(query, "ms", 0, 0, MAX_DELAY_MS);
          String body = echo(exchange, uri);
          var unused =
              timers.schedule(() -> sendQuietly(exchange, 200, body), ms, TimeUnit.MILLISECONDS);
        }
        case "/timeout" -> hold(exchange, bounded(query, "ms", DEFAULT_HOLD_MS, 0, MAX_HOLD_MS));
        case "/close" -> exchange.close();
        case "/redirect" -> redirect(exchange, query.getOrDefault("to", "/ok"));
        default -> {
          if (rawPath.startsWith("/status/")) {
            send(exchange, statusOf(rawPath.substring("/status/".length())), echo(exchange, uri));
          } else {
            send(exchange, 200, echo(exchange, uri));
          }
        }
      }
    } catch (IllegalArgumentException e) {
      sendQuietly(exchange, 400, Json.write(Map.of("error", String.valueOf(e.getMessage()))));
    } catch (IOException | RuntimeException e) {
      exchange.close();
    }
  }

  private void requests(HttpExchange exchange, String method) throws IOException {
    if (method.equals("DELETE")) {
      log.clear();
      send(exchange, 204, "");
    } else {
      send(exchange, 200, Json.write(log.snapshot()));
    }
  }

  private void hold(HttpExchange exchange, long ms) throws IOException {
    if (held.incrementAndGet() > MAX_HELD) {
      held.decrementAndGet();
      send(exchange, 503, Json.write(Map.of("error", "too many held connections")));
      return;
    }
    var unused =
        timers.schedule(
            () -> {
              held.decrementAndGet();
              exchange.close();
            },
            ms,
            TimeUnit.MILLISECONDS);
  }

  private static void redirect(HttpExchange exchange, String target) throws IOException {
    if (!target.startsWith("/")
        || target.startsWith("//")
        || target.contains("\\")
        || target.chars().anyMatch(c -> c < 0x20)) {
      throw new IllegalArgumentException("to must be a path on ext-stub, e.g. /ok");
    }
    exchange.getResponseHeaders().set("Location", target);
    exchange.sendResponseHeaders(302, -1);
    exchange.close();
  }

  static int statusOf(String text) {
    int status;
    try {
      status = Integer.parseInt(text);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("status must be a number between 200 and 599", e);
    }
    if (status < 200 || status > 599) {
      throw new IllegalArgumentException("status must be between 200 and 599");
    }
    return status;
  }

  static long bounded(Map<String, String> query, String name, long fallback, long min, long max) {
    String value = query.get(name);
    if (value == null || value.isEmpty()) {
      return fallback;
    }
    long parsed;
    try {
      parsed = Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(name + " must be a number", e);
    }
    if (parsed < min || parsed > max) {
      throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
    }
    return parsed;
  }

  static Map<String, String> parseQuery(String rawQuery) {
    Map<String, String> query = new LinkedHashMap<>();
    if (rawQuery == null || rawQuery.isEmpty()) {
      return query;
    }
    for (String pair : rawQuery.split("&", -1)) {
      if (pair.isEmpty()) {
        continue;
      }
      int eq = pair.indexOf('=');
      String name = eq < 0 ? pair : pair.substring(0, eq);
      String value = eq < 0 ? "" : pair.substring(eq + 1);
      query.putIfAbsent(
          URLDecoder.decode(name, StandardCharsets.UTF_8),
          URLDecoder.decode(value, StandardCharsets.UTF_8));
    }
    return query;
  }

  private static String echo(HttpExchange exchange, URI uri) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("stub", "ext-stub");
    body.put("method", exchange.getRequestMethod());
    body.put("path", uri.getPath());
    body.put("query", uri.getRawQuery());
    body.put("traceparent", exchange.getRequestHeaders().getFirst("traceparent"));
    body.put("tracestate", exchange.getRequestHeaders().getFirst("tracestate"));
    return Json.write(body);
  }

  private static void drain(InputStream in) throws IOException {
    byte[] buffer = new byte[8192];
    long total = 0;
    int read;
    while ((read = in.read(buffer)) != -1) {
      total += read;
      if (total > 1_000_000) {
        throw new IllegalArgumentException("request body too large");
      }
    }
  }

  private static void send(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    boolean noBody = status == 204 || status == 304 || exchange.getRequestMethod().equals("HEAD");
    if (!noBody) {
      exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    }
    exchange.sendResponseHeaders(status, noBody ? -1 : bytes.length);
    if (!noBody) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(bytes);
      }
    }
    exchange.close();
  }

  private static void sendQuietly(HttpExchange exchange, int status, String body) {
    try {
      send(exchange, status, body);
    } catch (IOException | RuntimeException e) {
      exchange.close();
    }
  }

  private static Thread daemon(Runnable task, String name) {
    Thread thread = new Thread(task, name);
    thread.setDaemon(true);
    return thread;
  }
}
