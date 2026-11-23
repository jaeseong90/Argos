/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.ws;

import io.argos.server.testing.auth.ApiTestClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A WebSocket client of {@code /ws} for the server tests (WO-22): the JDK's {@code
 * java.net.http.WebSocket} (no extra dependency), sending the {@code argos_at} cookie of a
 * logged-in {@link ApiTestClient} and an {@code Origin} header like a browser. Every received text
 * message is kept with its arrival time; the close code and reason are recorded. A failed handshake
 * is a {@link Handshake} with the HTTP status instead of a client.
 */
public final class WsTestClient implements AutoCloseable {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** A received frame: its text, the parsed JSON and the arrival time ({@link System#nanoTime}). */
  public record Frame(String text, JsonNode json, long receivedNanos) {
    /** The {@code op}. */
    public String op() {
      return json.path("op").asString();
    }
  }

  /**
   * The outcome of a connection attempt.
   *
   * @param client the open client, null when the handshake failed
   * @param status the HTTP status of a refused handshake, 101 for an accepted one
   * @param body the body of a refused handshake (the ErrorBody), empty otherwise
   */
  public record Handshake(WsTestClient client, int status, String body) {
    /** Whether the server upgraded the connection. */
    public boolean accepted() {
      return client != null;
    }
  }

  private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
  private final List<Frame> all = new java.util.concurrent.CopyOnWriteArrayList<>();
  private final CountDownLatch closed = new CountDownLatch(1);
  private final CompletableFuture<WebSocket> socket = new CompletableFuture<>();
  private volatile int closeCode = -1;
  private volatile String closeReason;
  private volatile long closedNanos;
  private volatile Throwable error;
  private volatile boolean paused;

  private WsTestClient() {}

  /** The {@code ws://} URL of {@code /ws} for an {@code http://} base URL. */
  public static URI wsUri(String baseUrl) {
    return URI.create(baseUrl.replaceFirst("^http", "ws") + "/ws");
  }

  /** Connects with the cookies of {@code client} and its server's origin. */
  public static Handshake connect(ApiTestClient client) {
    return connect(
        client.baseUrl(),
        client.cookie(ApiTestClient.ACCESS_COOKIE).orElse(null),
        client.serverOrigin());
  }

  /**
   * Connects.
   *
   * @param accessToken the {@code argos_at} value to send, null for no cookie
   * @param origin the {@code Origin} header, null for none
   */
  public static Handshake connect(String baseUrl, String accessToken, String origin) {
    return connect(baseUrl, accessToken, origin, false);
  }

  /**
   * Connects; with {@code paused} the client reads nothing until {@link #resume()} (the JDK client
   * then stops reading the socket, so the server's sends back up: a slow subscriber).
   */
  public static Handshake connect(
      String baseUrl, String accessToken, String origin, boolean paused) {
    WsTestClient c = new WsTestClient();
    c.paused = paused;
    HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    WebSocket.Builder builder = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
    if (accessToken != null) {
      builder.header("Cookie", ApiTestClient.ACCESS_COOKIE + "=" + accessToken);
    }
    if (origin != null) {
      builder.header("Origin", origin);
    }
    try {
      WebSocket ws = builder.buildAsync(wsUri(baseUrl), c.new Listener()).get(20, TimeUnit.SECONDS);
      c.socket.complete(ws);
      return new Handshake(c, 101, "");
    } catch (ExecutionException e) {
      Throwable cause =
          e.getCause() instanceof CompletionException ce ? ce.getCause() : e.getCause();
      if (cause instanceof WebSocketHandshakeException h) {
        Object body = h.getResponse().body();
        return new Handshake(
            null, h.getResponse().statusCode(), body == null ? "" : body.toString());
      }
      throw new IllegalStateException("WebSocket connect failed", cause);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (TimeoutException e) {
      throw new IllegalStateException("WebSocket connect timed out", e);
    }
  }

  /** Connects and requires the upgrade. */
  public static WsTestClient open(ApiTestClient client) {
    Handshake h = connect(client);
    if (!h.accepted()) {
      throw new AssertionError("handshake refused: " + h.status() + " " + h.body());
    }
    return h.client();
  }

  private WebSocket ws() {
    return socket.join();
  }

  /** Sends a text message (one frame). */
  public void send(String text) {
    try {
      ws().sendText(text, true).get(10, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException("send failed", e);
    }
  }

  /** Sends {@code subscribe}. */
  public void subscribe(String id, String topic, String paramsJson) {
    send(
        "{\"op\":\"subscribe\",\"id\":\""
            + id
            + "\",\"topic\":\""
            + topic
            + "\",\"params\":"
            + paramsJson
            + "}");
  }

  /** Sends {@code unsubscribe}. */
  public void unsubscribe(String id) {
    send("{\"op\":\"unsubscribe\",\"id\":\"" + id + "\"}");
  }

  /** Sends {@code ping}. */
  public void ping(long t) {
    send("{\"op\":\"ping\",\"t\":" + t + "}");
  }

  /** The next frame that matches, waiting up to {@code timeout}; earlier ones are skipped. */
  public Optional<Frame> await(Predicate<Frame> match, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      long left = deadline - System.nanoTime();
      if (left <= 0) {
        return Optional.empty();
      }
      Frame f;
      try {
        f = inbox.poll(left, TimeUnit.NANOSECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return Optional.empty();
      }
      if (f == null) {
        return Optional.empty();
      }
      if (match.test(f)) {
        return Optional.of(f);
      }
    }
  }

  /** The next frame with {@code op} (and {@code id} when non-null), or an assertion error. */
  public Frame expect(String op, String id, Duration timeout) {
    return await(
            f -> f.op().equals(op) && (id == null || id.equals(f.json().path("id").asString())),
            timeout)
        .orElseThrow(
            () ->
                new AssertionError(
                    "no " + op + (id == null ? "" : " " + id) + " within " + timeout + ": " + all));
  }

  /** Starts reading after a paused connect. */
  public void resume() {
    if (paused) {
      paused = false;
      ws().request(1);
    }
  }

  /** Every frame received so far. */
  public List<Frame> frames() {
    return new ArrayList<>(all);
  }

  /** Waits for the server's close; true when it came. */
  public boolean awaitClose(Duration timeout) {
    try {
      return closed.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** The close code (-1 while open). */
  public int closeCode() {
    return closeCode;
  }

  /** The close reason, null while open. */
  public String closeReason() {
    return closeReason;
  }

  /** When the close arrived ({@link System#nanoTime}), 0 while open. */
  public long closedNanos() {
    return closedNanos;
  }

  /** A transport error, null when none. */
  public Throwable error() {
    return error;
  }

  /** Whether the connection is still open (no close received, no error). */
  public boolean isOpen() {
    return closed.getCount() > 0;
  }

  @Override
  public void close() {
    if (socket.isDone() && isOpen()) {
      try {
        ws().sendClose(WebSocket.NORMAL_CLOSURE, "test done").get(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } catch (ExecutionException | TimeoutException e) {
        ws().abort();
      }
      awaitClose(Duration.ofSeconds(5));
    }
    if (socket.isDone()) {
      ws().abort();
    }
  }

  private final class Listener implements WebSocket.Listener {
    private final StringBuilder text = new StringBuilder();

    @Override
    public void onOpen(WebSocket webSocket) {
      if (!paused) {
        webSocket.request(1);
      }
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      text.append(data);
      if (last) {
        String message = text.toString();
        text.setLength(0);
        JsonNode json;
        try {
          json = JSON.readTree(message);
        } catch (RuntimeException e) {
          json = JSON.createObjectNode();
        }
        Frame f = new Frame(message, json, System.nanoTime());
        all.add(f);
        inbox.add(f);
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      closeCode = statusCode;
      closeReason = reason;
      closedNanos = System.nanoTime();
      closed.countDown();
      return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable e) {
      error = e;
      closedNanos = System.nanoTime();
      closed.countDown();
    }
  }
}
