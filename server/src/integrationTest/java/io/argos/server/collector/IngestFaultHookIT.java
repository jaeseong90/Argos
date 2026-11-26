/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.collector.testing.IngestFaultInjector;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.collector.CollectorHarness;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentClock;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.CallResult;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.argos.server.testing.fakeagent.Records;
import io.argos.storage.clickhouse.Account;
import io.grpc.Status;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-CH-004a (proposed): the collector's failure injection {@code argos.testing.ingest-fault-file}
 * (18 §19, WO-52) on the whole server with real PostgreSQL and ClickHouse, driven by a protocol
 * fake agent: each mode answers as the chaos scenario T-CH-004 needs ({@code UNAVAILABLE}, {@code
 * INTERNAL}, {@code RESOURCE_EXHAUSTED} with {@code argos-retry-after-ms}, {@code QUOTA} through
 * the real quota path: {@code ACK_PARTIAL_DROP}, {@code
 * argos_collector_dropped_total{reason=quota}}, the kept records stored), a refused batch writes
 * nothing, removing the file turns the fault off, and a release build ignores the key (18 §15).
 */
@Tag("integration")
@Tag("req:DSN-18-15")
@Tag("req:N-05")
@Tag("req:D-06")
class IngestFaultHookIT {

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static Path faultFile;

  @BeforeAll
  static void start() throws Exception {
    env = ServerTestEnvironment.start();
    faultFile = Files.createTempDirectory("ingest-fault").resolve("fault");
    try {
      server =
          ArgosTestServer.start(
              env, Map.of("argos.testing.ingest-fault-file", faultFile.toString()));
    } catch (RuntimeException | Error e) {
      env.close();
      throw e;
    }
  }

  @AfterAll
  static void stop() throws Exception {
    try {
      if (server != null) {
        server.close();
      }
    } finally {
      if (env != null) {
        env.close();
      }
      Files.deleteIfExists(faultFile);
    }
  }

  /** Writes the fault and waits past the once-per-second read. */
  private static void fault(String content) throws Exception {
    if (content == null) {
      Files.deleteIfExists(faultFile);
    } else {
      Files.writeString(faultFile, content, StandardCharsets.UTF_8);
    }
    TimeUnit.MILLISECONDS.sleep(1_200);
  }

  private static TransactionBatch batch(FakeAgent agent, int records) {
    List<TransactionRecord> list = new ArrayList<>(records);
    long now = System.currentTimeMillis();
    for (int i = 0; i < records; i++) {
      list.add(agent.records().http("GET /api/fault", now - 1_000 + i, 2_000).build());
    }
    // The texts the records refer to go on the session stream first (11 §7).
    agent.sendNewDictionaryEntries();
    return agent.batches().transactions(list);
  }

  private static double quotaDrops() {
    Counter c =
        server
            .bean(MeterRegistry.class)
            .find("argos.collector.dropped")
            .tag("reason", "quota")
            .counter();
    return c == null ? 0 : c.count();
  }

  private static long scalar(String sql) {
    String value = env.clickHouse(Account.READER).scalar(sql).strip();
    return value.isEmpty() ? 0 : Long.parseLong(value);
  }

