/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.alert;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;

/**
 * An in-process webhook receiver for the channel tests (WO-25; 18 §19 "알림 채널 Mock"): a JDK {@code
 * HttpServer} or {@code HttpsServer} on 127.0.0.1 and a free port. It records every request
 * (receipt time, method, path, headers, body) and answers with a configurable status, body, delay
 * or redirect. No connection leaves the machine.
 *
 * <pre>{@code
 * try (FakeWebhookReceiver receiver = FakeWebhookReceiver.https(TestTls.serverContext(pki))) {
 *   String url = receiver.url("/hook");          // https://127.0.0.1:<port>/hook
 *   receiver.respond(500);                      // fail every request
 *   receiver.redirect("https://example.invalid"); // answer 302 with Location
 *   receiver.await(1, Duration.ofSeconds(10));
 * }
 * }</pre>
 */
public final class FakeWebhookReceiver implements AutoCloseable {

  /** One received request. */
  public static final class Received {
    private final Instant at;
    private final String method;
    private final String path;
    private final Map<String, List<String>> headers;
    private final byte[] body;

    Received(
        Instant at, String method, String path, Map<String, List<String>> headers, byte[] body) {
      this.at = at;
      this.method = method;
      this.path = path;
      this.headers = Map.copyOf(headers);
      this.body = body.clone();
    }

    /** Receipt time (clock of the test JVM). */
    public Instant at() {
      return at;
    }

    /** HTTP method. */
    public String method() {
      return method;
    }

    /** Request path with query. */
    public String path() {
      return path;
    }

    /** Request headers, names lower case. */
    public Map<String, List<String>> headers() {
      return headers;
    }

    /** The raw body (a copy). */
    public byte[] body() {
      return body.clone();
    }

    /** The first value of a header (name case-insensitive), or null. */
    public String header(String name) {
      List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
      return values == null || values.isEmpty() ? null : values.get(0);
    }

    /** The body as UTF-8. */
    public String bodyText() {
      return new String(body, StandardCharsets.UTF_8);
    }
  }

  private final HttpServer server;
  private final ExecutorService executor;
  private final boolean https;
  private final List<Received> received = new CopyOnWriteArrayList<>();
  private volatile int status = 200;
  private volatile String location;
  private volatile byte[] responseBody = "ok".getBytes(StandardCharsets.UTF_8);
  private volatile Duration delay = Duration.ZERO;

  private FakeWebhookReceiver(HttpServer server, boolean https) {
    this.server = server;
    this.https = https;
    this.executor = Executors.newFixedThreadPool(4);
    server.setExecutor(executor);
    server.createContext("/", this::handle);
    server.start();
  }

  /** A plain HTTP receiver. */
  public static FakeWebhookReceiver http() {
    try {
      return new FakeWebhookReceiver(
          HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16), false);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** An HTTPS receiver with the server context of a test PKI ({@link TestTls#serverContext}). */
  public static FakeWebhookReceiver https(SSLContext context) {
    try {
      HttpsServer server =
          HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
      server.setHttpsConfigurator(new HttpsConfigurator(context));
      return new FakeWebhookReceiver(server, true);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** {@code http(s)://127.0.0.1:<port><path>}. */
  public String url(String path) {
    return (https ? "https" : "http") + "://127.0.0.1:" + port() + path;
  }

  /** The port. */
  public int port() {
    return server.getAddress().getPort();
  }

  /** Answer every following request with {@code code} (no redirect). */
  public FakeWebhookReceiver respond(int code) {
    this.location = null;
    this.status = code;
    return this;
  }

  /** Answer every following request with 302 and {@code Location: target}. */
  public FakeWebhookReceiver redirect(String target) {
    this.location = target;
    this.status = 302;
    return this;
  }

  /** The response body of the following requests (to prove it is never exposed). */
  public FakeWebhookReceiver body(String text) {
    this.responseBody = text.getBytes(StandardCharsets.UTF_8);
    return this;
  }

  /** Wait this long before answering. */
  public FakeWebhookReceiver delay(Duration value) {
    this.delay = value;
    return this;
  }

  /** The requests so far, in order. */
  public List<Received> requests() {
    return new ArrayList<>(received);
  }

  /**
   * Waits until at least {@code count} requests arrived.
   *
   * @return the requests
   * @throws AssertionError when they did not arrive in time
   */
  public List<Received> await(int count, Duration timeout) {
    long end = System.nanoTime() + timeout.toNanos();
    while (received.size() < count) {
      if (System.nanoTime() > end) {
        throw new AssertionError(
            "expected " + count + " webhook requests, got " + received.size() + " in " + timeout);
      }
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted", e);
      }
    }
    return requests();
  }

  /** Forgets the recorded requests. */
  public void clear() {
    received.clear();
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
    try {
      executor.awaitTermination(2, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void handle(HttpExchange exchange) throws IOException {
    Instant at = Instant.now();
    byte[] body;
    try (InputStream in = exchange.getRequestBody()) {
      body = in.readAllBytes();
    }
    Map<String, List<String>> headers = new LinkedHashMap<>();
    exchange
        .getRequestHeaders()
        .forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), List.copyOf(v)));
    received.add(
        new Received(
            at,
            exchange.getRequestMethod(),
            exchange.getRequestURI().toString(),
            Map.copyOf(headers),
            body));
    Duration wait = delay;
    if (!wait.isZero()) {
      try {
        Thread.sleep(wait.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    String redirectTo = location;
    if (redirectTo != null) {
      exchange.getResponseHeaders().add("Location", redirectTo);
    }
    byte[] answer = responseBody;
    exchange.sendResponseHeaders(status, answer.length == 0 ? -1 : answer.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(answer);
    }
  }
}
