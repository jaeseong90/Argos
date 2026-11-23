/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.ByeReason;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.alert.engine.ActiveAlert;
import io.argos.server.alert.engine.AlertEngine;
import io.argos.server.alert.engine.AlertState;
import io.argos.server.alert.engine.UnitState;
import io.argos.server.auth.Role;
import io.argos.server.session.InstanceStateTracker;
import io.argos.server.session.InstanceStateWriter;
import io.argos.server.session.SessionRegistry;
import io.argos.server.session.api.InstanceState;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.MutableClock;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.ws.WsTestClient;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The alert engine with the instance states and the warm-up grace over the whole server (gRPC
 * session, monitor, ingest, engine) on real PostgreSQL and ClickHouse, with the server clock moved
 * by hand (S-17 automatic part, F-S25): an {@code AgentBye} followed by a reconnect within 5
 * minutes fires no "instance down" alert, an instance that does not come back fires it (and its
 * reconnect resolves it, restored from {@code alert_states} after a server restart), and the p95
 * rule is not evaluated during the 3-minute warm-up of a new agent epoch.
 */
@Tag("integration")
@Tag("req:F-S25")
@Tag("req:S-17")
class AlertInstanceStateIT {

  private static final MutableClock CLOCK = new MutableClock();
  private static AlertStack stack;

  @BeforeAll
  static void start() {
    stack = AlertStack.start(CLOCK);
  }

  @AfterAll
  static void stop() {
    if (stack != null) {
      stack.close();
    }
  }

  private static InstanceState state(int instanceId) {
    return stack.server.bean(InstanceStateTracker.class).state(instanceId);
  }

