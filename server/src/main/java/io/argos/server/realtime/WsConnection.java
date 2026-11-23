/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import io.argos.server.auth.Role;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One WebSocket connection of {@code /ws} (14 §5): the authenticated user, its subscriptions, the
 * send queue ({@link SubscriberOutbox}, 1 MiB budget of 14 §5.3) and the close protocol. It does
 * not depend on Spring's WebSocket types: the handler gives it a {@link Transport}.
 *
 * <p>Threads: frames arrive on the container's threads ({@link #onText}); bus threads and the
 * handler offer frames to the outbox, whose wake-up schedules one drain task on the send executor;
 * the drain task is the only caller of {@link Transport#send} and of {@link Transport#close}, so
 * sends never overlap and a close never interrupts a frame. {@link #requestClose} only records the
 * request, drops what waits and schedules the drain: it never blocks (the session revocation event
 * calls it on the revoking thread, SEC-44).
 */
final class WsConnection {

  /** Largest client message in UTF-8 bytes (14 §5.1, 12 §3.7: 4 KiB). */
  static final int MAX_MESSAGE_BYTES = 4096;

  /** Subscriptions per connection (14 §5.1). */
  static final int MAX_SUBSCRIPTIONS = 20;

  /** Close codes of 14 §5.3 and of the decisions recorded in the WO-22 report. */
  static final int CLOSE_UNAUTHENTICATED = 4401;

  /** No client message for 60 seconds (14 §5.3). */
  static final int CLOSE_TIMEOUT = 4408;

  /** A client message over 4 KiB (12 §3.7, 16 §7.7). */
  static final int CLOSE_TOO_BIG = 4413;

  /** The sixth connection of a user (14 §5.3). */
  static final int CLOSE_TOO_MANY = 4429;

  /** Server shutdown (14 §5.3, RFC 6455 service restart). */
  static final int CLOSE_RESTART = 1012;

  /** A binary message: not part of the protocol (RFC 6455 unsupported data). */
  static final int CLOSE_UNSUPPORTED = 1003;

  /** A send failed: the connection is not usable (RFC 6455 internal error). */
  static final int CLOSE_ERROR = 1011;

  private static final Logger LOG = LoggerFactory.getLogger(WsConnection.class);

  /** The container side of the connection. */
  interface Transport {
    /** Sends one text message, blocking until it is written (or fails). */
    void send(String text) throws IOException;

    /** Closes with a code and an ASCII reason (best effort; the connection may be gone). */
    void close(int code, String reason);

    /** Whether the connection is still open. */
    boolean isOpen();
  }

  /** What the connection asks of the subscription logic for a complete message. */
  interface FrameHandler {
    void onFrame(WsConnection connection, String message);
  }

  /** A pending close. */
  record CloseRequest(int code, String reason, boolean reauth) {}

  /** One subscription of this connection. */
  record Subscription(String id, Topic topic, RealtimeBus.BusSubscription handle) {}

  private final String id;
  private final UUID userId;
  private final UUID sid;
  private final Transport transport;
  private final Executor sendExecutor;
  private final SubscriberOutbox outbox;
  private final SubscribeBucket bucket;
  private final Map<String, Subscription> subscriptions = new LinkedHashMap<>();
  private final AtomicBoolean draining = new AtomicBoolean();
  private final AtomicReference<CloseRequest> closeRequest = new AtomicReference<>();
  private final AtomicBoolean closed = new AtomicBoolean();
  private volatile Role role;
  private volatile long lastClientMs;
  private final StringBuilder partial = new StringBuilder();
  private long partialBytes;

  /**
   * @param id the container's connection id
   * @param outboxFactory makes the outbox with this connection's wake-up
   * @param nowMs the server clock at the handshake (start of the 60 s timeout)
   */
  WsConnection(
      String id,
      UUID userId,
      UUID sid,
      Role role,
      Transport transport,
      Executor sendExecutor,
      java.util.function.Function<Runnable, SubscriberOutbox> outboxFactory,
      SubscribeBucket bucket,
      long nowMs) {
    this.id = Objects.requireNonNull(id, "id");
    this.userId = Objects.requireNonNull(userId, "userId");
    this.sid = Objects.requireNonNull(sid, "sid");
    this.role = Objects.requireNonNull(role, "role");
    this.transport = Objects.requireNonNull(transport, "transport");
    this.sendExecutor = Objects.requireNonNull(sendExecutor, "sendExecutor");
    this.bucket = Objects.requireNonNull(bucket, "bucket");
    this.outbox = outboxFactory.apply(this::wakeUp);
    this.lastClientMs = nowMs;
  }

  String id() {
    return id;
  }

  UUID userId() {
    return userId;
  }

  /** The session ({@code user_sessions.id}) the handshake authenticated. */
  UUID sid() {
    return sid;
  }

  Role role() {
    return role;
  }

  void role(Role newRole) {
    this.role = Objects.requireNonNull(newRole, "role");
  }

  SubscriberOutbox outbox() {
    return outbox;
  }

  SubscribeBucket bucket() {
    return bucket;
  }

  /** Server-clock time of the last client message. */
  long lastClientMs() {
    return lastClientMs;
  }

  /** Whether a close was requested or done. */
  boolean closing() {
    return closeRequest.get() != null || closed.get();
  }

  /** The requested close, null when none. */
  CloseRequest closeRequestOrNull() {
    return closeRequest.get();
  }

  // --- inbound (container threads) -------------------------------------------------------------

  /**
   * One part of a text message (the handler accepts partial messages so that the 4 KiB limit is
   * enforced here, whatever the container's buffer size). A message over {@value
   * #MAX_MESSAGE_BYTES} bytes closes the connection with {@value #CLOSE_TOO_BIG}.
   *
   * @param nowMs the server clock (the 60 s timeout restarts with every client message)
   */
  void onText(String part, boolean last, long nowMs, FrameHandler handler) {
    if (closing()) {
      return;
    }
    lastClientMs = nowMs;
    String message;
    synchronized (partial) {
      partialBytes += SubscriberOutbox.utf8Length(part);
      if (partialBytes > MAX_MESSAGE_BYTES) {
        partial.setLength(0);
        partialBytes = 0;
        message = null;
      } else if (!last) {
        partial.append(part);
        return;
      } else if (partial.isEmpty()) {
        partialBytes = 0;
        message = part;
      } else {
        message = partial.append(part).toString();
        partial.setLength(0);
        partialBytes = 0;
      }
    }
    if (message == null) {
      requestClose(CLOSE_TOO_BIG, "Message too big", false);
      return;
    }
    handler.onFrame(this, message);
  }

  // --- subscriptions (handler thread, guarded by this) ------------------------------------------

  synchronized Subscription subscription(String subscriptionId) {
    return subscriptions.get(subscriptionId);
  }

  synchronized int subscriptionCount() {
    return subscriptions.size();
  }

  /**
   * Adds a subscription unless the connection is closing; replaces (and returns) one of the same
   * id.
   *
   * @return the replaced subscription, null when none; the new one is cancelled at once when the
   *     connection is closing
   */
  Subscription put(Subscription s) {
    Subscription old;
    boolean refused;
    synchronized (this) {
      refused = closing();
      old = refused ? null : subscriptions.put(s.id(), s);
    }
    if (refused) {
      s.handle().cancel();
      return null;
    }
    if (old != null) {
      old.handle().cancel();
    }
    return old;
  }

  /** Removes and cancels a subscription; false when there was none. */
  boolean remove(String subscriptionId) {
    Subscription s;
    synchronized (this) {
      s = subscriptions.remove(subscriptionId);
    }
    if (s == null) {
      return false;
    }
    s.handle().cancel();
    return true;
  }

  synchronized List<Subscription> subscriptions() {
    return new ArrayList<>(subscriptions.values());
  }

  /** Cancels every subscription (close, revocation). */
  void cancelAll() {
    List<Subscription> all;
    synchronized (this) {
      all = new ArrayList<>(subscriptions.values());
      subscriptions.clear();
    }
    for (Subscription s : all) {
      s.handle().cancel();
    }
  }

  // --- outbound ----------------------------------------------------------------------------------

  /** Queues a control frame ({@code ack}, {@code error}, {@code pong}). */
  void sendControl(String frame) {
    outbox.offerControl(frame);
  }

  /**
   * Asks for the connection to close: the first request wins. The subscriptions stop at once and
   * the queued frames are dropped (nothing is pushed after a revocation); the drain task sends
   * {@code reauth} first when asked, then closes. Never blocks.
   */
  void requestClose(int code, String reason, boolean reauth) {
    if (!closeRequest.compareAndSet(null, new CloseRequest(code, reason, reauth))) {
      return;
    }
    cancelAll();
    outbox.close();
    schedule();
  }

  /** The outbox's wake-up: one drain task at a time. */
  private void wakeUp() {
    if (!closing()) {
      schedule();
    }
  }

  private void schedule() {
    if (draining.compareAndSet(false, true)) {
      try {
        sendExecutor.execute(this::drain);
      } catch (RejectedExecutionException shutdown) {
        draining.set(false);
      }
    }
  }

  /** Sends what waits, or closes; the only sender. */
  void drain() {
    try {
      while (true) {
        CloseRequest c = closeRequest.get();
        if (c != null) {
          finishClose(c);
          return;
        }
        String frame = outbox.poll();
        if (frame == null) {
          draining.set(false);
          if ((outbox.size() == 0 && closeRequest.get() == null)
              || !draining.compareAndSet(false, true)) {
            return;
          }
          continue;
        }
        transport.send(frame);
      }
    } catch (IOException | RuntimeException e) {
      LOG.debug("WebSocket send failed: {}", e.getClass().getSimpleName());
      closeRequest.compareAndSet(null, new CloseRequest(CLOSE_ERROR, "Send failed", false));
      cancelAll();
      outbox.close();
      if (closed.compareAndSet(false, true)) {
        transport.close(CLOSE_ERROR, "Send failed");
      }
    }
  }

  private void finishClose(CloseRequest c) {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    if (c.reauth() && transport.isOpen()) {
      try {
        transport.send(ServerFrames.reauth());
      } catch (IOException | RuntimeException e) {
        LOG.debug("WebSocket reauth frame not sent: {}", e.getClass().getSimpleName());
      }
    }
    transport.close(c.code(), c.reason());
  }

  /**
   * The safety net of a requested close: closes directly when the drain task did not within its
   * time (a send that blocks on a stuck client). Runs on the send executor.
   */
  void forceCloseIfOpen() {
    CloseRequest c = closeRequest.get();
    if (c != null && transport.isOpen() && closed.compareAndSet(false, true)) {
      transport.close(c.code(), c.reason());
    }
  }

  /** The container reported the connection closed: stop everything. */
  void onClosed() {
    closeRequest.compareAndSet(null, new CloseRequest(1000, "Closed", false));
    closed.set(true);
    cancelAll();
    outbox.close();
  }
}
