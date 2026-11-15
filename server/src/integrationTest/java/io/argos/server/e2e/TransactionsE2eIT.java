/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.FailureKind;
import io.argos.proto.v1.ProfileStep;
import io.argos.proto.v1.SqlParamMode;
import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.config.IntegrationLogs;
import io.argos.server.storage.api.Column;
import io.argos.server.storage.api.Table;
import io.argos.server.storage.clickhouse.ClickHouseBatchWriter;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentClock;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.CallResult;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.argos.server.testing.fakeagent.Records;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseHttp;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Transactions from a fake agent through the whole server ({@link
 * io.argos.server.ServerApplication} with every start phase, the gRPC collector over TLS, the WAL,
 * the ingest pipeline, the ClickHouse batch writer) into ClickHouse, counted as {@code
 * argos_reader} sees them: duplicates resent by the agent (T-ACC-13 (a)), a broken materialized
 * view (T-ACC-13 (d)) and the receive-to-query delay (T-SV-020, server part).
 */
@Tag("integration")
@Tag("req:D-13")
class TransactionsE2eIT {

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;

  @BeforeAll
  static void start() {
    IntegrationLogs.ensureLogPath();
    env = ServerTestEnvironment.start();
    server = ArgosTestServer.start(env);
  }

  @AfterAll
  static void stop() {
    if (server != null) {
      server.close();
    }
    if (env != null) {
      env.close();
    }
  }

  @Test
  @Tag("req:C-144")
  @DisplayName(
      "the writer's column catalogue (V001 + C-144) equals system.columns of the schema the server"
          + " migrated, name, type and order")
  void writerCatalogueMatchesTheServerSchema() {
    ClickHouseHttp reader = E2e.reader(env);
    for (Table table : Table.values()) {
      List<String> server = new ArrayList<>();
      for (List<String> row :
          reader.rows(
              "SELECT name, type FROM system.columns WHERE database = 'argos' AND table = '"
                  + table.tableName()
                  + "' ORDER BY position")) {
        server.add(row.get(0) + " " + row.get(1));
      }
      List<String> ours = new ArrayList<>();
      for (Column c : table.columns()) {
        ours.add(c.name() + " " + c.type());
      }
      assertThat(ours).as(table.tableName()).containsExactlyElementsOf(server);
    }
  }

  @Test
  @Tag("req:DSN-11-06")
  @DisplayName(
      "dictionary round trip: a transaction whose names were not pre-sent makes the ingest path send"
          + " a DictionaryRequest on the agent's session; the answer lands in argos.dictionary")
  void dictionaryRequestRoundTrip() throws Exception {
    String app = "e2e-dictionary";
    AgentTokens.IssuedToken token = E2e.issue(env, app);
    String service = "GET /api/e2e/dictionary/{id}";
    try (FakeAgent agent = connected(token, app, 14)) {
      TransactionRecord tx =
          agent.records().http(service, agent.clock().nowMs() - 30, 30_000).build();
      CallResult r = agent.sendTransactions(agent.batches().transactions(List.of(tx)));
      assertThat(r.ackStatus()).as(r.toString()).isEqualTo(AckStatus.ACK_OK);
      assertThat(
              agent.awaitServerMessage(
                  m ->
                      m.hasDictionaryRequest()
                          && m.getDictionaryRequest().getRefsList().stream()
                              .anyMatch(ref -> ref.getHash() == tx.getServiceId()),
                  Duration.ofSeconds(30)))
          .as("DictionaryRequest for the service id (SessionMessenger)")
          .isPresent();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
      String text = "";
      while (text.isEmpty() && System.nanoTime() < deadline) {
        try (java.sql.Connection c = env.connect(ServerTestEnvironment.PgAccount.APP);
            java.sql.PreparedStatement st =
                c.prepareStatement(
                    "SELECT text FROM argos.dictionary WHERE app_id = ? AND kind = 'service'"
                        + " AND hash64 = ?")) {
          st.setInt(1, token.appId());
          st.setLong(2, tx.getServiceId());
          try (java.sql.ResultSet rs = st.executeQuery()) {
            text = rs.next() ? rs.getString(1) : "";
          }
        }
        if (text.isEmpty()) {
          TimeUnit.MILLISECONDS.sleep(200);
        }
      }
      assertThat(text).as("argos.dictionary row of the answered entry").isEqualTo(service);
    }
  }

  private static FakeAgent connected(AgentTokens.IssuedToken token, String app, int node) {
    FakeAgent agent =
        E2e.agent(
            env,
            token.token(),
            app,
            app + "-1",
            new Records(AgentClock.system(), node, node),
            node);
    HelloResult hello = agent.connect();
    assertThat(hello.accepted()).as(hello.toString()).isTrue();
    return agent;
  }

