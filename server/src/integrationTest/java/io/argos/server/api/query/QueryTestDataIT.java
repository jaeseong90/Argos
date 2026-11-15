/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.fakeagent.TxIds;
import io.argos.server.testing.querydata.Anchors;
import io.argos.server.testing.querydata.HandMadeRows;
import io.argos.server.testing.querydata.HandMadeRows.StepRow;
import io.argos.server.testing.querydata.HandMadeRows.XlogRow;
import io.argos.server.testing.querydata.LoadQueryTestData;
import io.argos.server.testing.querydata.QueryDataManifest;
import io.argos.server.testing.querydata.QueryDataset;
import io.argos.server.testing.querydata.QueryTestData;
import io.argos.server.testing.querydata.Samples;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseHttp;
import io.argos.storage.clickhouse.MemoryBudget;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The query test data of WO-23 ({@code testing.querydata}): the generator's tiny data in the shared
 * environment matches its manifest in every raw table and the rollups, the PostgreSQL rows describe
 * the same estate, the samples are real rows (the +60 s instances with agent-time tx_ids among
 * them), hand-made rows relative to now land, and the development command loads a running
 * installation with passwords from the environment only and writes its manifest.
 */
@Tag("req:F-S04")
class QueryTestDataIT {

  private static long scalar(ClickHouseHttp ch, String sql) {
    return ch.scalarLong(sql);
  }

