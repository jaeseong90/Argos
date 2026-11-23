/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.CloseReason;
import io.argos.proto.v1.InstanceUpdate;
import io.argos.proto.v1.SessionClose;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.MutableClock;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 12 §4.3 "인스턴스 이름·보관" over the whole server on real PostgreSQL and ClickHouse: the merge of a
 * temporary {@code host:pid} instance into the existing instance of its new name (ADR-0010 C-158
 * (2): a restarted JVM continues as its old {@code instance_id}) and the daily retention job's
 * statements (archive at 7 days, delete at 30 days, the boundaries included).
 */
@Tag("integration")
@Tag("req:F-S28")
@Tag("req:C-158")
class InstanceNamingIT {

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

  private static double merged() {
    Counter c =
        stack
            .server
            .bean(MeterRegistry.class)
            .find("argos.collector.sessions.closed")
            .tag("reason", "instance_merged")
            .counter();
    return c == null ? 0 : c.count();
  }

  private static Instant archivedAt(int instanceId) throws SQLException {
    try (Connection c = stack.env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement("SELECT archived_at FROM argos.instances WHERE id = ?")) {
      ps.setInt(1, instanceId);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).as("instance %d exists", instanceId).isTrue();
        Timestamp t = rs.getTimestamp(1);
        return t == null ? null : t.toInstant();
      }
    }
  }

  private static boolean exists(int instanceId) throws SQLException {
    try (Connection c = stack.env.connect(PgAccount.APP);
        PreparedStatement ps = c.prepareStatement("SELECT 1 FROM argos.instances WHERE id = ?")) {
      ps.setInt(1, instanceId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  private static boolean listed(int appId, int instanceId) {
    for (JsonNode i : stack.get("/api/v1/apps/" + appId + "/instances")) {
      if (i.path("instance_id").asInt() == instanceId) {
        return true;
      }
    }
    return false;
  }

  @Test
  @DisplayName(
      "C-158 (2) a JVM restarted before its port is known registers as host:pid; its"
          + " InstanceUpdate to the old host:port (no live session) closes the session, the"
          + " reconnect continues as the old instance_id, the temporary instance is archived without"
          + " a state event and no longer listed")
  void restartedInstanceKeepsItsId() throws Exception {
    String app = "naming-merge";
    AgentTokens.IssuedToken token = stack.token(app);
    int old;
    try (FakeAgent first = stack.agent(token, app, "fake-host:8080", Duration.ZERO)) {
      assertThat(first.connect().accepted()).isTrue();
      old = first.instanceId();
      first.sendBye(io.argos.proto.v1.ByeReason.SHUTDOWN);
      first.cancelSession();
    }

    try (FakeAgent restarted = stack.agent(token, app, "fake-host:4242", Duration.ZERO)) {
      HelloResult hello = restarted.connect();
      assertThat(hello.accepted()).isTrue();
      int temporary = restarted.instanceId();
      assertThat(temporary).isNotEqualTo(old);
      stack.flush();
      int temporaryEvents = stack.events(temporary).size();
      double mergedBefore = merged();

      restarted.sendInstanceUpdate(
          InstanceUpdate.newBuilder().setInstanceName("fake-host:8080").build());
      restarted.awaitSessionEnd(Duration.ofSeconds(10));
      SessionClose close = restarted.sessionClose().orElseThrow();
      assertThat(close.getReason()).isEqualTo(CloseReason.CLOSE_REASON_UNSPECIFIED);
      assertThat(close.getRetryAfterS()).isZero();
      assertThat(merged()).isEqualTo(mergedBefore + 1);

      // The agent reconnects with its current name in the Hello (what the real agent does).
      HelloResult again =
          restarted.connect(
              restarted.helloTemplate().toBuilder().setInstanceName("fake-host:8080").build());
      assertThat(again.accepted()).as(again.toString()).isTrue();
      assertThat(restarted.instanceId()).isEqualTo(old);
      assertThat(stack.state(old)).isEqualTo(io.argos.server.session.api.InstanceState.UP);

      stack.flush();
      assertThat(archivedAt(temporary)).isEqualTo(CLOCK.instant());
      assertThat(stack.stored(temporary).status()).isEqualTo("DOWN");
      assertThat(stack.events(temporary))
          .as("no state event for the temporary instance: it never went down")
          .hasSize(temporaryEvents);
      // The first agent's Bye writes a stop row at the same (fixed) clock instant as its UP, so the
      // rows tie on ts: judge the state rows only. The reconnect inside the Bye grace is UP -> UP
      // (no new row); the instance never went STALE or DOWN.
      assertThat(stack.events(old))
          .filteredOn(e -> e.type().equals("state"))
          .extracting(e -> e.to())
          .isNotEmpty()
          .endsWith("UP")
          .doesNotContain("STALE", "DOWN");
      assertThat(listed(token.appId(), old)).isTrue();
      assertThat(listed(token.appId(), temporary)).isFalse();
    }
  }

  @Test
  @DisplayName(
      "C-158 (2) the old instance still has a live session: the rename keeps its conflict (no"
          + " close, the temporary instance stays)")
  void liveOldInstanceKeepsConflict() throws Exception {
    String app = "naming-live";
    AgentTokens.IssuedToken token = stack.token(app);
    try (FakeAgent first = stack.agent(token, app, "fake-host:8080", Duration.ZERO);
        FakeAgent second = stack.agent(token, app, "fake-host:4242", Duration.ZERO)) {
      assertThat(first.connect().accepted()).isTrue();
      assertThat(second.connect().accepted()).isTrue();
      double mergedBefore = merged();
      second.sendInstanceUpdate(
          InstanceUpdate.newBuilder().setInstanceName("fake-host:8080").build());
      // A heartbeat round after the update proves the stream is still open and the update handled.
      assertThat(second.heartbeatRound(Duration.ofSeconds(5))).isPresent();
      assertThat(second.sessionOpen()).isTrue();
      assertThat(first.sessionOpen()).isTrue();
      assertThat(merged()).isEqualTo(mergedBefore);
      stack.flush();
      assertThat(archivedAt(second.instanceId())).isNull();
    }
  }

  @Test
  @DisplayName(
      "12 §4.3 retention on PostgreSQL: silent exactly 7 days is archived, 7 days minus 1 s is"
          + " not; archived and silent exactly 30 days is deleted (its events stay), 30 days minus"
          + " 1 s is not; a live instance is never archived")
  void retentionStatements() throws Exception {
    String app = "naming-retention";
    AgentTokens.IssuedToken token = stack.token(app);
    Instant now = CLOCK.instant();
    int archiveEdge = insertInstance(token.appId(), "edge-7d", now.minus(Duration.ofDays(7)), null);
    int archiveInside =
        insertInstance(
            token.appId(), "inside-7d", now.minus(Duration.ofDays(7)).plusSeconds(1), null);
    int purgeEdge =
        insertInstance(
            token.appId(),
            "edge-30d",
            now.minus(Duration.ofDays(30)),
            now.minus(Duration.ofDays(23)));
    int purgeInside =
        insertInstance(
            token.appId(),
            "inside-30d",
            now.minus(Duration.ofDays(30)).plusSeconds(1),
            now.minus(Duration.ofDays(23)));
    UUID event = UUID.randomUUID();
    try (Connection c = stack.env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO argos.events (id, app_id, env, instance_id, type, title, ts)"
                    + " VALUES (?, ?, 'prod', ?, 'stop', 'Agent shutdown', ?)")) {
      ps.setObject(1, event);
      ps.setInt(2, token.appId());
      ps.setInt(3, purgeEdge);
      ps.setTimestamp(4, Timestamp.from(now.minus(Duration.ofDays(30))));
      ps.executeUpdate();
    }
    try (FakeAgent live = stack.agent(token, app, "live-host:8080", Duration.ZERO)) {
      assertThat(live.connect().accepted()).isTrue();
      int liveId = live.instanceId();
      stack.flush();
      setLastSeen(liveId, now.minus(Duration.ofDays(40)));

      InstanceRetentionJob.Result result = stack.server.bean(InstanceRetentionJob.class).run();
      assertThat(result.archived()).isGreaterThanOrEqualTo(1);
      assertThat(result.purged()).isGreaterThanOrEqualTo(1);

      assertThat(archivedAt(archiveEdge)).isEqualTo(now);
      assertThat(stack.stored(archiveEdge).status()).isEqualTo("DOWN");
      assertThat(archivedAt(archiveInside)).isNull();
      assertThat(exists(purgeEdge)).isFalse();
      assertThat(exists(purgeInside)).isTrue();
      assertThat(archivedAt(liveId)).as("a live instance is never archived").isNull();
      try (Connection c = stack.env.connect(PgAccount.APP);
          PreparedStatement ps = c.prepareStatement("SELECT 1 FROM argos.events WHERE id = ?")) {
        ps.setObject(1, event);
        try (ResultSet rs = ps.executeQuery()) {
          assertThat(rs.next()).as("the deleted instance's events stay").isTrue();
        }
      }
    }
  }

  private static int insertInstance(int appId, String name, Instant lastSeen, Instant archivedAt)
      throws SQLException {
    try (Connection c = stack.env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "INSERT INTO argos.instances (app_id, name, status, first_seen_at, last_seen_at,"
                    + " archived_at) VALUES (?, ?, 'DOWN', ?, ?, ?) RETURNING id")) {
      ps.setInt(1, appId);
      ps.setString(2, name);
      ps.setTimestamp(3, Timestamp.from(lastSeen.minus(Duration.ofDays(1))));
      ps.setTimestamp(4, Timestamp.from(lastSeen));
      ps.setTimestamp(5, archivedAt == null ? null : Timestamp.from(archivedAt));
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private static void setLastSeen(int instanceId, Instant lastSeen) throws SQLException {
    try (Connection c = stack.env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement("UPDATE argos.instances SET last_seen_at = ? WHERE id = ?")) {
      ps.setTimestamp(1, Timestamp.from(lastSeen));
      ps.setInt(2, instanceId);
      ps.executeUpdate();
    }
  }
}