  @Test
  @Tag("req:T-ACC-13")
  @Tag("req:C-3")
  @DisplayName(
      "T-ACC-13 (a) the same batch (same seq) resent 100 times over gRPC: ACK_DUPLICATE with"
          + " last_seq each time, xlog and every rollup still = sent")
  void sameSeqResent() throws Exception {
    String app = "e2e-dup";
    AgentTokens.IssuedToken token = E2e.issue(env, app);
    int n = 25;
    try (FakeAgent agent = connected(token, app, 11)) {
      TransactionBatch batch = agent.batches().transactions(E2e.transactions(agent.records(), n));
      CallResult first = agent.sendTransactions(batch);
      assertThat(first.ackStatus()).as(first.toString()).isEqualTo(AckStatus.ACK_OK);
      assertThat(E2e.awaitTotals(env, token.appId(), n, Duration.ofMinutes(1)))
          .isEqualTo(E2e.expected(n));

      for (int i = 0; i < 100; i++) {
        CallResult again = agent.sendTransactions(batch);
        assertThat(again.ackStatus())
            .as("resend %d: %s", i + 1, again)
            .isEqualTo(AckStatus.ACK_DUPLICATE);
        assertThat(again.ack().getLastSeq()).isEqualTo(batch.getHeader().getSeq());
      }
      // Whatever a resend could have queued is flushed now (it must be nothing).
      ClickHouseBatchWriter writer = server.bean(ClickHouseBatchWriter.class);
      TimeUnit.SECONDS.sleep(3);
      writer.flushNow();
      assertThat(writer.awaitIdle(TimeUnit.MINUTES.toMillis(1))).isTrue();
    }
    assertThat(E2e.totals(env, token.appId()))
        .as("rows unchanged after 100 resends")
        .isEqualTo(E2e.expected(n));
  }

