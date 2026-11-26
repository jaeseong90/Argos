/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.AgentHello;
import io.argos.server.auth.Role;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.MutableClock;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentClock;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import tools.jackson.databind.JsonNode;

/**
 * The real server (every start phase, real PostgreSQL and ClickHouse of a {@link
 * ServerTestEnvironment}) for the instance state tests of WO-22, optionally on a {@link
 * MutableClock}: fake agents whose clock follows the server's (no clock skew unless asked), a
 * logged-in viewer, and direct reads of {@code instances} and {@code events}.
 */
final class StateStack implements AutoCloseable {

  final ServerTestEnvironment env;
  final MutableClock clock;
  private final TestUsers.TestUser user;
  private ApiTestClient viewer;
  private ArgosTestServer viewerServer;
  private long viewerLoginMs;
  ArgosTestServer server;

  private StateStack(ServerTestEnvironment env, MutableClock clock) {
    this.env = env;
    this.clock = clock;
    this.server = startServer();
    this.user = new TestUsers(env).create(Role.VIEWER);
  }

  /** A stack on a server clock that tests move ({@code clock} non-null) or the system clock. */
  static StateStack start(MutableClock clock) {
    return new StateStack(ServerTestEnvironment.start(), clock);
  }

  private ArgosTestServer startServer() {
    return clock == null
        ? ArgosTestServer.start(env, Map.of())
        : ArgosTestServer.start(env, Map.of(), clock.installer());
  }

  /** Stops the server (graceful: its stop event) and starts a new one on the same databases. */
  void restartServer(Duration down) {
    server.close();
    if (clock != null) {
      clock.advance(down);
    }
    server = startServer();
  }

  /** A token of a new application ({@code name}, prod). */
  AgentTokens.IssuedToken token(String app) throws SQLException {
    try (Connection c = env.connect(PgAccount.APP)) {
      return AgentTokens.issue(c, AgentTokens.ensureApp(c, app, "prod"));
    }
  }

  /** A fake agent of {@code app}/{@code instance} whose clock is the server's plus {@code skew}. */
  FakeAgent agent(AgentTokens.IssuedToken token, String app, String instance, Duration skew) {
    return agent(token, app, instance, skew, UnaryOperator.identity());
  }

  /**
   * A fake agent as {@link #agent(AgentTokens.IssuedToken, String, String, Duration)} whose Hello
   * template {@code hello} changes ({@code check_only}, {@code agent_epoch}, {@code
   * process_start_ms}; WO-29).
   */
  FakeAgent agent(
      AgentTokens.IssuedToken token,
      String app,
      String instance,
      Duration skew,
      UnaryOperator<AgentHello.Builder> hello) {
    AgentClock agentClock =
        clock == null ? AgentClock.skewed(skew) : AgentClock.of(clock::millis, skew.toMillis());
    return FakeAgent.builder(
            AgentChannels.tls("localhost", server.collectorPort(), env.collectorCaCertificate()))
        .ownsChannel(true)
        .token(token.token())
        .appName(app)
        .instanceName(instance)
        .clock(agentClock)
        .callDeadline(Duration.ofSeconds(30))
        .hello(hello)
        .build();
  }

  /** The single {@code count(*)} of {@code sql} with one parameter per {@code ?}. */
  long count(String sql, Object... args) throws SQLException {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps = c.prepareStatement(sql)) {
      for (int i = 0; i < args.length; i++) {
        ps.setObject(i + 1, args[i]);
      }
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        return rs.getLong(1);
      }
    }
  }

  /**
   * A logged-in viewer: logged in again when the server clock moved 10 minutes since the last login
   * (the access token lives 15 minutes of the server clock) or the server was restarted.
   */
  ApiTestClient viewer() {
    long now = clock == null ? System.currentTimeMillis() : clock.millis();
    if (viewer == null || viewerServer != server || now - viewerLoginMs >= 600_000) {
      ApiTestClient c = server.client();
      ApiResponse login = c.login(user.username(), user.password());
      assertThat(login.status()).as("login " + login.body()).isEqualTo(200);
      viewer = c;
      viewerServer = server;
      viewerLoginMs = now;
    }
    return viewer;
  }

  /** {@code GET} as a fresh viewer; the body's {@code data}. */
  JsonNode get(String path) {
    ApiResponse r = viewer().get(path);
    assertThat(r.status()).as(path + " " + r.body()).isEqualTo(200);
    return r.json().path("data");
  }

  /** The {@code state} of an instance in {@code GET /apps/{appId}/instances}. */
  String restState(int appId, int instanceId) {
    for (JsonNode i : get("/api/v1/apps/" + appId + "/instances")) {
      if (i.path("instance_id").asInt() == instanceId) {
        return i.path("state").asString();
      }
    }
    throw new AssertionError("instance " + instanceId + " not listed");
  }

  /** Writes what the state writer holds (on the test thread). */
  void flush() {
    assertThat(server.bean(InstanceStateWriter.class).flush()).as("state writer flush").isTrue();
  }

  /** One evaluation of the monitor at the server clock's now. */
  void tick() {
    server.bean(SessionMonitor.class).tick();
  }

  /** The tracker's state. */
  io.argos.server.session.api.InstanceState state(int instanceId) {
    return server.bean(InstanceStateTracker.class).state(instanceId);
  }

  /** The stored state columns of an instance. */
  Stored stored(int instanceId) throws SQLException {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT status, warn_flags, clock_offset_ms, last_seen_at FROM argos.instances"
                    + " WHERE id = ?")) {
      ps.setInt(1, instanceId);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        Array flags = rs.getArray(2);
        int offset = rs.getInt(3);
        Integer clockOffset = rs.wasNull() ? null : offset;
        java.sql.Timestamp seen = rs.getTimestamp(4);
        return new Stored(
            rs.getString(1),
            flags == null ? List.of() : Arrays.asList((String[]) flags.getArray()),
            clockOffset,
            seen == null ? null : seen.toInstant());
      }
    }
  }

  /** The {@code events} rows of an instance, oldest first. */
  List<Event> events(int instanceId) throws SQLException {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT type, detail->>'from', detail->>'to', detail->>'reason', ts, app_id, env"
                    + " FROM argos.events WHERE instance_id = ? ORDER BY ts, type")) {
      ps.setInt(1, instanceId);
      List<Event> out = new ArrayList<>();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(
              new Event(
                  rs.getString(1),
                  rs.getString(2),
                  rs.getString(3),
                  rs.getString(4),
                  rs.getTimestamp(5).toInstant(),
                  rs.getInt(6),
                  rs.getString(7)));
        }
      }
      return out;
    }
  }

  /** The server events ({@code start}/{@code stop}, no application), oldest first. */
  List<Event> serverEvents() throws SQLException {
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT type, ts FROM argos.events WHERE app_id IS NULL AND instance_id IS NULL"
                    + " ORDER BY ts")) {
      List<Event> out = new ArrayList<>();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(
              new Event(
                  rs.getString(1), null, null, null, rs.getTimestamp(2).toInstant(), 0, null));
        }
      }
      return out;
    }
  }

  @Override
  public void close() {
    try {
      if (server != null) {
        server.close();
      }
    } finally {
      env.close();
    }
  }

  /** Stored state columns. */
  record Stored(String status, List<String> warnFlags, Integer clockOffsetMs, Instant lastSeenAt) {}

  /** An {@code events} row. */
  record Event(
      String type, String from, String to, String reason, Instant ts, int appId, String env) {}
}