  private static long pg(ServerTestEnvironment env, PgAccount account, String sql)
      throws SQLException {
    try (Connection c = env.connect(account);
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  @Test
  @DisplayName(
      "QueryTestData loads the tiny data: raw rows = manifest, rollups = xlog, PostgreSQL estate"
          + " and dictionary seeded, samples are stored rows")
  void sharedTinyData() throws Exception {
    QueryTestData.Shared shared = QueryTestData.shared();
    ServerTestEnvironment env = shared.env();
    QueryDataset data = shared.dataset();
    ClickHouseHttp ch = env.clickHouse(Account.READER);

    assertThat(data.xlogRows()).isGreaterThan(500_000);
    for (Map.Entry<String, Long> e : data.rows().entrySet()) {
      assertThat(scalar(ch, "SELECT count() FROM argos." + e.getKey() + " WHERE app_id <= 10"))
          .as(e.getKey())
          .isEqualTo(e.getValue())
          .isPositive();
    }
    assertThat(scalar(ch, "SELECT sum(count) FROM argos.app_stat_1m WHERE app_id <= 10"))
        .isEqualTo(data.xlogRows());
    assertThat(scalar(ch, "SELECT sum(count) FROM argos.app_stat_1h WHERE app_id <= 10"))
        .isEqualTo(data.xlogRows());
    assertThat(scalar(ch, "SELECT sum(sql_count) FROM argos.xlog WHERE app_id <= 10"))
        .isEqualTo(data.xlogSqlCount());
    assertThat(data.end().toEpochMilli() % 60_000).as("recent placement ends on a minute").isZero();
    assertThat(Duration.between(data.end(), Anchors.now())).isLessThan(Duration.ofHours(1));

    assertThat(data.apps()).hasSize(10);
    assertThat(data.apps().stream().mapToInt(a -> a.instances().size()).sum()).isEqualTo(50);
    assertThat(pg(env, PgAccount.APP, "SELECT count(*) FROM argos.apps WHERE id <= 10"))
        .isEqualTo(10);
    assertThat(pg(env, PgAccount.APP, "SELECT count(*) FROM argos.instances WHERE id <= 50"))
        .isEqualTo(50);
    assertThat(pg(env, PgAccount.APP, "SELECT count(*) FROM argos.dictionary WHERE app_id <= 10"))
        .isGreaterThanOrEqualTo(data.dictionaryEntries())
        .isPositive();
    assertThat(pg(env, PgAccount.APP, "SELECT count(*) FROM argos.error_groups WHERE app_id <= 10"))
        .isPositive();
    assertThat(pg(env, PgAccount.OWNER, "SELECT last_value FROM argos.apps_id_seq"))
        .as("an agent registering afterwards gets a free id")
        .isGreaterThanOrEqualTo(10);
    assertThat(pg(env, PgAccount.OWNER, "SELECT last_value FROM argos.instances_id_seq"))
        .isGreaterThanOrEqualTo(50);

    Samples s = data.samples();
    assertThat(s.transactions()).isNotEmpty();
    assertThat(s.traces()).isNotEmpty();
    assertThat(s.users()).isNotEmpty();
    assertThat(s.requests()).isNotEmpty();
    Samples.Transaction tx = s.transactions().get(0);
    assertThat(
            scalar(
                ch,
                "SELECT count() FROM argos.xlog WHERE app_id = "
                    + tx.appId()
                    + " AND tx_id = "
                    + tx.txIdText()))
        .isEqualTo(1);
    assertThat(data.skewedInstanceIds()).isNotEmpty();
    assertThat(s.skewedInstanceTransactions()).isNotEmpty();
    for (Samples.Transaction skewed : s.skewedInstanceTransactions()) {
      assertThat(data.skewedInstanceIds()).contains(skewed.instanceId());
      long lead = TxIds.startMs(skewed.txId()) - skewed.ts().toEpochMilli();
      assertThat(lead)
          .as("tx_id keeps the agent clock, ts is corrected (C-129 (6)): the C-50 fallback case")
          .isBetween(55_000L, 65_000L);
    }
  }

  @Test
  @DisplayName("hand-made rows relative to now land in xlog, profile_step and the rollups")
  void handMadeRows() throws Exception {
    QueryTestData.Shared shared = QueryTestData.shared();
    ClickHouseHttp ch = shared.env().clickHouse(Account.READER);
    HandMadeRows rows = QueryTestData.rows();
    int app = QueryTestData.FIRST_FREE_APP_ID;
    XlogRow first =
        XlogRow.at(app, 9001, Anchors.minutesAgo(5).plusMillis(250))
            .service(17)
            .elapsedMs(250)
            .userId("a'b\\c\t<script>", "plain")
            .traceIdHex("6ab3c0de0102030405060708090a0b0c")
            .profileCaptured(true);
    XlogRow second =
        XlogRow.at(app, 9001, Anchors.minutesAgo(4)).service(17).error(true).httpStatus(500);
    XlogRow third = XlogRow.at(app, 9002, Anchors.minutesAgo(3)).kind("BATCH");
    rows.xlog(List.of(first, second, third));
    rows.steps(
        List.of(
            StepRow.of(first, 1, "SQL").elapsedMs(40).nameId(-5L),
            StepRow.of(first, 2, "SUMMARY").set("repeat_count", 12).set("status", 1)));
    assertThat(scalar(ch, "SELECT count() FROM argos.xlog WHERE app_id = " + app)).isEqualTo(3);
    assertThat(scalar(ch, "SELECT sum(count) FROM argos.app_stat_1m WHERE app_id = " + app))
        .isEqualTo(3);
    assertThat(
            ch.scalar(
                "SELECT user_id FROM argos.xlog WHERE app_id = "
                    + app
                    + " AND tx_id = "
                    + Long.toUnsignedString(first.txId())))
        .isEqualTo("a'b\\c\t<script>");
    assertThat(
            ch.scalar(
                "SELECT lower(hex(trace_id)) FROM argos.xlog WHERE app_id = "
                    + app
                    + " AND tx_id = "
                    + Long.toUnsignedString(first.txId())))
        .isEqualTo("6ab3c0de0102030405060708090a0b0c");
    assertThat(
            scalar(
                ch,
                "SELECT count() FROM argos.profile_step WHERE app_id = "
                    + app
                    + " AND tx_id = "
                    + Long.toUnsignedString(first.txId())))
        .isEqualTo(2);
    assertThat(
            ch.scalar(
                "SELECT toString(name_id) FROM argos.profile_step WHERE app_id = "
                    + app
                    + " AND seq = 1"))
        .isEqualTo(Long.toUnsignedString(-5L));
    // The same rows again are counted again (a unique deduplication token per insert).
    rows.xlog(List.of(XlogRow.at(app, 9001, Anchors.minutesAgo(2)).txId(first.txId())));
    assertThat(scalar(ch, "SELECT count() FROM argos.xlog WHERE app_id = " + app)).isEqualTo(4);
  }

  @Test
  @DisplayName(
      "loadQueryTestData loads a running installation (passwords from the environment only) and"
          + " writes its manifest")
  void devCommand(@TempDir Path out) throws Exception {
    // A small, short-lived environment of its own (the shared one may be up too: 2 + 2 GiB).
    try (ServerTestEnvironment env =
        ServerTestEnvironment.builder()
            .clickHouseMemoryLimit(3 * MemoryBudget.GIB / 2)
            .postgresMemoryLimit(MemoryBudget.GIB / 2)
            .start()) {
      try (ArgosTestServer server = ArgosTestServer.start(env)) {
        assertThat(server.port()).isPositive(); // the schema of both databases now exists
      }
      List<String> args =
          List.of(
              "--clickhouse=" + env.clickHouseUrl(),
              "--postgres=" + env.postgresJdbcUrl(),
              "--profile=tiny",
              "--placement=around_midnight",
              "--out=" + out.toAbsolutePath());
      Map<String, String> environment =
          Map.of(
              "ARGOS_CH_WRITER_PASSWORD", env.password(Account.WRITER),
              "ARGOS_PG_PASSWORD", env.password(PgAccount.APP));
      assertThatThrownBy(() -> LoadQueryTestData.run(args, Map.of(), System.out))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("ARGOS_CH_WRITER_PASSWORD");
      assertThatThrownBy(
              () ->
                  LoadQueryTestData.run(
                      List.of("--clickhouse=x", "--ch-password=secret"), environment, System.out))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("environment only");

      ByteArrayOutputStream log = new ByteArrayOutputStream();
      QueryDataset data =
          LoadQueryTestData.run(
              args, environment, new PrintStream(log, true, StandardCharsets.UTF_8));
      String output = log.toString(StandardCharsets.UTF_8);
      assertThat(output)
          .doesNotContain(env.password(Account.WRITER))
          .doesNotContain(env.password(PgAccount.APP));

      ClickHouseHttp ch = env.clickHouse(Account.READER);
      assertThat(ch.scalarLong("SELECT count() FROM argos.xlog")).isEqualTo(data.xlogRows());
      assertThat(pg(env, PgAccount.APP, "SELECT count(*) FROM argos.apps")).isEqualTo(10);

      Path manifest = out.resolve(QueryDataManifest.FILE_NAME);
      assertThat(manifest).exists();
      String text = Files.readString(manifest, StandardCharsets.UTF_8);
      assertThat(text)
          .doesNotContain(env.password(Account.WRITER))
          .doesNotContain(env.password(PgAccount.APP));
      JsonNode json = JsonMapper.builder().build().readTree(text);
      assertThat(json.path("profile").asString()).isEqualTo("tiny");
      assertThat(json.path("placement").asString()).isEqualTo("around_midnight");
      assertThat(json.path("range").path("start").asString()).isEqualTo(data.start().toString());
      assertThat(json.path("range").path("end").asString()).isEqualTo(data.end().toString());
      assertThat(json.path("apps").size()).isEqualTo(10);
      assertThat(json.path("rows").path("xlog").asLong()).isEqualTo(data.xlogRows());
      assertThat(json.path("samples").path("transactions").size()).isPositive();
      assertThat(json.path("samples").path("skewed_instance_transactions").size()).isPositive();
      assertThat(json.path("skewed_instance_ids").size()).isPositive();
      try (var runs = Files.list(out)) {
        assertThat(runs.filter(Files::isDirectory).map(d -> d.resolve("samples.json")))
            .as("the generator's own run directory (manifest, samples, dictionary)")
            .anyMatch(Files::exists);
      }
    }
  }
}
