/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import static io.argos.server.realtime.BusFixture.T0;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.Role;
import io.argos.server.auth.session.RevocationReason;
import io.argos.server.auth.session.SessionRevokedEvent;
import io.argos.server.auth.session.SessionStateCache;
import io.argos.server.realtime.BusFixture.Sources;
import io.argos.server.realtime.BusFixture.TestClock;
import io.argos.server.realtime.WsFixture.RecordingTransport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The connection registry: per-user limit, revocation, client timeout, shutdown. */
@Tag("req:SEC-44")
class WsConnectionRegistryTest {

  private final TestClock clock = new TestClock(T0);
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final RealtimeBus bus =
      BusFixture.bus(new Sources().app(3, "prod", 1_000), clock, meters);
  private final SubscriptionManager manager = new SubscriptionManager(bus, clock, meters);
  // The session sweep is not driven here (it reads PostgreSQL; WebSocketSecurityTest covers it).
  private final WsConnectionRegistry registry =
      new WsConnectionRegistry(new SessionStateCache(null), manager, bus, clock);

  @AfterEach
  void stop() {
    registry.stop();
  }

  private static ArgosPrincipal principal(UUID user, UUID sid) {
    return new ArgosPrincipal(user, "user-a", sid, Role.VIEWER, Set.of(), false);
  }

  @Test
  @DisplayName(
      "T-API-007c five connections of a user are accepted, the sixth closes 4429; a closed"
          + " connection frees its place; other users are not affected")
  void perUserLimit() throws Exception {
    UUID user = UUID.randomUUID();
    List<RecordingTransport> transports = new ArrayList<>();
    List<WsConnection> conns = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      RecordingTransport t = new RecordingTransport();
      transports.add(t);
      conns.add(registry.open("c" + i, principal(user, UUID.randomUUID()), t));
    }
    assertThat(registry.connections(user)).isEqualTo(5);
    RecordingTransport sixth = new RecordingTransport();
    registry.open("c5", principal(user, UUID.randomUUID()), sixth);
    assertThat(sixth.closed.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(sixth.closes).containsExactly(WsConnection.CLOSE_TOO_MANY);
    assertThat(registry.connections(user)).isEqualTo(5);
    assertThat(transports).allSatisfy(t -> assertThat(t.closes).isEmpty());

    RecordingTransport other = new RecordingTransport();
    registry.open("o1", principal(UUID.randomUUID(), UUID.randomUUID()), other);
    assertThat(other.closes).isEmpty();

    registry.closed(conns.get(0));
    assertThat(registry.connections(user)).isEqualTo(4);
    RecordingTransport again = new RecordingTransport();
    registry.open("c6", principal(user, UUID.randomUUID()), again);
    Thread.sleep(100);
    assertThat(again.closes).isEmpty();
    assertThat(registry.connections(user)).isEqualTo(5);
  }

  @Test
  @DisplayName(
      "T-SEC-44d a revoked session (any row of its family): reauth then close 4401 within one"
          + " second on every connection of it; the event returns at once; other sessions stay")
  void revocation() throws Exception {
    UUID user = UUID.randomUUID();
    UUID oldRow = UUID.randomUUID();
    UUID current = UUID.randomUUID();
    UUID otherSession = UUID.randomUUID();
    RecordingTransport a = new RecordingTransport();
    RecordingTransport b = new RecordingTransport();
    RecordingTransport other = new RecordingTransport();
    WsConnection ca = registry.open("a", principal(user, oldRow), a);
    registry.open("b", principal(user, current), b);
    registry.open("o", principal(user, otherSession), other);
    manager.onFrame(
        ca, "{\"op\":\"subscribe\",\"id\":\"s1\",\"topic\":\"counter\",\"params\":{\"app\":3}}");
    assertThat(bus.subscriptions(Topic.COUNTER)).isEqualTo(1);

    long start = System.nanoTime();
    registry.onSessionRevoked(
        new SessionRevokedEvent(current, user, RevocationReason.LOGOUT, Set.of(oldRow, current)));
    long eventNanos = System.nanoTime() - start;
    assertThat(TimeUnit.NANOSECONDS.toMillis(eventNanos)).as("listener time").isLessThan(100);
    assertThat(bus.subscriptions(Topic.COUNTER)).as("subscriptions end at once").isZero();
    assertThat(a.closed.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(b.closed.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(TimeUnit.NANOSECONDS.toMillis(Math.max(a.closedAtNanos, b.closedAtNanos) - start))
        .isLessThan(1_000);
    for (RecordingTransport t : List.of(a, b)) {
      assertThat(t.closes).containsExactly(WsConnection.CLOSE_UNAUTHENTICATED);
      assertThat(t.ops().get(t.ops().size() - 1)).isEqualTo("reauth");
    }
    Thread.sleep(100);
    assertThat(other.closes).isEmpty();
  }

  @Test
  @DisplayName("T-SEC-44d a stuck send does not delay the 4401 beyond the forced close (900 ms)")
  void forcedClose() throws Exception {
    RecordingTransport stuck = new RecordingTransport();
    UUID sid = UUID.randomUUID();
    WsConnection c = registry.open("s", principal(UUID.randomUUID(), sid), stuck);
    stuck.blockSend = new java.util.concurrent.CountDownLatch(1);
    c.sendControl(ServerFrames.pong(1, clock.instant()));
    Thread.sleep(50);
    long start = System.nanoTime();
    registry.onSessionRevoked(
        new SessionRevokedEvent(sid, c.userId(), RevocationReason.ADMIN_TERMINATED, null));
    assertThat(stuck.closed.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(TimeUnit.NANOSECONDS.toMillis(stuck.closedAtNanos - start)).isLessThan(1_000);
    assertThat(stuck.closes).containsExactly(WsConnection.CLOSE_UNAUTHENTICATED);
    stuck.blockSend.countDown();
  }

  @Test
  @DisplayName(
      "T-API-007c 59 s without a client message keep the connection, 60 s close 4408 (server"
          + " clock); every client message restarts the timeout")
  void clientTimeout() throws Exception {
    RecordingTransport t = new RecordingTransport();
    WsConnection c = registry.open("t", principal(UUID.randomUUID(), UUID.randomUUID()), t);
    clock.advance(30_000);
    c.onText("{\"op\":\"ping\",\"t\":1}", true, clock.millis(), manager);
    clock.advance(59_999);
    registry.sweepTimeouts();
    Thread.sleep(50);
    assertThat(t.closes).isEmpty();
    clock.advance(1);
    registry.sweepTimeouts();
    assertThat(t.closed.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(t.closes).containsExactly(WsConnection.CLOSE_TIMEOUT);
  }

  @Test
  @DisplayName("T-API-007c shutdown closes every connection with 1012")
  void shutdown() throws Exception {
    RecordingTransport t1 = new RecordingTransport();
    RecordingTransport t2 = new RecordingTransport();
    WsConnection c1 = registry.open("1", principal(UUID.randomUUID(), UUID.randomUUID()), t1);
    WsConnection c2 = registry.open("2", principal(UUID.randomUUID(), UUID.randomUUID()), t2);
    registry.start();
    Thread closer =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    t1.closed.await(5, TimeUnit.SECONDS);
                    t2.closed.await(5, TimeUnit.SECONDS);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                  registry.closed(c1);
                  registry.closed(c2);
                });
    registry.stop();
    closer.join();
    assertThat(t1.closes).containsExactly(WsConnection.CLOSE_RESTART);
    assertThat(t2.closes).containsExactly(WsConnection.CLOSE_RESTART);
    assertThat(registry.size()).isZero();
    assertThat(registry.isRunning()).isFalse();
  }
}
