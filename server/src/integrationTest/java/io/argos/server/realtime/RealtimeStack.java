/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.Role;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentClock;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;

/**
 * The real server (real PostgreSQL and ClickHouse, every start phase) on an {@link OffsetClock} for
 * the WebSocket tests of WO-22: logged-in users, applications with agent tokens and fake agents
 * whose clock is the server's.
 */
final class RealtimeStack implements AutoCloseable {

  final ServerTestEnvironment env;
  final OffsetClock clock = new OffsetClock();
  final ArgosTestServer server;
  final TestUsers users;

  private RealtimeStack(Map<String, ?> properties) {
    this.env = ServerTestEnvironment.start();
    this.server = ArgosTestServer.start(env, properties, clock.installer());
    this.users = new TestUsers(env);
  }

  static RealtimeStack start() {
    return new RealtimeStack(Map.of());
  }

  static RealtimeStack start(Map<String, ?> properties) {
    return new RealtimeStack(properties);
  }

  /** A new user of {@code role}, logged in. */
  ApiTestClient login(Role role) {
    return login(users.create(role));
  }

  /** {@code user} logged in with a new browser. */
  ApiTestClient login(TestUsers.TestUser user) {
    ApiTestClient c = server.client();
    ApiResponse r = c.login(user.username(), user.password());
    assertThat(r.status()).as("login " + r.body()).isEqualTo(200);
    return c;
  }

  /** A token of a new application ({@code name}, prod). */
  AgentTokens.IssuedToken app(String name) throws SQLException {
    try (Connection c = env.connect(PgAccount.APP)) {
      return AgentTokens.issue(c, AgentTokens.ensureApp(c, name, "prod"));
    }
  }

  /** A fake agent whose clock is the server's plus {@code skew}. */
  FakeAgent agent(AgentTokens.IssuedToken token, String app, String instance, Duration skew) {
    return FakeAgent.builder(
            AgentChannels.tls("localhost", server.collectorPort(), env.collectorCaCertificate()))
        .ownsChannel(true)
        .token(token.token())
        .appName(app)
        .instanceName(instance)
        .clock(AgentClock.of(clock::millis, skew.toMillis()))
        .callDeadline(Duration.ofSeconds(30))
        .build();
  }

  @Override
  public void close() {
    try {
      server.close();
    } finally {
      env.close();
    }
  }
}