  /**
   * An {@code AgentBye} the server has handled (its {@code stop} event), then the session's end.
   */
  private static void bye(FakeAgent a, int id) throws Exception {
    long stops = stopEvents(id);
    a.sendBye(ByeReason.SHUTDOWN);
    a.completeSession();
    Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> stopEvents(id) > stops);
    a.cancelSession();
    Awaitility.await()
        .atMost(10, TimeUnit.SECONDS)
        .until(() -> stack.server.bean(SessionRegistry.class).find(id).isEmpty());
  }

  private static long stopEvents(int id) throws Exception {
    assertThat(stack.server.bean(InstanceStateWriter.class).flush()).isTrue();
    return (Long)
        stack
            .rows("SELECT count(*) FROM argos.events WHERE instance_id = ? AND type = 'stop'", id)
            .get(0)
            .get(0);
  }

  private static AlertState unitState(UUID rule, String unit) {
    UnitState s = stack.engine().state(rule, unit);
    return s == null ? AlertState.OK : s.state();
  }

  @Test
  @DisplayName(
      "T-SV-023 AgentBye then a reconnect after 4:59: no instance-down alert (no event, OK);"
          + " without a reconnect DOWN at 5:00 fires it (alert_events, alert_states, active"
          + " alerts, WebSocket alert push), ACK stops it (FIRING only), the state survives a"
          + " restart and the reconnect resolves it")
  @Tag("req:F-S26")
  void byeGraceNoDownAlert() throws Exception {
    AgentTokens.IssuedToken token = stack.app("alert-bye");
    stack.engine().housekeepingNow();
    UUID rule = stack.templateRule(token.appId(), "instance_down");
    int id;
    try (FakeAgent a = stack.agent(token, "alert-bye", "host-b:8080")) {
      assertThat(a.connect().accepted()).isTrue();
      id = a.instanceId();
      assertThat(a.heartbeatRound(Duration.ofSeconds(5))).isPresent();
      bye(a, id);
    }
    for (int s = 0; s < 29; s++) {
      CLOCK.advance(Duration.ofSeconds(10));
      stack.tick();
    }
    CLOCK.advance(Duration.ofSeconds(9));
    stack.tick();
    assertThat(state(id)).isEqualTo(InstanceState.UP);
    String unit = "instance:" + id;
    try (FakeAgent again = stack.agent(token, "alert-bye", "host-b:8080")) {
      assertThat(again.connect().accepted()).isTrue();
      assertThat(again.instanceId()).isEqualTo(id);
      for (int s = 0; s < 4; s++) {
        CLOCK.advance(Duration.ofSeconds(30));
        assertThat(again.heartbeatRound(Duration.ofSeconds(5))).isPresent();
        stack.tick();
        stack.engine().evaluateNow();
      }
      assertThat(unitState(rule, unit)).isEqualTo(AlertState.OK);
      assertThat(stack.events(rule)).as("no instance-down event across the restart").isEmpty();
      bye(again, id);
    }
    CLOCK.advance(Duration.ofSeconds(299));
    stack.tick();
    stack.engine().evaluateNow();
    assertThat(unitState(rule, unit)).isEqualTo(AlertState.OK);
    TestUsers.TestUser viewer = new TestUsers(stack.env).create(Role.VIEWER);
    ApiTestClient client = stack.server.client();
    assertThat(client.login(viewer.username(), viewer.password()).status()).isEqualTo(200);
    try (WsTestClient ws = WsTestClient.open(client)) {
      ws.subscribe("al", "alert", "{\"env\":\"prod\"}");
      ws.expect("ack", "al", Duration.ofSeconds(5));
      // The ack precedes the bus subscription (14 §5.1); frames are handled in order, so the pong
      // of a later ping proves the subscription is attached.
      ws.ping(1);
      ws.expect("pong", null, Duration.ofSeconds(5));
      CLOCK.advance(Duration.ofSeconds(1));
      stack.tick();
      assertThat(state(id)).isEqualTo(InstanceState.DOWN);
      stack.engine().evaluateNow();
      assertThat(unitState(rule, unit)).isEqualTo(AlertState.FIRING);
      assertThat(stack.events(rule)).containsExactly("FIRING");
      WsTestClient.Frame push =
          ws.await(
                  f ->
                      "push".equals(f.op())
                          && "alert".equals(f.json().path("topic").asString())
                          && rule.toString()
                              .equals(
                                  f.json()
                                      .path("data")
                                      .path("event")
                                      .path("rule")
                                      .path("id")
                                      .asString()),
                  Duration.ofSeconds(5))
              .orElseThrow(() -> new AssertionError("no alert push: " + ws.frames()));
      assertThat(push.json().path("data").path("event").path("state").asString())
          .isEqualTo("FIRING");
      assertThat(push.json().path("data").path("event").path("app").path("id").asInt())
          .isEqualTo(token.appId());
      // C-228 (2): every push carries the reason of its transition, the alert_events value.
      JsonNode pushed = push.json().path("data").path("event");
      assertThat(pushed.path("reason").asString())
          .as("push reason = alert_events.reason of the same event")
          .isEqualTo("condition")
          .isEqualTo(
              stack
                  .rows(
                      "SELECT reason FROM argos.alert_events WHERE id = ?",
                      UUID.fromString(pushed.path("event_id").asString()))
                  .get(0)
                  .get(0));
    }
    assertThat(
            stack.rows(
                "SELECT state, severity, target_kind, target_id FROM argos.alert_states"
                    + " WHERE rule_id = ? AND unit_key = ?",
                rule,
                unit))
        .containsExactly(List.of("FIRING", "critical", "instance", (long) id));
    List<ActiveAlert> active = new ArrayList<>(stack.engine().activeAlerts());
    assertThat(active)
        .filteredOn(x -> x.ruleId().equals(rule))
        .singleElement()
        .satisfies(
            x -> {
              assertThat(x.appId()).isEqualTo(token.appId());
              assertThat(x.appName()).isEqualTo("alert-bye");
              assertThat(x.severity()).isEqualTo("critical");
              assertThat(x.state()).isEqualTo("FIRING");
              assertThat(x.targetType()).isEqualTo("instance");
              assertThat(x.targetName()).isEqualTo("host-b:8080");
              assertThat(x.deeplink())
                  .startsWith(
                      stack.server.baseUrl()
                          + "/apps/"
                          + token.appId()
                          + "/dashboard?env=prod&from=")
                  .contains("&f.instance=" + id + "&alert=" + x.eventId());
            });

    UUID firing =
        active.stream().filter(x -> x.ruleId().equals(rule)).findFirst().orElseThrow().eventId();
    CLOCK.advance(Duration.ofSeconds(1));
    assertThat(stack.engine().ack(firing, viewer.id(), viewer.username(), "checking the host"))
        .isEqualTo(AlertEngine.AckResult.ACKED);
    assertThat(stack.engine().ack(firing, viewer.id(), viewer.username(), null))
        .as("ACK of an ACKED alert")
        .isEqualTo(AlertEngine.AckResult.NOT_FIRING);
    assertThat(stack.engine().ack(UUID.randomUUID(), viewer.id(), viewer.username(), null))
        .isEqualTo(AlertEngine.AckResult.NOT_FOUND);
    assertThat(
            stack.rows(
                "SELECT to_state, acked_by, ack_note FROM argos.alert_events WHERE rule_id = ?"
                    + " AND to_state = 'ACKED'",
                rule))
        .containsExactly(List.of("ACKED", viewer.id(), "checking the host"));
    assertThat(stack.engine().activeAlerts())
        .filteredOn(x -> x.ruleId().equals(rule))
        .singleElement()
        .satisfies(
            x -> {
              assertThat(x.state()).isEqualTo("ACKED");
              assertThat(x.eventId()).isEqualTo(firing);
              assertThat(x.ackedByName()).isEqualTo(viewer.username());
              assertThat(x.note()).isEqualTo("checking the host");
            });

    stack.restartServer();
    assertThat(unitState(rule, unit)).as("restored from alert_states").isEqualTo(AlertState.ACKED);
    try (FakeAgent back = stack.agent(token, "alert-bye", "host-b:8080")) {
      assertThat(back.connect().accepted()).isTrue();
      Awaitility.await()
          .atMost(20, TimeUnit.SECONDS)
          .until(
              () -> {
                stack.engine().evaluateNow();
                return unitState(rule, unit) == AlertState.OK
                    || unitState(rule, unit) == AlertState.RESOLVED;
              });
      assertThat(stack.events(rule)).containsExactly("FIRING", "ACKED", "RESOLVED");
      bye(back, id);
    }
  }

  @Test
  @DisplayName(
      "T-SV-023 warm-up: slow requests (p95 20 s, 30 per minute) during the first 3 minutes of a"
          + " new agent epoch do not evaluate the p95 rule (no PENDING); after 3 minutes the same"
          + " traffic does")
  void warmupGrace() throws Exception {
    AgentTokens.IssuedToken token = stack.app("alert-warm");
    stack.engine().housekeepingNow();
    UUID rule = stack.templateRule(token.appId(), "p95_latency");
    keepLive();
    try (FakeAgent a = stack.agent(token, "alert-warm", "host-w:8080")) {
      assertThat(a.connect().accepted()).isTrue();
      CLOCK.advance(Duration.ofSeconds(60));
      slowTraffic(a);
      stack.engine().evaluateNow();
      assertThat(unitState(rule, "app")).as("within the 3-minute warm-up").isEqualTo(AlertState.OK);
      assertThat(stack.events(rule)).isEmpty();

      CLOCK.advance(Duration.ofSeconds(121));
      assertThat(a.heartbeatRound(Duration.ofSeconds(5))).isPresent();
      slowTraffic(a);
      stack.engine().evaluateNow();
      assertThat(unitState(rule, "app")).isEqualTo(AlertState.PENDING);
      assertThat(stack.events(rule)).containsExactly("PENDING");
      bye(a, a.instanceId());
    }
  }

  @Test
  @DisplayName(
      "T-SV-023 no_data ignore instance units whose instance is gone resolve (C-226 (12)): a"
          + " FIRING unit of a deleted instance at the next housekeeping, a FIRING unit of an"
          + " instance without a session when it becomes DOWN (alert_events RESOLVED, value"
          + " null); a stored UP instance without a session keeps FIRING")
  void targetGoneResolves() throws Exception {
    AgentTokens.IssuedToken token = stack.app("alert-gone");
    stack.engine().housekeepingNow();
    // Active transactions: instance units, no_data ignore (the default).
    UUID rule = stack.templateRule(token.appId(), "active_transactions");
    assertThat(
            stack.rows(
                "SELECT spec->>'evaluation_unit', coalesce(spec->>'no_data', 'ignore')"
                    + " FROM argos.alert_rules WHERE id = ?",
                rule))
        .containsExactly(List.of("instance", "ignore"));
    int id;
    try (FakeAgent a = stack.agent(token, "alert-gone", "host-g:8080")) {
      assertThat(a.connect().accepted()).isTrue();
      id = a.instanceId();
      assertThat(a.heartbeatRound(Duration.ofSeconds(5))).isPresent();
      bye(a, id);
    }
    int deleted = 2_000_000_000;
    String unit = "instance:" + id;
    String deletedUnit = "instance:" + deleted;
    firingRow(rule, token.appId(), unit, id);
    firingRow(rule, token.appId(), deletedUnit, deleted);
    stack.restartServer();
    // (The engine's own first housekeeping may already have resolved the deleted one.)
    assertThat(unitState(rule, unit)).as("restored").isEqualTo(AlertState.FIRING);

    stack.engine().housekeepingNow();
    stack.engine().evaluateNow();
    assertThat(unitState(rule, deletedUnit)).as("deleted instance").isEqualTo(AlertState.RESOLVED);
    assertThat(unitState(rule, unit))
        .as("stored UP, no session yet: not gone")
        .isEqualTo(AlertState.FIRING);

    // The restored instance has no heartbeat: STALE after 60 s, DOWN after 180 s (12 §4.2).
    for (int s = 0; s < 19 && state(id) != InstanceState.DOWN; s++) {
      CLOCK.advance(Duration.ofSeconds(10));
      stack.tick();
    }
    assertThat(state(id)).isEqualTo(InstanceState.DOWN);
    stack.engine().evaluateNow();
    assertThat(unitState(rule, unit)).as("DOWN instance").isEqualTo(AlertState.RESOLVED);
    assertThat(
            stack.rows(
                "SELECT target_id, from_state, to_state, value FROM argos.alert_events"
                    + " WHERE rule_id = ? ORDER BY target_id",
                rule))
        .containsExactly(
            Arrays.asList((long) id, "FIRING", "RESOLVED", null),
            Arrays.asList((long) deleted, "FIRING", "RESOLVED", null));
    assertThat(stack.engine().activeAlerts()).noneMatch(x -> x.ruleId().equals(rule));
  }

  /** A FIRING {@code alert_states} row of an instance unit (restored at the next start). */
  private static void firingRow(UUID rule, int appId, String unit, int instanceId)
      throws Exception {
    try (Connection c = stack.env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO argos.alert_states (rule_id, unit_key, app_id, env, target_kind,"
                    + " target_id, target_name, state, severity, state_since, pending_since,"
                    + " firing_since, last_notified_at, event_id, event_ts, value, threshold)"
                    + " VALUES (?, ?, ?, 'prod', 'instance', ?, 'gone', 'FIRING', 'critical',"
                    + " ?, ?, ?, ?, ?, ?, 0.9, 0.8)")) {
      Timestamp at = new Timestamp(CLOCK.millis());
      ps.setObject(1, rule);
      ps.setString(2, unit);
      ps.setInt(3, appId);
      ps.setLong(4, instanceId);
      ps.setTimestamp(5, at);
      ps.setTimestamp(6, at);
      ps.setTimestamp(7, at);
      ps.setTimestamp(8, at);
      ps.setObject(9, UUID.randomUUID());
      ps.setTimestamp(10, at);
      assertThat(ps.executeUpdate()).isEqualTo(1);
    }
  }

  /** Moves the server clock to the real time at least (records count as live within 5 s). */
  private static void keepLive() {
    long behind = System.currentTimeMillis() + 1_000 - CLOCK.millis();
    if (behind > 0) {
      CLOCK.advance(Duration.ofMillis(behind));
    }
  }

  /** 30 transactions of 20 s that ended now, and the wait for the alert windows to have them. */
  private static void slowTraffic(FakeAgent a) {
    keepLive();
    long before = stack.xlogPoints();
    long now = a.clock().nowMs();
    List<TransactionRecord> txs = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      txs.add(a.records().http("GET /slow", now - 20_000 - i, 20_000_000).build());
    }
    assertThat(a.sendTransactions(a.batches().transactions(txs)).ok()).isTrue();
    Awaitility.await().atMost(30, TimeUnit.SECONDS).until(() -> stack.xlogPoints() >= before + 30);
    AlertEngine engine = stack.engine();
    assertThat(engine).isNotNull();
  }
}
