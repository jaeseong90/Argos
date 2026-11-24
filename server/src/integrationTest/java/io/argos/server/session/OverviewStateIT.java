/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.AgentEvent;
import io.argos.proto.v1.AgentEventType;
import io.argos.server.session.api.InstanceState;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.MutableClock;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.ws.WsTestClient;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The application status and up count of {@code GET /api/v1/overview} and {@code GET /api/v1/apps}
 * from the instance state machine (ADR-0010 C-216 (3), WO-31) on the whole server (gRPC sessions,
 * the monitor, the query API) with real PostgreSQL and ClickHouse, the server clock moved by hand
 * ({@link MutableClock}; the fake agents' clocks follow it): status is the worst state of the
 * application's instances, {@code instances.up} counts those in {@code UP} or {@code WARN}. The
 * {@code per_instance[].state} of {@code GET /apps/{appId}/active} is the same instance state, not
 * a fixed {@code UP} (ADR-0010 C-218 (4)).
 *
 * <p>The overview result is cached for 5 s of real time per filter (14 §4.2, Caffeine's ticker, not
 * the server clock): every overview check polls until the cache entry was rebuilt, at most 15 s;
 * {@code GET /apps} is not cached and is read after the overview agrees.
 */
@Tag("integration")
@Tag("req:F-U17")
@Tag("req:F-S28")
class OverviewStateIT {

  private static final MutableClock CLOCK = new MutableClock();
  private static StateStack stack;

  @BeforeAll
  static void start() {
    stack = StateStack.start(CLOCK);
  }

  @AfterAll
  static void stop() {
    if (stack != null) {
      stack.close();
    }
  }

  @Test
  @DisplayName(
      "T-SV-022 overview and apps status from the instance state machine: two agents UP 2/2, one"
          + " silent STALE 1/2 at 60 s and DOWN 1/2 at 180 s, back UP 2/2, kill switch WARN 2/2;"
          + " GET /overview and GET /apps agree, GET /apps/{id}/active per_instance.state follows"
          + " the instance state (C-218 (4))")
  void overviewFollowsInstanceStates() throws Exception {
    AgentTokens.IssuedToken token = stack.token("overview-state");
    int appId = token.appId();
    try (FakeAgent a = stack.agent(token, "overview-state", "host-a:8080", Duration.ZERO);
        FakeAgent b = stack.agent(token, "overview-state", "host-b:8080", Duration.ZERO)) {
      assertThat(a.connect().accepted()).isTrue();
      assertThat(b.connect().accepted()).isTrue();
      int ia = a.instanceId();
      int ib = b.instanceId();
      assertThat(ia).isNotEqualTo(ib);
      assertThat(a.heartbeatRound(Duration.ofSeconds(5))).isPresent();
      assertThat(b.heartbeatRound(Duration.ofSeconds(5))).isPresent();
      assertThat(stack.state(ia)).isEqualTo(InstanceState.UP);
      assertThat(stack.state(ib)).isEqualTo(InstanceState.UP);
      expect(appId, "UP", 2, 2);

      // b falls silent: STALE 60 s after its last heartbeat while a keeps beating.
      CLOCK.advance(Duration.ofSeconds(59));
      assertThat(a.heartbeatRound(Duration.ofSeconds(5))).isPresent();
      stack.tick();
      assertThat(stack.state(ib)).isEqualTo(InstanceState.UP);
      CLOCK.advance(Duration.ofSeconds(1));
      stack.tick();
      assertThat(stack.state(ib)).isEqualTo(InstanceState.STALE);
      assertThat(stack.state(ia)).isEqualTo(InstanceState.UP);
      // Both sessions are still open: the former "every instance has a session" rule said UP.
      assertThat(stack.server.bean(SessionRegistry.class).byApp(appId)).hasSize(2);
      expect(appId, "STALE", 1, 2);
      // The silent instance still has its session, so /active lists it, as STALE (C-218 (4)).
      assertThat(activeState(appId, ib)).as("/active per_instance state of b").isEqualTo("STALE");
      assertThat(activeState(appId, ia)).as("/active per_instance state of a").isEqualTo("UP");

      // DOWN 180 s after its last heartbeat.
      CLOCK.advance(Duration.ofSeconds(59));
      assertThat(a.heartbeatRound(Duration.ofSeconds(5))).isPresent();
      stack.tick();
      CLOCK.advance(Duration.ofSeconds(59));
      assertThat(a.heartbeatRound(Duration.ofSeconds(5))).isPresent();
      stack.tick();
      assertThat(stack.state(ib)).as("178 s").isEqualTo(InstanceState.STALE);
      CLOCK.advance(Duration.ofSeconds(2));
      stack.tick();
      assertThat(stack.state(ib)).as("180 s").isEqualTo(InstanceState.DOWN);
      assertThat(stack.state(ia)).isEqualTo(InstanceState.UP);
      expect(appId, "DOWN", 1, 2);

      // b beats again: UP.
      assertThat(b.heartbeatRound(Duration.ofSeconds(5))).isPresent();
      assertThat(stack.state(ib)).isEqualTo(InstanceState.UP);
      expect(appId, "UP", 2, 2);

      // a reports its kill switch on: WARN, which still counts as up.
      a.sendEvent(AgentEvent.newBuilder().setType(AgentEventType.KILL_SWITCH_ON).build());
      Awaitility.await()
          .atMost(10, TimeUnit.SECONDS)
          .until(() -> stack.state(ia) == InstanceState.WARN);
      expect(appId, "WARN", 2, 2);
      assertThat(activeState(appId, ia)).as("/active per_instance state of a").isEqualTo("WARN");
      assertThat(activeState(appId, ib)).as("/active per_instance state of b").isEqualTo("UP");
    }
  }

  @Test
  @DisplayName(
      "T-SV-022a WS overview status on the REST basis (C-218 (3)): an instance that never"
          + " connected since the start (not tracked) makes the app DOWN in the WS push as in GET"
          + " /overview and GET /apps (DOWN 2/3); archived instances (one silent STALE with its"
          + " session, the untracked one) count nowhere: WS push = REST = UP 1/1")
  void wsOverviewOnRestBasis() throws Exception {
    AgentTokens.IssuedToken token = stack.token("overview-c218");
    int appId = token.appId();
    try (FakeAgent a = stack.agent(token, "overview-c218", "c218-a:8080", Duration.ZERO);
        FakeAgent b = stack.agent(token, "overview-c218", "c218-b:8080", Duration.ZERO);
        WsTestClient ws = WsTestClient.open(stack.viewer())) {
      assertThat(a.connect().accepted()).isTrue();
      assertThat(b.connect().accepted()).isTrue();
      int ib = b.instanceId();
      assertThat(a.heartbeatRound(Duration.ofSeconds(5))).isPresent();
      assertThat(b.heartbeatRound(Duration.ofSeconds(5))).isPresent();
      ws.subscribe("ov", "overview", "{}");
      ws.expect("ack", "ov", Duration.ofSeconds(10));
      expect(appId, "UP", 2, 2);
      expectWs(ws, appId, "UP", a, b);

      // A stored instance the state machine does not track (never connected since the start).
      int never = insertInstance(appId, "c218-never:8080");
      assertThat(stack.state(never)).isEqualTo(InstanceState.DOWN);
      expect(appId, "DOWN", 2, 3);
      // Before C-218 (3) the push knew only the sessions and state changes: UP.
      expectWs(ws, appId, "DOWN", a, b);

      // b falls silent: STALE at 60 s (its session stays open), then b and the untracked
      // instance are archived.
      CLOCK.advance(Duration.ofSeconds(30));
      ws.ping(30);
      assertThat(a.heartbeatRound(Duration.ofSeconds(5))).isPresent();
      CLOCK.advance(Duration.ofSeconds(30));
      ws.ping(60);
      assertThat(a.heartbeatRound(Duration.ofSeconds(5))).isPresent();
      stack.tick();
      assertThat(stack.state(ib)).isEqualTo(InstanceState.STALE);
      archive(ib);
      archive(never);
      expect(appId, "UP", 1, 1);
      // Before C-218 (3) the push still counted b's session (STALE) and its state change.
      expectWs(ws, appId, "UP", a);
    }
  }

  /**
   * Moves the server clock in 6 s steps (the 5 s overview push needs a closed counter window), the
   * {@code beating} agents heartbeating, until the {@code overview} push row of {@code appId} has
   * {@code status} (at most 8 steps); the last pushed status otherwise fails the test.
   */
  private static void expectWs(WsTestClient ws, int appId, String status, FakeAgent... beating) {
    String last = null;
    for (int round = 0; round < 8 && !status.equals(last); round++) {
      CLOCK.advance(Duration.ofSeconds(6));
      for (FakeAgent f : beating) {
        assertThat(f.heartbeatRound(Duration.ofSeconds(5))).isPresent();
      }
      stack.tick();
      ws.ping(round);
      Optional<WsTestClient.Frame> push =
          ws.await(f -> overviewPushRow(f, appId) != null, Duration.ofSeconds(3));
      if (push.isPresent()) {
        last = overviewPushRow(push.get(), appId).path("status").asString();
      }
    }
    assertThat(last).as("WS overview push status of app " + appId).isEqualTo(status);
  }

  private static JsonNode overviewPushRow(WsTestClient.Frame f, int appId) {
    if (!"push".equals(f.op()) || !"overview".equals(f.json().path("topic").asString())) {
      return null;
    }
    for (JsonNode app : f.json().path("data").path("apps")) {
      if (app.path("app_id").asInt() == appId) {
        return app;
      }
    }
    return null;
  }

  private static int insertInstance(int appId, String name) throws Exception {
    try (Connection c = stack.env.connect(PgAccount.APP);
        PreparedStatement st =
            c.prepareStatement(
                "INSERT INTO argos.instances (app_id, name, last_seen_at) VALUES (?, ?, now())"
                    + " RETURNING id")) {
      st.setInt(1, appId);
      st.setString(2, name);
      try (ResultSet rs = st.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getInt(1);
      }
    }
  }

  private static void archive(int instanceId) throws Exception {
    try (Connection c = stack.env.connect(PgAccount.APP);
        PreparedStatement st =
            c.prepareStatement("UPDATE argos.instances SET archived_at = now() WHERE id = ?")) {
      st.setInt(1, instanceId);
      assertThat(st.executeUpdate()).isEqualTo(1);
    }
  }

  /** The {@code per_instance[].state} of {@code instanceId} in {@code GET /apps/{appId}/active}. */
  private static String activeState(int appId, int instanceId) {
    for (JsonNode i :
        stack.get("/api/v1/apps/" + appId + "/active?kind=ALL").path("per_instance")) {
      if (i.path("instance_id").asInt() == instanceId) {
        return i.path("state").asString();
      }
    }
    throw new AssertionError("instance " + instanceId + " not in GET /apps/" + appId + "/active");
  }

  /**
   * The overview row of {@code appId} reaches {@code status} and {@code up}/{@code total} (within
   * the 5 s cache), and {@code GET /apps} (list and detail) report the same counts.
   */
  private static void expect(int appId, String status, int up, int total) {
    Awaitility.await()
        .pollInterval(500, TimeUnit.MILLISECONDS)
        .atMost(15, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              JsonNode row = overviewRow(appId);
              assertThat(row.path("status").asString()).as("overview status").isEqualTo(status);
              assertThat(row.path("instances").path("up").asInt()).as("overview up").isEqualTo(up);
              assertThat(row.path("instances").path("total").asInt())
                  .as("overview total")
                  .isEqualTo(total);
            });
    JsonNode listed = appsRow(appId);
    assertThat(listed.path("instances_up").asInt()).as("GET /apps instances_up").isEqualTo(up);
    assertThat(listed.path("instances_total").asInt())
        .as("GET /apps instances_total")
        .isEqualTo(total);
    JsonNode detail = stack.get("/api/v1/apps/" + appId);
    assertThat(detail.path("instances_up").asInt()).as("GET /apps/{id} instances_up").isEqualTo(up);
    assertThat(detail.path("instances_total").asInt()).isEqualTo(total);
  }

  private static JsonNode overviewRow(int appId) {
    for (JsonNode app : stack.get("/api/v1/overview").path("apps")) {
      if (app.path("app_id").asInt() == appId) {
        return app;
      }
    }
    throw new AssertionError("app " + appId + " not in the overview");
  }

  private static JsonNode appsRow(int appId) {
    for (JsonNode app : stack.get("/api/v1/apps")) {
      if (app.path("app_id").asInt() == appId) {
        return app;
      }
    }
    throw new AssertionError("app " + appId + " not in GET /apps");
  }
}
