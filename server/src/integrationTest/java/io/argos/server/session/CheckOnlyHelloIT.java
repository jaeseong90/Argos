/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.RejectReason;
import io.argos.server.alert.engine.AlertEngine;
import io.argos.server.session.api.InstanceState;
import io.argos.server.testing.auth.MutableClock;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.grpc.Status;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-SV-022b (ADR-0010 C-240 (1)): a check-only Hello ({@code AgentHello.check_only}, the agent's
 * {@code --check}) over the whole server (TLS gRPC session, real PostgreSQL and ClickHouse, the
 * monitor and the alert engine on a clock moved by hand): it is answered without a session and its
 * stream ends, and the server keeps nothing of it: no {@code instances} row, no {@code
 * pending_registrations} row, no app variant, {@code argos_sessions_active} unchanged, no instance
 * state and no {@code events} row, and no instance-down alert however long the clock runs.
 */
@Tag("integration")
@Tag("req:C-240")
@Tag("req:F-A27")
class CheckOnlyHelloIT {

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

  private static double sessionsActive() {
    return stack.server.bean(MeterRegistry.class).get("argos.sessions.active").gauge().value();
  }

  private static UUID instanceDownRule(int appId) throws Exception {
    stack.server.bean(AlertEngine.class).housekeepingNow();
    try (var c = stack.env.connect(io.argos.server.testing.ServerTestEnvironment.PgAccount.APP);
        var ps =
            c.prepareStatement(
                "SELECT id FROM argos.alert_rules WHERE app_id = ? AND template_key ="
                    + " 'instance_down' AND deleted_at IS NULL")) {
      ps.setInt(1, appId);
      try (var rs = ps.executeQuery()) {
        assertThat(rs.next()).as("instance_down template rule of app " + appId).isTrue();
        return (UUID) rs.getObject(1);
      }
    }
  }

  @Test
  @DisplayName(
      "T-SV-022b a check-only Hello is accepted (app id, instance 0, no session id) and its stream"
          + " ends OK; no instances or pending_registrations row, sessions gauge unchanged, no"
          + " state, no event, no instance-down alert after 10 minutes")
  void nothingKept() throws Exception {
    String app = "check-only-it";
    AgentTokens.IssuedToken token = stack.token(app);
    UUID rule = instanceDownRule(token.appId());
    double sessionsBefore = sessionsActive();
    int registryBefore = stack.server.bean(SessionRegistry.class).size();
    Map<InstanceState, Integer> countsBefore =
        stack.server.bean(InstanceStateTracker.class).counts();
    try (FakeAgent check =
        stack.agent(
            token, app, "host-c-check", Duration.ZERO, h -> h.setCheckOnly(true).setPid(4242))) {
      HelloResult r = check.connect();
      assertThat(r.accepted()).as(r.toString()).isTrue();
      assertThat(r.hello().getAppId()).isEqualTo(token.appId());
      assertThat(r.hello().getInstanceId()).isZero();
      assertThat(r.hello().getSessionId()).isEmpty();
      assertThat(r.hello().getServerVersion()).isNotEmpty();
      assertThat(r.hello().getServerTimeMs()).isEqualTo(CLOCK.millis());
      assertThat(r.hello().hasLimits()).isTrue();
      assertThat(r.hello().getConfigMap()).isEmpty();
      assertThat(r.hello().getUserIdSalt().isEmpty()).isTrue();
      HelloResult end = check.awaitSessionEnd(Duration.ofSeconds(10));
      assertThat(end.code()).as("the server ends the stream").isEqualTo(Status.Code.OK);
    }
    stack.flush();
    assertThat(stack.count("SELECT count(*) FROM argos.instances WHERE app_id = ?", token.appId()))
        .as("no instance registered")
        .isZero();
    assertThat(stack.count("SELECT count(*) FROM argos.pending_registrations WHERE name = ?", app))
        .isZero();
    assertThat(stack.count("SELECT count(*) FROM argos.events WHERE app_id = ?", token.appId()))
        .as("no state, start or stop event")
        .isZero();
    assertThat(sessionsActive()).isEqualTo(sessionsBefore);
    assertThat(stack.server.bean(SessionRegistry.class).size()).isEqualTo(registryBefore);
    assertThat(stack.server.bean(InstanceStateTracker.class).counts()).isEqualTo(countsBefore);

    AlertEngine engine = stack.server.bean(AlertEngine.class);
    for (int i = 0; i < 60; i++) {
      CLOCK.advance(Duration.ofSeconds(10));
      stack.tick();
      engine.evaluateNow();
    }
    assertThat(stack.count("SELECT count(*) FROM argos.alert_events WHERE rule_id = ?", rule))
        .as("no instance-down alert for the check")
        .isZero();
    assertThat(stack.count("SELECT count(*) FROM argos.alert_states WHERE rule_id = ?", rule))
        .isZero();
    assertThat(engine.activeAlerts()).noneMatch(a -> rule.equals(a.ruleId()));
  }

  @Test
  @DisplayName(
      "T-SV-022b check-only for a missing environment: with the app's policy 'approval' it is"
          + " APP_NOT_REGISTERED and nothing is queued; with automatic registration it is accepted"
          + " with app id 0 and no app variant is created")
  void missingApp() throws Exception {
    String app = "check-only-env";
    AgentTokens.IssuedToken token = stack.token(app);
    long apps = stack.count("SELECT count(*) FROM argos.apps WHERE name = ?", app);
    try (FakeAgent check =
        stack.agent(
            token, app, "host-d-check", Duration.ZERO, h -> h.setCheckOnly(true).setEnv("qa"))) {
      HelloResult r = check.connect();
      assertThat(r.accepted()).as(r.toString()).isTrue();
      assertThat(r.hello().getAppId()).as("not registered: app id 0").isZero();
      assertThat(r.hello().getInstanceId()).isZero();
    }
    assertThat(stack.count("SELECT count(*) FROM argos.apps WHERE name = ?", app))
        .as("no app variant created")
        .isEqualTo(apps);

    try (var c = stack.env.connect(io.argos.server.testing.ServerTestEnvironment.PgAccount.APP);
        var ps = c.prepareStatement("UPDATE argos.apps SET auto_register = false WHERE id = ?")) {
      ps.setInt(1, token.appId());
      assertThat(ps.executeUpdate()).isEqualTo(1);
    }
    try (FakeAgent check =
        stack.agent(
            token, app, "host-d-check", Duration.ZERO, h -> h.setCheckOnly(true).setEnv("qa2"))) {
      HelloResult r = check.connect();
      assertThat(r.accepted()).isFalse();
      assertThat(r.rejectReason()).isEqualTo(RejectReason.APP_NOT_REGISTERED);
      assertThat(r.code()).isEqualTo(Status.Code.OK);
    }
    assertThat(stack.count("SELECT count(*) FROM argos.pending_registrations WHERE name = ?", app))
        .as("a check is never queued for approval")
        .isZero();
    assertThat(stack.count("SELECT count(*) FROM argos.instances WHERE app_id = ?", token.appId()))
        .isZero();
  }
}
