/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.AckStatus;
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
import io.argos.server.testing.fakeagent.CallResult;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.argos.server.testing.fakeagent.Records;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseHttp;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * The whole server in this JVM (every start phase, the gRPC collector, the WAL, the ClickHouse
 * writer and the REST API) on real PostgreSQL and ClickHouse, for the WO-27 acceptance tests that
 * drive it with protocol-level fake agents: applications with agent tokens, connected agents,
 * logged-in users and polling helpers.
 */
final class AgentServerStack implements AutoCloseable {

  final ServerTestEnvironment env;
  final ArgosTestServer server;
  final TestUsers users;

  private AgentServerStack() {
    this.env = ServerTestEnvironment.start();
    ArgosTestServer started = null;
    try {
      started = ArgosTestServer.start(env);
    } finally {
      if (started == null) {
        env.close();
      }
    }
    this.server = started;
    this.users = new TestUsers(env);
  }

  /** Starts PostgreSQL, ClickHouse and the server. */
  static AgentServerStack start() {
    return new AgentServerStack();
  }

  /** A server bean. */
  <T> T bean(Class<T> type) {
    return server.bean(type);
  }

  /** A new user of {@code role}, logged in with a new browser. */
  ApiTestClient login(Role role) {
    TestUsers.TestUser user = users.create(role);
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

  /**
   * A connected fake agent of {@code app} (its token), instance {@code instance}; {@code node}
   * separates the transaction ids of agents that run at the same time.
   */
  FakeAgent connect(AgentTokens.IssuedToken token, String app, String instance, int node) {
    return connect(token, app, instance, node, true);
  }

  /**
   * A connected fake agent; {@code answerDictionaryRequests} false keeps texts that the agent has
   * not sent yet away from the server (a dictionary that arrives late, 11 §7).
   */
  FakeAgent connect(
      AgentTokens.IssuedToken token,
      String app,
      String instance,
      int node,
      boolean answerDictionaryRequests) {
    FakeAgent agent =
        FakeAgent.builder(
                AgentChannels.tls(
                    "localhost", server.collectorPort(), env.collectorCaCertificate()))
            .ownsChannel(true)
            .token(token.token())
            .appName(app)
            .instanceName(instance)
            .records(new Records(AgentClock.system(), node, node))
            .agentEpoch(1_000 + node)
            .answerDictionaryRequests(answerDictionaryRequests)
            .callDeadline(Duration.ofSeconds(30))
            .build();
    HelloResult hello = agent.connect();
    assertThat(hello.accepted()).as(hello.toString()).isTrue();
    return agent;
  }

  /** {@code argos_reader}. */
  ClickHouseHttp reader() {
    return env.clickHouse(Account.READER);
  }

  /** A number read as {@code argos_reader} (empty = 0). */
  long scalar(String sql) {
    String value = reader().scalar(sql).strip();
    return value.isEmpty() || value.equals("\\N") ? 0 : Long.parseLong(value);
  }

  /**
   * Waits until {@code sql} (a number) reads {@code expected}, at most {@code seconds}; returns the
   * last value read.
   */
  long awaitScalar(String sql, long expected, int seconds) throws InterruptedException {
    return await(() -> scalar(sql), expected, seconds);
  }

  /** Waits until {@code value} returns {@code expected}; returns the last value. */
  static long await(LongSupplier value, long expected, int seconds) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    long last = value.getAsLong();
    while (last != expected && System.nanoTime() < deadline) {
      TimeUnit.MILLISECONDS.sleep(250);
      last = value.getAsLong();
    }
    return last;
  }

  /** Asserts an {@code ACK_OK} without dropped items. */
  static void assertAckOk(CallResult result) {
    assertThat(result.ackStatus()).as(result.toString()).isEqualTo(AckStatus.ACK_OK);
    assertThat(result.ack().getDroppedItems()).as(result.toString()).isZero();
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
