/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.ByeReason;
import io.argos.server.alert.engine.AlertEngine;
import io.argos.server.session.api.InstanceState;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.MutableClock;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-SV-023b (F-A26, S-17, ADR-0010 C-240 (1)): the agent start event over the whole server (TLS
 * gRPC session, real PostgreSQL and ClickHouse, the monitor and the alert engine on a clock moved
 * by hand). One {@code events} row {@code type = 'start'} (title {@code Agent start}, detail {@code
 * agent_version}) per agent process; a reconnect of the same process writes none, also after a
 * server restart (the stored {@code started_at} is its {@code process_start_ms}); a Bye followed by
 * a restarted process is {@code stop} then {@code start}, with no instance-down alert.
 */
@Tag("integration")
@Tag("req:F-A26")
@Tag("req:S-17")
@Tag("req:C-240")
class AgentStartEventIT {

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

  /** The non-state events of an instance, oldest first (type and detail). */
  private static List<String> lifecycle(int instanceId) throws Exception {
    stack.flush();
    try (Connection c = stack.env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT type, title, detail::text FROM argos.events WHERE instance_id = ? AND type"
                    + " <> 'state' ORDER BY ts, CASE type WHEN 'stop' THEN 0 ELSE 1 END")) {
      ps.setInt(1, instanceId);
      List<String> out = new java.util.ArrayList<>();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3));
        }
      }
      return out;
    }
  }

  private static UUID instanceDownRule(int appId) throws Exception {
    stack.server.bean(AlertEngine.class).housekeepingNow();
    try (Connection c = stack.env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT id FROM argos.alert_rules WHERE app_id = ? AND template_key ="
                    + " 'instance_down' AND deleted_at IS NULL")) {
      ps.setInt(1, appId);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return (UUID) rs.getObject(1);
      }
    }
  }

  private static FakeAgent agent(
      AgentTokens.IssuedToken token, String app, long epoch, long processStartMs) {
    return stack.agent(
        token,
        app,
        "host-s:8080",
        Duration.ZERO,
        h -> h.setAgentEpoch(epoch).setProcessStartMs(processStartMs).setAgentVersion("0.0.0"));
  }

  private static void bye(FakeAgent a, int id) throws Exception {
    a.sendBye(ByeReason.SHUTDOWN);
    a.completeSession();
    Awaitility.await()
        .atMost(10, TimeUnit.SECONDS)
        .until(() -> lifecycle(id).stream().anyMatch(e -> e.startsWith("stop|")));
    a.cancelSession();
    Awaitility.await()
        .atMost(10, TimeUnit.SECONDS)
        .until(() -> stack.server.bean(SessionRegistry.class).find(id).isEmpty());
  }

  @Test
  @DisplayName(
      "T-SV-023b one start per agent process: the first Hello writes 'Agent start' with"
          + " agent_version; the same process reconnecting (also after a server restart) writes"
          + " none; Bye and a restarted process is stop then start with no instance-down alert")
  void startEvents() throws Exception {
    String app = "agent-start-it";
    AgentTokens.IssuedToken token = stack.token(app);
    UUID rule = instanceDownRule(token.appId());
    long processStart = CLOCK.millis() - 60_000;
    int id;
    try (FakeAgent a = agent(token, app, 101, processStart)) {
      assertThat(a.connect().accepted()).isTrue();
      id = a.instanceId();
      assertThat(lifecycle(id)).containsExactly("start|Agent start|{\"agent_version\": \"0.0.0\"}");
      // The same process reconnects (a network blip): no start.
      assertThat(a.connect().accepted()).isTrue();
      assertThat(a.instanceId()).isEqualTo(id);
      assertThat(lifecycle(id)).hasSize(1);
      a.cancelSession();
    }

    // The server restarts; the same agent process reconnects to the new server: no start.
    stack.restartServer(Duration.ofSeconds(20));
    try (FakeAgent same = agent(token, app, 101, processStart)) {
      assertThat(same.connect().accepted()).isTrue();
      assertThat(same.instanceId()).isEqualTo(id);
      assertThat(lifecycle(id)).as("the same process after a server restart").hasSize(1);
      bye(same, id);
    }
    assertThat(lifecycle(id))
        .extracting(e -> e.substring(0, e.indexOf('|')))
        .containsExactly("start", "stop");

    // The application restarts within the Bye grace: a new process (epoch, process start).
    CLOCK.advance(Duration.ofSeconds(40));
    stack.tick();
    try (FakeAgent restarted = agent(token, app, 202, CLOCK.millis() - 5_000)) {
      assertThat(restarted.connect().accepted()).isTrue();
      assertThat(restarted.instanceId()).isEqualTo(id);
      for (int i = 0; i < 4; i++) {
        CLOCK.advance(Duration.ofSeconds(30));
        assertThat(restarted.heartbeatRound(Duration.ofSeconds(5))).isPresent();
        stack.tick();
        stack.server.bean(AlertEngine.class).evaluateNow();
      }
      assertThat(stack.state(id)).isEqualTo(InstanceState.UP);
      assertThat(lifecycle(id))
          .extracting(e -> e.substring(0, e.indexOf('|')))
          .containsExactly("start", "stop", "start");
      restarted.cancelSession();
    }
    assertThat(stack.count("SELECT count(*) FROM argos.alert_events WHERE rule_id = ?", rule))
        .as("no instance-down alert across the stop and the start")
        .isZero();
    assertThat(
            stack.count(
                "SELECT count(*) FROM argos.events WHERE instance_id = ? AND type = 'state'"
                    + " AND detail->>'to' IN ('DOWN', 'STALE')",
                id))
        .as("the start rows make no transition; the Bye grace kept the instance UP")
        .isZero();
  }
}
