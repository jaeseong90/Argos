/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.storage;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.TransactionRecord;
import io.argos.server.auth.Role;
import io.argos.server.ops.storage.EmergencyOrder.Candidate;
import io.argos.server.storage.api.Table;
import io.argos.server.storage.clickhouse.ClickHouseBatchWriter;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.AuditLog;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.fakeagent.AgentChannels;
import io.argos.server.testing.fakeagent.AgentClock;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.argos.server.testing.fakeagent.Records;
import io.argos.server.testing.querydata.HandMadeRows;
import io.argos.server.testing.querydata.HandMadeRows.StepRow;
import io.argos.server.testing.querydata.HandMadeRows.XlogRow;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseHttp;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The emergency retention on real ClickHouse and PostgreSQL (T-SV-046b to d; F-S23, 13 §8, 17 §9.2;
 * PM question 1 assumption). The ClickHouse disk usage comes from {@code
 * argos.testing.disk-usage-file} (development build hook); the schedule is an hour, so every step
 * is one explicit {@link StorageHealthJob#runOnce()}:
 *
 * <ul>
 *   <li>at 0.91 each step drops exactly one day partition, in the 13 §8 order ({@code
 *       profile_step}, then {@code metric}/{@code sql_stat_raw}/{@code ext_stat_raw} oldest first,
 *       then {@code xlog}), never today's partition and never a rollup (rollup sums unchanged);
 *       every drop leaves an {@code events} row {@code retention_emergency}, a {@code
 *       RETENTION_EMERGENCY_DROP} audit row and {@code argos_retention_emergency_drops_total};
 *   <li>at 0.84 nothing more is dropped and the API reports the shortening off;
 *   <li>at 0.96 the inserts pause: a fake agent's transactions stay in the WAL and the writer's
 *       queue; at 0.92 they resume and every transaction is in {@code xlog}.
 * </ul>
 *
 * The drop durations are printed as {@code [wo28-emergency]} lines (17 open item 10; shared laptop,
 * reference only).
 */
@Tag("integration")
@Tag("req:F-S23")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EmergencyRetentionIT {

  private static final int APP = 972;
  private static final int TXS = 30;
  private static final DateTimeFormatter ID = DateTimeFormatter.BASIC_ISO_DATE;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** Rollup sums of the seeded application that no drop may change. */
  private static final List<String> ROLLUP_SUMS =
      List.of(
          "SELECT sum(count) FROM argos.app_stat_1m WHERE app_id = " + APP,
          "SELECT sum(count) FROM argos.app_stat_1h WHERE app_id = " + APP,
          "SELECT sum(count) FROM argos.sql_stat_1m WHERE app_id = " + APP,
          "SELECT sum(count) FROM argos.ext_stat_1m WHERE app_id = " + APP);

  @TempDir static Path tmp;

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;
  private static ClickHouseHttp reader;
  private static ApiTestClient admin;
  private static Path ratioFile;
  private static LocalDate today;
  private static List<Long> rollupsBefore;

  @BeforeAll
  static void start() throws Exception {
    env = ServerTestEnvironment.start();
    ratioFile = tmp.resolve("disk-usage");
    Files.writeString(ratioFile, "0.5", StandardCharsets.UTF_8);
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("argos.testing.disk-usage-file", ratioFile.toString());
    p.put("argos.ops.storage-health-interval", "1h");
    server = ArgosTestServer.start(env, p);
    reader = env.clickHouse(Account.READER);
    today = LocalDate.now(ZoneOffset.UTC);
    seed();
    rollupsBefore = rollupSums();
    TestUsers.TestUser a = new TestUsers(env).create(Role.ADMIN);
    admin = server.client();
    assertThat(admin.login(a.username(), a.password()).status()).isEqualTo(200);
  }

  /** Day partitions (days ago) of the raw tables, today's included. */
  private static void seed() {
    HandMadeRows rows = HandMadeRows.of(env);
    List<XlogRow> tx = new ArrayList<>();
    List<StepRow> steps = new ArrayList<>();
    for (int daysAgo : new int[] {3, 2, 1, 0}) {
      XlogRow r = XlogRow.at(APP, 9721, at(daysAgo));
      tx.add(r);
      if (daysAgo <= 2) {
        steps.add(StepRow.of(r, 1, "SQL"));
      }
    }
    rows.xlog(tx);
    rows.steps(steps);
    List<Map<String, Object>> metric = new ArrayList<>();
    for (int daysAgo : new int[] {2, 0}) {
      metric.add(
          Map.of(
              "app_id",
              APP,
              "instance_id",
              9721,
              "name",
              "jvm.heap.used",
              "ts",
              second(daysAgo),
              "value",
              1.0));
    }
    rows.insert("metric", metric);
    List<Map<String, Object>> sql = new ArrayList<>();
    for (int daysAgo : new int[] {3, 0}) {
      sql.add(
          Map.of(
              "app_id", APP, "instance_id", 9721, "ts", second(daysAgo), "sql_id", 11, "count", 4));
    }
    rows.insert("sql_stat_raw", sql);
    List<Map<String, Object>> ext = new ArrayList<>();
    for (int daysAgo : new int[] {1, 0}) {
      ext.add(
          Map.of(
              "app_id",
              APP,
              "instance_id",
              9721,
              "ts",
              second(daysAgo),
              "host_id",
              12,
              "count",
              5));
    }
    rows.insert("ext_stat_raw", ext);
  }

  private static java.time.Instant at(int daysAgo) {
    // Today's row a minute ago at most (never in the future), the others at 08:00.
    return daysAgo == 0
        ? java.time.Instant.now().minusSeconds(60)
        : today.minusDays(daysAgo).atTime(8, 0).toInstant(ZoneOffset.UTC);
  }

  private static String second(int daysAgo) {
    return at(daysAgo).toString().substring(0, 19).replace('T', ' ');
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

  private static void ratio(double value) throws Exception {
    Files.writeString(ratioFile, Double.toString(value), StandardCharsets.UTF_8);
  }

  private static StorageHealthJob job() {
    return server.bean(StorageHealthJob.class);
  }

  private static String id(int daysAgo) {
    return ID.format(today.minusDays(daysAgo));
  }

  private static List<String> partitions(String table) {
    String v =
        reader
            .scalar(
                "SELECT arrayStringConcat(groupArray(partition_id), ',') FROM (SELECT DISTINCT"
                    + " partition_id FROM system.parts WHERE database = 'argos' AND table = '"
                    + table
                    + "' AND active ORDER BY partition_id)")
            .strip();
    return v.isBlank() ? List.of() : List.of(v.split(",", -1));
  }

  private static long scalar(String sql) {
    String v = reader.scalar(sql).strip();
    return v.isEmpty() || v.equals("\\N") ? 0 : Long.parseLong(v);
  }

  private static List<Long> rollupSums() {
    return ROLLUP_SUMS.stream().map(EmergencyRetentionIT::scalar).toList();
  }

  private static String step() {
    StorageHealthJob.Snapshot s = job().runOnce();
    Optional<Candidate> d = s.emergency().dropped();
    return d.map(c -> c.table() + "/" + c.partitionId()).orElse("-");
  }

  private static double counter(String table) {
    var c =
        server
            .bean(MeterRegistry.class)
            .find("argos.retention.emergency.drops")
            .tag("table", table)
            .counter();
    return c == null ? 0 : c.count();
  }

  private static JsonNode storageHealth() {
    ApiResponse r = admin.get("/api/v1/admin/storage/health");
    assertThat(r.status()).as(r.body()).isEqualTo(200);
    return r.json().path("data");
  }

  @Test
  @Order(1)
  @DisplayName(
      "T-SV-046b/c at 0.91 one day partition per step in the 13 §8 order, today and rollups kept,"
          + " events, audit and counters per drop; at 0.84 the shortening stops")
  void dropsInOrderAndStops() throws Exception {
    assertThat(partitions("profile_step")).containsExactly(id(2), id(1), id(0));
    assertThat(step()).as("0.5: nothing").isEqualTo("-");
    assertThat(storageHealth().path("emergency_retention_active").asBoolean(true)).isFalse();

    ratio(0.91);
    List<String> drops = new ArrayList<>();
    drops.add(step());
    assertThat(storageHealth().path("emergency_retention_active").asBoolean(false)).isTrue();
    assertThat(partitions("profile_step")).containsExactly(id(1), id(0));
    for (int i = 0; i < 4; i++) {
      drops.add(step());
    }
    assertThat(drops)
        .containsExactly(
            "profile_step/" + id(2),
            "profile_step/" + id(1),
            "sql_stat_raw/" + id(3),
            "metric/" + id(2),
            "ext_stat_raw/" + id(1));
    assertThat(partitions("profile_step")).containsExactly(id(0));
    assertThat(partitions("sql_stat_raw")).containsExactly(id(0));
    assertThat(partitions("ext_stat_raw")).containsExactly(id(0));
    assertThat(partitions("metric")).contains(id(0)).doesNotContain(id(2));
    assertThat(partitions("xlog")).containsExactly(id(3), id(2), id(1), id(0));

    ratio(0.84);
    assertThat(step()).as("0.84 is below the target 0.85").isEqualTo("-");
    assertThat(step()).isEqualTo("-");
    assertThat(partitions("xlog")).containsExactly(id(3), id(2), id(1), id(0));
    assertThat(storageHealth().path("emergency_retention_active").asBoolean(true)).isFalse();

    assertThat(rollupSums()).as("rollups untouched").isEqualTo(rollupsBefore);
    assertThat(counter("profile_step")).isEqualTo(2);
    assertThat(counter("sql_stat_raw")).isEqualTo(1);
    assertThat(counter("metric")).isEqualTo(1);
    assertThat(counter("ext_stat_raw")).isEqualTo(1);
    assertThat(counter("xlog")).isZero();

    List<String> eventTables = new ArrayList<>();
    try (Connection c = env.connect(PgAccount.APP);
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT app_id, detail::text FROM argos.events WHERE type = 'retention_emergency'"
                    + " ORDER BY ts, id")) {
      while (rs.next()) {
        assertThat(rs.getObject(1)).as("server-wide event").isNull();
        JsonNode detail = JSON.readTree(rs.getString(2));
        eventTables.add(
            detail.path("table").asString() + "/" + detail.path("partition").asString());
        assertThat(detail.path("disk_used_ratio").asDouble()).isEqualTo(0.91);
        System.out.println(
            "[wo28-emergency] dropped "
                + detail.path("table").asString()
                + " "
                + detail.path("partition").asString()
                + " rows="
                + detail.path("rows").asLong()
                + " bytes="
                + detail.path("bytes").asLong()
                + " drop_ms="
                + detail.path("drop_ms").asLong());
      }
    }
    assertThat(eventTables).containsExactlyInAnyOrderElementsOf(drops);
    List<AuditLog.Row> audit = AuditLog.rows(env, "RETENTION_EMERGENCY_DROP");
    assertThat(audit).hasSize(5);
    assertThat(audit).allMatch(r -> "system".equals(r.actorType()) && "success".equals(r.result()));
    assertThat(audit).extracting(AuditLog.Row::targetId).containsExactlyInAnyOrderElementsOf(drops);
  }

  @Test
  @Order(2)
  @DisplayName(
      "T-SV-046d at 0.96 the inserts pause (the agent's transactions stay in the WAL), at 0.92 they"
          + " resume and every transaction is loaded; today's partition is never dropped")
  void pausesAndResumes() throws Exception {
    AgentTokens.IssuedToken token;
    int agentApp;
    try (Connection c = env.connect(PgAccount.APP)) {
      agentApp = AgentTokens.ensureApp(c, "emergency", "prod");
      token = AgentTokens.issue(c, agentApp);
    }
    ClickHouseBatchWriter writer = server.bean(ClickHouseBatchWriter.class);
    ratio(0.96);
    assertThat(step()).isEqualTo("xlog/" + id(3));
    assertThat(writer.insertsPaused()).isTrue();
    assertThat(storageHealth().path("emergency_retention_active").asBoolean(false)).isTrue();
    long coveredBefore = writer.durableThrough(Table.XLOG);
    try (FakeAgent agent =
        FakeAgent.builder(
                AgentChannels.tls(
                    "localhost", server.collectorPort(), env.collectorCaCertificate()))
            .ownsChannel(true)
            .token(token.token())
            .appName("emergency")
            .instanceName("host-emergency:8080")
            .records(new Records(AgentClock.system(), 9, 9))
            .agentEpoch(1_009)
            .callDeadline(Duration.ofSeconds(30))
            .build()) {
      HelloResult hello = agent.connect();
      assertThat(hello.accepted()).as(hello.toString()).isTrue();
      long now = agent.clock().nowMs();
      List<TransactionRecord> txs = new ArrayList<>();
      for (int i = 0; i < TXS; i++) {
        txs.add(agent.records().http("GET /emergency", now - 100 - i, 50_000).build());
      }
      assertThat(agent.sendTransactions(agent.batches().transactions(txs)).ok()).isTrue();
      // Received and in the WAL (and the writer's queue), but not inserted while paused.
      Awaitility.await()
          .atMost(30, TimeUnit.SECONDS)
          .until(() -> writer.queuedRows(Table.XLOG) >= TXS);
      Thread.sleep(3_000); // longer than the flush interval (2 s)
      assertThat(scalar("SELECT count() FROM argos.xlog WHERE app_id = " + agentApp)).isZero();
      assertThat(writer.durableThrough(Table.XLOG))
          .as("nothing committed: the rows are in the WAL only")
          .isEqualTo(coveredBefore);

      ratio(0.92);
      assertThat(step())
          .as("0.92: inserts resume, the shortening goes on")
          .isEqualTo("xlog/" + id(2));
      assertThat(writer.insertsPaused()).isFalse();
      Awaitility.await()
          .atMost(60, TimeUnit.SECONDS)
          .until(() -> scalar("SELECT count() FROM argos.xlog WHERE app_id = " + agentApp) == TXS);
    }
    ratio(0.5);
    assertThat(step()).isEqualTo("-");
    assertThat(partitions("xlog")).containsExactly(id(1), id(0));
    assertThat(partitions("profile_step")).containsExactly(id(0));
    assertThat(rollupSums()).isEqualTo(rollupsBefore);
    assertThat(AuditLog.rows(env, "RETENTION_EMERGENCY_DROP")).hasSize(7);
  }
}