  @Test
  @DisplayName(
      "T-CH-004a ingest fault hook: UNAVAILABLE, INTERNAL, RESOURCE_EXHAUSTED (retry-after) refuse"
          + " without writing, QUOTA goes the real quota way, no file means no fault")
  void modes() throws Exception {
    String app = "it-ingest-fault";
    AgentTokens.IssuedToken token;
    try (Connection c = env.connect(PgAccount.APP)) {
      token = AgentTokens.issue(c, AgentTokens.ensureApp(c, app, "prod"));
    }
    try (FakeAgent agent =
        FakeAgent.builder(
                AgentChannels.tls(
                    "localhost", server.collectorPort(), env.collectorCaCertificate()))
            .ownsChannel(true)
            .token(token.token())
            .appName(app)
            .instanceName(app + "-1:8080")
            .records(new Records(AgentClock.system(), 52, 52))
            .callDeadline(Duration.ofSeconds(30))
            .build()) {
      HelloResult hello = agent.connect();
      assertThat(hello.accepted()).as(hello.toString()).isTrue();
      fault(null);
      CallResult ok = agent.sendTransactions(batch(agent, 10));
      assertThat(ok.ackStatus()).as(ok.toString()).isEqualTo(AckStatus.ACK_OK);

      fault("UNAVAILABLE");
      CallResult unavailable = agent.sendTransactions(batch(agent, 10));
      assertThat(unavailable.code()).as(unavailable.toString()).isEqualTo(Status.Code.UNAVAILABLE);
      assertThat(unavailable.retryAfterMs()).isEmpty();

      fault("internal:1");
      CallResult internal = agent.sendTransactions(batch(agent, 10));
      assertThat(internal.code()).as(internal.toString()).isEqualTo(Status.Code.INTERNAL);

      fault("RESOURCE_EXHAUSTED");
      CallResult exhausted = agent.sendTransactions(batch(agent, 10));
      assertThat(exhausted.code())
          .as(exhausted.toString())
          .isEqualTo(Status.Code.RESOURCE_EXHAUSTED);
      assertThat(exhausted.retryAfterMs()).hasValue(IngestFaultInjector.RETRY_AFTER_MS);
      assertThat(exhausted.rejectReason())
          .as("no reject reason: the agent retries after the time instead of splitting")
          .isNullOrEmpty();

      double quotaBefore = quotaDrops();
      fault("QUOTA:0.5");
      CallResult quota = agent.sendTransactions(batch(agent, 10));
      assertThat(quota.ackStatus()).as(quota.toString()).isEqualTo(AckStatus.ACK_PARTIAL_DROP);
      assertThat(quota.ack().getDroppedItems()).isEqualTo(5);
      assertThat(quotaDrops() - quotaBefore)
          .as("argos_collector_dropped_total{reason=quota}")
          .isEqualTo(5.0);

      fault(null);
      CallResult again = agent.sendTransactions(batch(agent, 10));
      assertThat(again.ackStatus()).as(again.toString()).isEqualTo(AckStatus.ACK_OK);
      assertThat(again.ack().getDroppedItems()).isZero();

      // Stored: 10 + 5 (the first half of the QUOTA batch) + 10; the refused batches wrote nothing.
      String count = "SELECT count() FROM argos.xlog WHERE app_id = " + token.appId();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
      long rows = scalar(count);
      while (rows < 25 && System.nanoTime() < deadline) {
        TimeUnit.MILLISECONDS.sleep(500);
        rows = scalar(count);
      }
      TimeUnit.SECONDS.sleep(3);
      assertThat(scalar(count)).as("xlog rows of the application").isEqualTo(25);
      assertThat(scalar("SELECT uniqExact(tx_id) FROM argos.xlog WHERE app_id = " + token.appId()))
          .isEqualTo(25);
    } finally {
      Files.deleteIfExists(faultFile);
    }
  }

  @Test
  @DisplayName(
      "T-CH-004a ingest fault hook: a release build ignores argos.testing.ingest-fault-file")
  void releaseBuildIgnoresTheHook() throws Exception {
    Path file = Files.createTempDirectory("ingest-fault-release").resolve("fault");
    Files.writeString(file, "UNAVAILABLE", StandardCharsets.UTF_8);
    try (CollectorHarness release =
        CollectorHarness.builder(env)
            .serverVersion("1.0.0")
            .property("argos.testing.ingest-fault-file", file.toString())
            .start()) {
      String app = "it-ingest-fault-release";
      String token = release.token(app, "prod").token();
      try (FakeAgent agent =
          release
              .agent(token)
              .appName(app)
              .instanceName(app + "-1")
              .agentVersion("1.0.0")
              .build()) {
        HelloResult hello = agent.connect();
        assertThat(hello.accepted()).as(hello.toString()).isTrue();
        TimeUnit.MILLISECONDS.sleep(1_200);
        CallResult r = agent.sendTransactions(batch(agent, 5));
        assertThat(r.ackStatus()).as(r.toString()).isEqualTo(AckStatus.ACK_OK);
        assertThat(r.ack().getDroppedItems()).isZero();
      }
    } finally {
      Files.deleteIfExists(file);
    }
  }
}
