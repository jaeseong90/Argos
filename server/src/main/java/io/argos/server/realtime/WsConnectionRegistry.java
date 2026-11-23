/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.session.SessionRevokedEvent;
import io.argos.server.auth.session.SessionStateCache;
import io.argos.server.storage.postgres.auth.SessionStateRow;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The open WebSocket connections ({@code WsConnectionRegistry} of 16 §7.7): by connection, user and
 * session. It enforces the five connections of a user (the sixth closes with {@value
 * WsConnection#CLOSE_TOO_MANY}, 14 §5.1), the 60 s client timeout ({@value
 * WsConnection#CLOSE_TIMEOUT}, 14 §5.3, on the server clock), the end of a session (a {@link
 * SessionRevokedEvent} sends {@code reauth} and closes every connection of the revoked rows with
 * {@value WsConnection#CLOSE_UNAUTHENTICATED} within a second, SEC-44, ADR-0010 C-167 (2)), the
 * changes of the session record made elsewhere (revoked, disabled, past its absolute expiry, a new
 * role: re-read every second through {@link SessionStateCache}, whose entries live 5 s) and the
 * close at shutdown ({@value WsConnection#CLOSE_RESTART}).
 *
 * <p>An expired access token does not end a connection (14 §5.1: the WebSocket lives while the
 * session record is valid); a session record that no longer exists (a rotated row removed by the
 * cleanup job) does not either, since its family can still be alive.
 */
@Component
public class WsConnectionRegistry implements SmartLifecycle {

  /** Connections of one user (14 §5.1). */
  static final int MAX_CONNECTIONS_PER_USER = 5;

  /** Client silence before {@value WsConnection#CLOSE_TIMEOUT} (14 §5.3). */
  static final long IDLE_TIMEOUT_MS = 60_000;

  /** A requested close is forced after this long (SEC-44: closed within one second). */
  static final long FORCE_CLOSE_MS = 900;

  /** Period of the timeout and session checks. */
  static final long SWEEP_MS = 1_000;

  /** How long the shutdown waits for the connections to close. */
  static final long SHUTDOWN_WAIT_MS = 2_000;

  private static final Logger LOG = LoggerFactory.getLogger(WsConnectionRegistry.class);

  private final SessionStateCache states;
  private final SubscriptionManager subscriptions;
  private final RealtimeBus bus;
  private final Clock clock;
  private final Map<String, WsConnection> connections = new ConcurrentHashMap<>();
  private final Map<UUID, Integer> perUser = new HashMap<>();
  private final ExecutorService sendExecutor =
      Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("argos-ws-send-", 0).factory());
  private final ScheduledExecutorService sweeper =
      Executors.newScheduledThreadPool(
          2,
          r -> {
            Thread t = new Thread(r, "argos-ws-sweeper");
            t.setDaemon(true);
            return t;
          });
  private boolean running;
  private boolean stopped;

  @Autowired
  public WsConnectionRegistry(
      SessionStateCache states, SubscriptionManager subscriptions, RealtimeBus bus, Clock clock) {
    this.states = Objects.requireNonNull(states, "states");
    this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
    this.bus = Objects.requireNonNull(bus, "bus");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * A new connection of an authenticated handshake. Over the user's limit the connection is
   * returned already closing ({@value WsConnection#CLOSE_TOO_MANY}) and is not registered.
   */
  WsConnection open(String id, ArgosPrincipal principal, WsConnection.Transport transport) {
    return open(id, principal, transport, bus::outbox);
  }

  WsConnection open(
      String id,
      ArgosPrincipal principal,
      WsConnection.Transport transport,
      Function<Runnable, SubscriberOutbox> outboxes) {
    WsConnection c =
        new WsConnection(
            id,
            principal.userId(),
            principal.sid(),
            principal.role(),
            transport,
            this::executeSend,
            outboxes,
            new SubscribeBucket(System::nanoTime),
            clock.millis());
    boolean registered;
    synchronized (perUser) {
      int n = perUser.getOrDefault(principal.userId(), 0);
      registered = n < MAX_CONNECTIONS_PER_USER;
      if (registered) {
        perUser.put(principal.userId(), n + 1);
        connections.put(id, c);
      }
    }
    if (!registered) {
      LOG.debug(
          "WebSocket connection refused: the user has {} connections", MAX_CONNECTIONS_PER_USER);
      close(c, WsConnection.CLOSE_TOO_MANY, "Too many connections", false);
    }
    return c;
  }

  /** The container closed a connection. */
  void closed(WsConnection c) {
    c.onClosed();
    synchronized (perUser) {
      if (connections.remove(c.id(), c)) {
        perUser.computeIfPresent(c.userId(), (u, n) -> n <= 1 ? null : n - 1);
      }
    }
  }

  /** Open connections. */
  int size() {
    return connections.size();
  }

  /** Open connections of a user. */
  int connections(UUID userId) {
    synchronized (perUser) {
      return perUser.getOrDefault(userId, 0);
    }
  }

  /** Requests a close and forces it after {@value #FORCE_CLOSE_MS} ms if the drain did not. */
  void close(WsConnection c, int code, String reason, boolean reauth) {
    c.requestClose(code, reason, reauth);
    try {
      var unused =
          sweeper.schedule(
              () -> executeSend(c::forceCloseIfOpen), FORCE_CLOSE_MS, TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException shuttingDown) {
      // Stopped: the close request stands.
    }
  }

  /**
   * A session was revoked (logout, password change, role change, deactivation, administrator):
   * {@code reauth} and close {@value WsConnection#CLOSE_UNAUTHENTICATED} for every connection of
   * the revoked rows. Runs on the revoking thread and never blocks.
   */
  @EventListener
  public void onSessionRevoked(SessionRevokedEvent event) {
    for (WsConnection c : connections.values()) {
      if (event.sessionIds().contains(c.sid())) {
        close(c, WsConnection.CLOSE_UNAUTHENTICATED, "Session ended", true);
      }
    }
  }

  /** The 60 s client timeout (server clock). */
  void sweepTimeouts() {
    long now = clock.millis();
    for (WsConnection c : connections.values()) {
      if (!c.closing() && now - c.lastClientMs() >= IDLE_TIMEOUT_MS) {
        close(c, WsConnection.CLOSE_TIMEOUT, "Timeout", false);
      }
    }
  }

  /** The session records of the connections (changes made without an event in this process). */
  void sweepSessions() {
    Instant now = clock.instant();
    for (WsConnection c : connections.values()) {
      if (c.closing()) {
        continue;
      }
      Optional<SessionStateRow> found;
      try {
        found = states.get(c.sid());
      } catch (RuntimeException e) {
        LOG.debug("WebSocket session check failed: {}", e.getClass().getSimpleName());
        continue;
      }
      if (found.isEmpty()) {
        continue;
      }
      SessionStateRow row = found.get();
      if (!row.userId().equals(c.userId())
          || row.revokedAt() != null
          || !row.enabled()
          || !row.absoluteExpiresAt().isAfter(now)) {
        close(c, WsConnection.CLOSE_UNAUTHENTICATED, "Session ended", true);
      } else if (row.role() != c.role()) {
        subscriptions.roleChanged(c, row.role());
      }
    }
  }

  private void executeSend(Runnable task) {
    sendExecutor.execute(task);
  }

  // --- SmartLifecycle ----------------------------------------------------------------------------

  /** Starts the checks (the sender runs from construction: a handshake may come first). */
  @Override
  public synchronized void start() {
    if (running || stopped) {
      return;
    }
    running = true;
    var unusedTimeouts =
        sweeper.scheduleWithFixedDelay(
            () -> guarded(this::sweepTimeouts), SWEEP_MS, SWEEP_MS, TimeUnit.MILLISECONDS);
    var unusedSessions =
        sweeper.scheduleWithFixedDelay(
            () -> guarded(this::sweepSessions), SWEEP_MS, SWEEP_MS, TimeUnit.MILLISECONDS);
  }

  private static void guarded(Runnable r) {
    try {
      r.run();
    } catch (RuntimeException e) {
      LOG.warn("A WebSocket check failed: {}", e.getClass().getName());
    }
  }

  /** Closes every connection with {@value WsConnection#CLOSE_RESTART} (14 §5.3). */
  @Override
  public synchronized void stop() {
    if (stopped) {
      return;
    }
    stopped = true;
    running = false;
    List<WsConnection> open = List.copyOf(connections.values());
    for (WsConnection c : open) {
      c.requestClose(WsConnection.CLOSE_RESTART, "Server restart", false);
    }
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_WAIT_MS);
    while (!connections.isEmpty() && System.nanoTime() < deadline) {
      try {
        Thread.sleep(20);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    sweeper.shutdownNow();
    sendExecutor.shutdown();
  }

  @Override
  public synchronized boolean isRunning() {
    return running;
  }

  /** Before the web server stops (its graceful shutdown would wait for upgraded connections). */
  @Override
  public int getPhase() {
    return SmartLifecycle.DEFAULT_PHASE;
  }
}