  @Test
  @Tag("req:T-ACC-13")
  @Tag("req:C-61")
  @DisplayName(
      "T-ACC-13 (d) one materialized view broken (service_stat_1m refuses rows) while a fake agent"
          + " sends over gRPC: the insert fails and is retried with its token; after the repair"
          + " sent = count(xlog) = every rollup")
  void brokenViewRepaired() throws Exception {
    String app = "e2e-broken-view";
    AgentTokens.IssuedToken token = E2e.issue(env, app);
    int batches = 5;
    int perBatch = 20;
    ClickHouseHttp migrator = env.clickHouse(Account.MIGRATOR);
    MeterRegistry meters = server.bean(MeterRegistry.class);
    double failuresBefore = insertFailures(meters);
    long sent = 0;
    Map<String, Long> broken;
    migrator.execute(
        "ALTER TABLE argos.service_stat_1m ADD CONSTRAINT IF NOT EXISTS e2e_t_acc_13_d CHECK 0");
    try (FakeAgent agent = connected(token, app, 12)) {
      try {
        for (int i = 0; i < batches; i++) {
          CallResult r =
              agent.sendTransactions(
                  agent.batches().transactions(E2e.transactions(agent.records(), perBatch)));
          assertThat(r.ackStatus()).as(r.toString()).isEqualTo(AckStatus.ACK_OK);
          sent += perBatch;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (insertFailures(meters) - failuresBefore < 2 && System.nanoTime() < deadline) {
          TimeUnit.MILLISECONDS.sleep(100);
        }
        assertThat(insertFailures(meters) - failuresBefore)
            .as("xlog inserts failed and retried while the view is broken")
            .isGreaterThanOrEqualTo(2);
        broken = E2e.totals(env, token.appId());
      } finally {
        migrator.execute(
            "ALTER TABLE argos.service_stat_1m DROP CONSTRAINT IF EXISTS e2e_t_acc_13_d");
      }
      // The source block and the healthy views took the rows; the broken view did not (Q15).
      assertThat(broken.get("xlog")).isEqualTo(sent);
      assertThat(broken.get("service_stat_1m")).isZero();
      Map<String, Long> repaired = E2e.awaitTotals(env, token.appId(), sent, Duration.ofMinutes(2));
      assertThat(repaired).as("after the repair and the retry").isEqualTo(E2e.expected(sent));
    }
  }

  private static double insertFailures(MeterRegistry meters) {
    Counter c =
        meters.find("argos.writer.inserts").tag("table", "xlog").tag("result", "error").counter();
    return c == null ? 0 : c.count();
  }

  @Test
  @Tag("req:N-15")
  @DisplayName(
      "T-SV-020b (server part) 200 transactions over gRPC: receive -> visible to argos_reader,"
          + " p99 <= 5 s (the WebSocket half is WO-22)")
  void receiveToQuery() throws Exception {
    String app = "e2e-latency";
    AgentTokens.IssuedToken token = E2e.issue(env, app);
    int n = 200;
    Map<Long, Long> sentAt = new ConcurrentHashMap<>();
    Map<Long, Long> seenAt = new ConcurrentHashMap<>();
    AtomicBoolean polling = new AtomicBoolean(true);
    ClickHouseHttp reader = E2e.reader(env);
    Thread poller =
        new Thread(
            () -> {
              while (true) {
                List<List<String>> rows =
                    reader.rows(
                        "SELECT toString(tx_id) FROM argos.xlog WHERE app_id = " + token.appId());
                // The time after the query: a row is never counted as visible earlier than seen.
                long now = System.nanoTime();
                for (List<String> row : rows) {
                  seenAt.putIfAbsent(Long.parseUnsignedLong(row.get(0)), now);
                }
                if (!polling.get() && seenAt.keySet().containsAll(sentAt.keySet())) {
                  return;
                }
                try {
                  TimeUnit.MILLISECONDS.sleep(100);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
              }
            },
            "e2e-latency-poller");
    poller.setDaemon(true);
    poller.start();
    try (FakeAgent agent = connected(token, app, 13)) {
      for (int i = 0; i < n; i++) {
        TransactionRecord tx = agent.records().transaction().build();
        long t = System.nanoTime();
        sentAt.put(tx.getTxId(), t);
        CallResult r = agent.sendTransactions(agent.batches().transactions(List.of(tx)));
        assertThat(r.ackStatus()).as(r.toString()).isEqualTo(AckStatus.ACK_OK);
        TimeUnit.MILLISECONDS.sleep(100);
      }
    }
    polling.set(false);
    poller.join(TimeUnit.SECONDS.toMillis(60));
    assertThat(seenAt.keySet()).as("every transaction visible").containsAll(sentAt.keySet());
    List<Long> latencies = new ArrayList<>();
    for (Map.Entry<Long, Long> e : sentAt.entrySet()) {
      latencies.add(TimeUnit.NANOSECONDS.toMillis(seenAt.get(e.getKey()) - e.getValue()));
    }
    latencies.sort(null);
    long p50 = latencies.get((int) Math.ceil(0.50 * n) - 1);
    long p99 = latencies.get((int) Math.ceil(0.99 * n) - 1);
    long max = latencies.get(n - 1);
    System.out.printf(
        Locale.ROOT,
        "T-SV-020b (reference, shared laptop): receive -> argos_reader for %d transactions:"
            + " p50 %d ms, p99 %d ms, max %d ms (poll every 100 ms, flush interval 2 s)%n",
        n,
        p50,
        p99,
        max);
    assertThat(p99).as("receive -> query p99 in ms (N-15)").isLessThanOrEqualTo(5_000);
  }

  /** A fake agent of {@code app} with {@code mode} in its Hello, connected. */
  private static FakeAgent connected(
      AgentTokens.IssuedToken token, String app, int node, SqlParamMode mode) {
    FakeAgent agent =
        FakeAgent.builder(
                AgentChannels.tls("localhost", env.collectorPort(), env.collectorCaCertificate()))
            .ownsChannel(true)
            .token(token.token())
            .appName(app)
            .instanceName(app + "-" + mode.name().toLowerCase(Locale.ROOT))
            .records(new Records(AgentClock.system(), node, node))
            .agentEpoch(node)
            .callDeadline(Duration.ofSeconds(30))
            .hello(h -> h.setSqlParamMode(mode))
            .build();
    HelloResult hello = agent.connect();
    assertThat(hello.accepted()).as(hello.toString()).isTrue();
    return agent;
  }

  /**
   * Sends one transaction and its profile: a SQL step whose detail is {@code sqlDetail} and an
   * outgoing HTTP call with a detail of its own. Returns the profile's {@code tx_id}.
   */
  private static long sendProfile(FakeAgent agent, String sqlDetail) {
    Records records = agent.records();
    TransactionRecord tx = records.transaction().build();
    CallResult t = agent.sendTransactions(agent.batches().transactions(List.of(tx)));
    assertThat(t.ackStatus()).as(t.toString()).isEqualTo(AckStatus.ACK_OK);
    ProfileStep sql =
        records
            .sqlStep(1, 0, 100, 2_000, 300, "SELECT * FROM orders WHERE card = ?", "pg://db", 1)
            .toBuilder()
            .setDetail(sqlDetail)
            .build();
    ProfileStep http =
        records.httpCallStep(
            2, 0, 2_500, 1_000, "http://stock:8080", 200, FailureKind.FAILURE_NONE);
    CallResult p =
        agent.sendProfiles(
            agent.batches().profiles(List.of(records.profile(tx, List.of(sql, http)).build())));
    assertThat(p.ackStatus()).as("the ack is unchanged (C-148): " + p).isEqualTo(AckStatus.ACK_OK);
    assertThat(p.ack().getDroppedItems()).isZero();
    return tx.getTxId();
  }

  /** The (type, detail) of each profile_step row of {@code txId}, by seq, once both are visible. */
  private static List<List<String>> steps(int appId, long txId) throws InterruptedException {
    String sql =
        "SELECT toString(type), detail FROM argos.profile_step WHERE app_id = "
            + appId
            + " AND tx_id = "
            + Long.toUnsignedString(txId)
            + " ORDER BY seq";
    ClickHouseHttp reader = E2e.reader(env);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    List<List<String>> rows = reader.rows(sql);
    while (rows.size() < 2 && System.nanoTime() < deadline) {
      TimeUnit.MILLISECONDS.sleep(250);
      rows = reader.rows(sql);
    }
    return rows;
  }

  /** Whether any WAL segment file holds {@code text} (its UTF-8 bytes, as protobuf stores it). */
  private static boolean walContains(String text) throws IOException {
    byte[] needle = text.getBytes(StandardCharsets.UTF_8);
    try (DirectoryStream<Path> files = Files.newDirectoryStream(env.walDirectory(), "seg-*.wal")) {
      for (Path f : files) {
        byte[] bytes = Files.readAllBytes(f);
        for (int i = 0; i + needle.length <= bytes.length; i++) {
          if (Arrays.equals(bytes, i, i + needle.length, needle, 0, needle.length)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  @Test
  @Tag("req:C-148")
  @DisplayName(
      "C-148 a SQL_PARAM_FULL session over the whole server: the bind value of its SQL step is in"
          + " neither the WAL segment bytes nor ClickHouse (profile_step.detail empty, the HTTP"
          + " detail kept), the ack is unchanged and the step counted; a masked session's SQL"
          + " detail is stored (WAL and ClickHouse)")
  void fullModeBindValuesAreNotStored() throws Exception {
    String app = "e2e-full-binds";
    AgentTokens.IssuedToken token = E2e.issue(env, app);
    String full = "ARGOS_SEED_FULL_BIND_" + UUID.randomUUID();
    String masked = "ARGOS_SEED_MASKED_DETAIL_" + UUID.randomUUID();
    MeterRegistry meters = server.bean(MeterRegistry.class);
    double before = redacted(meters);
    long fullTx;
    long maskedTx;
    try (FakeAgent fullAgent = connected(token, app, 15, SqlParamMode.SQL_PARAM_FULL);
        FakeAgent maskedAgent = connected(token, app, 16, SqlParamMode.SQL_PARAM_MASKED)) {
      fullTx = sendProfile(fullAgent, "['" + full + "', 42]");
      maskedTx = sendProfile(maskedAgent, "['" + masked + "']");
      // The acks came after the group fsync: the records are in the segment files now.
      assertThat(walContains(masked))
          .as("positive control: a masked detail is in the WAL bytes")
          .isTrue();
      assertThat(walContains(full)).as("the full-mode bind value never reaches the disk").isFalse();
    }
    assertThat(redacted(meters) - before).as("cleared SQL steps").isEqualTo(1);

    List<List<String>> fullSteps = steps(token.appId(), fullTx);
    assertThat(fullSteps).hasSize(2);
    assertThat(fullSteps.get(0)).containsExactly("SQL", "");
    assertThat(fullSteps.get(1).get(0)).isEqualTo("HTTP");
    assertThat(fullSteps.get(1).get(1)).as("an outgoing call keeps its detail").isNotEmpty();
    List<List<String>> maskedSteps = steps(token.appId(), maskedTx);
    assertThat(maskedSteps).hasSize(2);
    assertThat(maskedSteps.get(0).get(1)).contains(masked);
    assertThat(
            E2e.count(
                env,
                "SELECT count() FROM argos.profile_step WHERE app_id = "
                    + token.appId()
                    + " AND position(detail, '"
                    + full
                    + "') > 0"))
        .isZero();
  }

  private static double redacted(MeterRegistry meters) {
    Counter c =
        meters.find("argos.collector.redacted").tag("reason", "full_detail_unencrypted").counter();
    return c == null ? 0 : c.count();
  }
}
