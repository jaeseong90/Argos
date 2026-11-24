/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.RejectReason;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.AuditLog;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.CallResult;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.grpc.Status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.json.JsonMapper;

/**
 * The agent audit events of ADR-0010 C-158 (1) over the whole server (gRPC, PostgreSQL 16;
 * T-SV-044f): {@code AGENT_SESSION_REJECTED} for a refused Hello of a valid token, {@code
 * AGENT_AUTH_FAILED} rate limited per client address and minute, {@code AGENT_AUTH_BLOCKED} at the
 * failure threshold; the gRPC answers do not wait for the audit store (its table locked).
 */
@Tag("integration")
@Tag("req:SEC-80")
@Tag("req:C-158")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentAuditIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Failures per address and minute before the block (lowered for the test). */
  private static final int FAILURES_PER_MINUTE = 6;

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;

  @BeforeAll
  static void start() {
    env = ServerTestEnvironment.start();
    server =
        ArgosTestServer.start(
            env,
            Map.of(
                "argos.collector.auth-fail.per-ip-per-minute",
                Integer.toString(FAILURES_PER_MINUTE),
                "argos.collector.auth-fail.block-minutes",
                "1"));
  }

  @AfterAll
  static void stop() {
    try {
      if (server != null) {
        server.close();
      }
    } finally {
      if (env != null) {
        env.close();
      }
    }
  }

  private static AgentTokens.IssuedToken token(String app) throws SQLException {
    try (Connection c = env.connect(PgAccount.APP)) {
      return AgentTokens.issue(c, AgentTokens.ensureApp(c, app, "prod"));
    }
  }

  private static FakeAgent agent(String token, String app) {
    return FakeAgent.builder(
            AgentChannels.tls("localhost", server.collectorPort(), env.collectorCaCertificate()))
        .ownsChannel(true)
        .token(token)
        .appName(app)
        .env("prod")
        .instanceName("audit-host:8080")
        .callDeadline(Duration.ofSeconds(30))
        .build();
  }

  private static List<AuditLog.Row> await(String action, int atLeast) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    List<AuditLog.Row> rows = AuditLog.rows(env, action);
    while (rows.size() < atLeast && System.nanoTime() < deadline) {
      Thread.sleep(100);
      rows = AuditLog.rows(env, action);
    }
    return rows;
  }

  @Test
  @Order(1)
  @DisplayName(
      "T-SV-044f a valid token's refused Hello (app_mismatch) is AGENT_SESSION_REJECTED: actor the"
          + " token prefix, target the token's app, one row a minute per prefix, never the token")
  void sessionRejected() throws Exception {
    AgentTokens.IssuedToken t = token("audit-agent");
    for (int i = 0; i < 3; i++) {
      try (FakeAgent a = agent(t.token(), "some-other-app")) {
        HelloResult r = a.connect();
        assertThat(r.accepted()).isFalse();
        assertThat(r.rejectReason()).isEqualTo(RejectReason.APP_MISMATCH);
      }
    }
    await("AGENT_SESSION_REJECTED", 1);
    Thread.sleep(500);
    List<AuditLog.Row> rows =
        AuditLog.rows(env, "AGENT_SESSION_REJECTED").stream()
            .filter(r -> t.prefix().equals(r.actorId()))
            .toList();
    assertThat(rows).as("3 refusals within a minute: one row").hasSize(1);
    AuditLog.Row row = rows.get(0);
    assertThat(row.actorType()).isEqualTo("agent");
    assertThat(row.actorName()).isEqualTo(t.prefix());
    assertThat(row.ip()).isNotBlank();
    assertThat(row.result()).isEqualTo("rejected");
    assertThat(row.reason()).isEqualTo("app_mismatch");
    assertThat(row.targetType()).isEqualTo("app");
    assertThat(row.targetId()).isEqualTo(Integer.toString(t.appId()));
    assertThat(JSON.readTree(row.after()).path("app_name").asString()).isEqualTo("some-other-app");
    assertThat(row.toString()).doesNotContain(t.token());
  }

  @Test
  @Order(2)
  @DisplayName(
      "T-SV-044f with audit_logs locked (the audit store stuck) a valid agent's session and data"
          + " call and a bad token's refusal answer at once; the queued AGENT_AUTH_FAILED is written"
          + " after the lock ends")
  void noWaitForTheAuditStore() throws Exception {
    AgentTokens.IssuedToken t = token("audit-ingest");
    String bogus = AgentTokens.generate();
    try (Connection owner = env.connect(PgAccount.OWNER);
        Statement s = owner.createStatement()) {
      owner.setAutoCommit(false);
      s.execute("LOCK TABLE argos.audit_logs IN ACCESS EXCLUSIVE MODE");
      try (FakeAgent good = agent(t.token(), "audit-ingest");
          FakeAgent bad = agent(bogus, "audit-ingest")) {
        long start = System.nanoTime();
        HelloResult refused = bad.connect();
        long refusedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(refused.accepted()).isFalse();
        assertThat(refused.code()).isEqualTo(Status.Code.UNAUTHENTICATED);
        assertThat(refusedMs)
            .as("bad token answered without waiting for the audit store")
            .isLessThan(3_000);
        start = System.nanoTime();
        assertThat(good.connect().accepted()).isTrue();
        long now = good.clock().nowMs();
        TransactionRecord tx = good.records().http("/audit", now - 50, 50_000).build();
        CallResult call = good.sendTransactions(good.batches().transactions(List.of(tx)));
        long goodMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(call.ok()).as(call.toString()).isTrue();
        assertThat(goodMs)
            .as("session and data call while the audit store is stuck")
            .isLessThan(3_000);
      }
      owner.rollback();
    }
    // Written after the lock ends, or (when the lock outlived the 5 s statement timeout on a slow
    // machine) to the fallback file: either way recorded and never in the gRPC thread's way.
    String prefix = AgentTokens.prefix(bogus);
    Path fallback = env.secretsDirectory().resolve("audit").resolve("audit-fallback.jsonl");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    boolean inPostgres = false;
    boolean inFallback = false;
    while (!inPostgres && !inFallback && System.nanoTime() < deadline) {
      inPostgres =
          AuditLog.rows(env, "AGENT_AUTH_FAILED").stream()
              .anyMatch(
                  r ->
                      prefix.equals(r.actorId())
                          && "failure".equals(r.result())
                          && "token_unknown".equals(r.reason())
                          && "token".equals(r.targetType()));
      inFallback =
          Files.exists(fallback)
              && Files.readString(fallback).contains("\"actor_id\":\"" + prefix + "\"");
      Thread.sleep(100);
    }
    assertThat(inPostgres || inFallback).as("AGENT_AUTH_FAILED of %s recorded", prefix).isTrue();
    assertThat(AuditLog.rows(env, "AGENT_AUTH_FAILED").toString()).doesNotContain(bogus);
    if (Files.exists(fallback)) {
      assertThat(Files.readString(fallback)).doesNotContain(bogus);
    }
  }

  @Test
  @Order(3)
  @DisplayName(
      "T-SV-044f repeated bad tokens from one address: AGENT_AUTH_FAILED stays rate limited (one"
          + " row a minute per address) and AGENT_AUTH_BLOCKED is written at the threshold; the next"
          + " call is refused auth_blocked")
  void failuresAndBlock() throws Exception {
    int attempts = FAILURES_PER_MINUTE + 1;
    for (int i = 0; i < attempts; i++) {
      try (FakeAgent bad = agent(AgentTokens.generate(), "audit-ingest")) {
        bad.connect();
      }
    }
    List<AuditLog.Row> blocked = await("AGENT_AUTH_BLOCKED", 1);
    assertThat(blocked).hasSize(1);
    AuditLog.Row b = blocked.get(0);
    assertThat(b.actorType()).isEqualTo("agent");
    assertThat(b.result()).isEqualTo("rejected");
    assertThat(b.reason()).isEqualTo("auth_blocked");
    assertThat(b.targetType()).isEqualTo("address");
    assertThat(JSON.readTree(b.after()).path("block_minutes").asLong()).isEqualTo(1);
    String address = b.targetId();
    long failedRows =
        AuditLog.rows(env, "AGENT_AUTH_FAILED").stream()
            .filter(r -> address.equals(r.ip()))
            .count();
    assertThat(failedRows)
        .as("%d failures from %s within the test: rate limited", attempts + 1, address)
        .isLessThanOrEqualTo(2L);
    try (FakeAgent bad = agent(AgentTokens.generate(), "audit-ingest")) {
      HelloResult r = bad.connect();
      assertThat(r.accepted()).isFalse();
      assertThat(r.code()).isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
    }
  }
}
