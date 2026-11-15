/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.query.DictionaryNames;
import io.argos.server.storage.query.QueryExecutor;
import io.argos.server.storage.query.QueryOptions;
import io.argos.server.storage.query.QueryParams;
import io.argos.server.storage.query.QueryResult;
import io.argos.server.storage.query.QueryRow;
import io.argos.server.storage.query.ReaderQueries;
import io.argos.server.storage.query.RollupAliases;
import io.argos.server.storage.query.SegmentedQuery;
import io.argos.server.storage.query.TableLevel;
import io.argos.server.storage.query.TimeSegment;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.querydata.Anchors;
import io.argos.server.testing.querydata.HandMadeRows.XlogRow;
import io.argos.server.testing.querydata.QueryDataset;
import io.argos.server.testing.querydata.QueryTestData;
import io.argos.server.web.RequestIdFilter;
import io.argos.server.web.RequestIds;
import io.argos.storage.clickhouse.Account;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * The query layer against the real ClickHouse and PostgreSQL of the shared query test data
 * (Testcontainers; 13 §6.3, SEC-76, ADR-0007 C-64, T-ST-053): statements run as {@code
 * argos_reader} with the {@code SETTINGS} limits and the request id in the query id (asserted in
 * {@code system.query_log}), values only as query parameters, segmented reads of raw, minute and
 * hour tables equal the raw rows bucket by bucket, failures map to 504/503/500, a rollup alias
 * switch redirects the reads, and dictionary keys resolve to names or {@code <unknown:hash>}.
 */
@Tag("req:SEC-76")
@Tag("req:DSN-14-06")
@Tag("req:D-03")
class QueryExecutorIT {

  private static QueryTestData.Shared shared;
  private static QueryExecutor executor;
  private static ReaderQueries queries;

  @BeforeAll
  static void start() {
    shared = QueryTestData.shared();
    ArgosProperties properties = shared.server().bean(ArgosProperties.class);
    RollupAliases aliases = shared.server().bean(RollupAliases.class);
    queries =
        new ReaderQueries(
            ReaderQueries.load("sqltest", QueryExecutorIT.class.getClassLoader()),
            ServerTestEnvironment.DATABASE,
            "",
            aliases::physical);
    executor = new QueryExecutor(properties.clickhouse(), queries);
  }

  @AfterAll
  static void stop() {
    if (executor != null) {
      executor.destroy();
    }
  }

  private static long count(QueryResult result) {
    return result.single().getLong("c");
  }

  /** The finished statements of a request in system.query_log (flushed every 7.5 s). */
  private static List<QueryRow> queryLog(String requestId, int expected) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
    List<QueryRow> rows = List.of();
    while (System.nanoTime() < deadline) {
      rows =
          executor
              .query(
                  "query_log",
                  QueryParams.create().string("prefix", requestId + "-"),
                  QueryOptions.defaults())
              .rows();
      if (rows.size() >= expected) {
        return rows;
      }
      TimeUnit.MILLISECONDS.sleep(500);
    }
    return rows;
  }

  @Test
  @DisplayName(
      "T-ST-053a every statement runs as argos_reader with the SETTINGS limits in the SQL, the"
          + " request id in its query id and values only as parameters")
  void queryLogTrace() throws Exception {
    String requestId = new RequestIds().next();
    MDC.put(RequestIdFilter.MDC_KEY, requestId);
    try {
      assertThat(
              count(
                  executor.query(
                      "count_xlog_app",
                      QueryParams.create().uint32("app", 777_777),
                      QueryOptions.defaults().withTimeoutSeconds(3))))
          .isZero();
      QueryDataset data = shared.dataset();
      assertThat(
              count(
                  executor.query(
                      "count_xlog",
                      // A margin: late and clamped rows of the generator lie a little outside its
                      // nominal range.
                      QueryParams.create()
                          .dateTime64("from", data.start().minus(Duration.ofHours(1)))
                          .dateTime64("to", data.end().plus(Duration.ofHours(1))),
                      QueryOptions.defaults().withMaxResultRows(10))))
          .isGreaterThanOrEqualTo(data.xlogRows());
    } finally {
      MDC.remove(RequestIdFilter.MDC_KEY);
    }
    List<QueryRow> log = queryLog(requestId, 2);
    assertThat(log).hasSize(2);
    for (QueryRow r : log) {
      assertThat(r.getString("query_id")).startsWith(requestId + "-");
      assertThat(r.getString("user")).isEqualTo("argos_reader");
      assertThat(r.getString("max_rows_to_read")).isNotEmpty();
      assertThat(r.getString("max_memory_usage")).isNotEmpty();
      assertThat(r.getString("query")).contains("SETTINGS max_execution_time = ");
    }
    // The two statements by their limits: the 3 s one is the app count, the other the range.
    QueryRow appQuery =
        log.stream()
            .filter(r -> "3".equals(r.getString("max_execution_time")))
            .findFirst()
            .orElseThrow();
    // ClickHouse logs a bound parameter as a typed cast it substituted itself (the server sent
    // {app:UInt32} and param_app): the value was never part of the SQL text the server built.
    assertThat(appQuery.getString("query"))
        .contains("WHERE app_id = _CAST(777777, 'UInt32')")
        .contains("SETTINGS max_execution_time = 3, max_rows_to_read = ");
    QueryRow rangeQuery =
        log.stream()
            .filter(r -> "10".equals(r.getString("max_execution_time")))
            .findFirst()
            .orElseThrow();
    assertThat(rangeQuery.getString("max_result_rows")).isEqualTo("10");
    assertThat(rangeQuery.getString("query")).contains("argos.xlog").contains("_CAST('");
  }

  @Test
  @DisplayName(
      "T-ST-053a hostile strings, arrays, 64-bit ids, times and identifiers round-trip as"
          + " parameters")
  void parameters() {
    String hostile = "x' OR 1=1 --\t\n\\ 😀 <script>alert(1)</script> {db}";
    List<String> array = List.of("a'b", "c\\d", "e\tf", "", "x\ny", "]', 'z");
    Instant t = Instant.parse("2026-11-08T01:02:03.456Z");
    QueryRow r =
        executor
            .query(
                "echo",
                QueryParams.create()
                    .string("s", hostile)
                    .stringArray("arr", array)
                    .uint64("u", -1L)
                    .dateTime64("t", t)
                    .uint64Array("ids", 1L, -2L)
                    .identifier("sort", "elapsed_ms"),
                QueryOptions.defaults())
            .single();
    assertThat(r.getString("s")).isEqualTo(hostile);
    assertThat(r.getStringList("arr")).isEqualTo(array);
    assertThat(r.getUInt64("u")).isEqualTo("18446744073709551615");
    assertThat(r.getInstant("t")).isEqualTo(t);
    assertThat(r.getLongArray("ids")).containsExactly(1L, -2L);
    assertThat(r.getLong("sort_column_value")).isEqualTo(7L);
  }

  private static Map<Instant, long[]> series(
      List<TimeSegment> segments, long stepSeconds, long... apps) {
    SegmentedQuery q =
        queries.segmented(
            "series.outer",
            Map.of(
                TableLevel.RAW, "series.raw",
                TableLevel.MINUTE, "series.1m",
                TableLevel.HOUR, "series.1h"));
    QueryResult result =
        executor.query(
            q,
            segments,
            QueryParams.create().int64Array("apps", apps).int64("step_seconds", stepSeconds),
            QueryOptions.defaults());
    Map<Instant, long[]> out = new LinkedHashMap<>();
    for (QueryRow r : result) {
      // quantilesDD(0.01, 0.5, 0.95, 0.99): 0.01 is the relative accuracy; p50, p95, p99 come back.
      double[] quantiles = r.getDoubleArray("q");
      assertThat(quantiles).hasSize(3);
      assertThat(quantiles[0]).isLessThanOrEqualTo(quantiles[1]);
      assertThat(quantiles[1]).isLessThanOrEqualTo(quantiles[2]);
      out.put(r.getInstant("b"), new long[] {r.getLong("cnt"), r.getLong("err")});
    }
    return out;
  }

  private static void assertSameBuckets(Map<Instant, long[]> actual, Map<Instant, long[]> raw) {
    assertThat(actual.keySet()).isEqualTo(raw.keySet());
    for (Map.Entry<Instant, long[]> e : raw.entrySet()) {
      assertThat(actual.get(e.getKey())).as("bucket %s", e.getKey()).containsExactly(e.getValue());
    }
  }

  @Test
  @DisplayName(
      "T-API-003a/T-ACC-03a raw edges + minute rollup per minute bucket = the raw rows (generator"
          + " data, unaligned range)")
  void segmentedMinutes() {
    QueryDataset data = shared.dataset();
    Instant a = data.start().plusMillis(37_250);
    Instant b = data.end().minusSeconds(4 * 60 + 13).minusMillis(500);
    Instant aMinute = a.plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
    Instant bMinute = b.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
    long[] apps = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
    Map<Instant, long[]> raw = series(List.of(new TimeSegment(TableLevel.RAW, a, b)), 60, apps);
    Map<Instant, long[]> mixed =
        series(
            List.of(
                new TimeSegment(TableLevel.RAW, a, aMinute),
                new TimeSegment(TableLevel.MINUTE, aMinute, bMinute),
                new TimeSegment(TableLevel.RAW, bMinute, b)),
            60,
            apps);
    assertThat(raw).hasSizeGreaterThan(30);
    assertSameBuckets(mixed, raw);
  }

  @Test
  @DisplayName(
      "T-API-003a/T-ACC-03a raw, minute and hour segments per hour bucket = the raw rows"
          + " (hand-made rows over three hours)")
  void segmentedHours() {
    int app = QueryTestData.FIRST_FREE_APP_ID + 1;
    Instant first = Anchors.hoursAgo(3).plusSeconds(7 * 60 + 13);
    Instant last = Anchors.thisHour().minusSeconds(2 * 60);
    List<XlogRow> rows = new ArrayList<>();
    int i = 0;
    for (Instant t = first; t.isBefore(last); t = t.plusSeconds(9)) {
      rows.add(
          XlogRow.at(app, app * 10 + 1, t.plusMillis(i % 1000))
              .elapsedMs(20 + (i * 37) % 3000)
              .error(i % 17 == 0));
      i++;
    }
    QueryTestData.rows().xlog(rows);
    Instant a = first.plusMillis(500);
    Instant b = last.minusSeconds(40).minusMillis(250);
    Instant aMinute = a.plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
    Instant aHour = a.plus(Duration.ofHours(1)).truncatedTo(java.time.temporal.ChronoUnit.HOURS);
    Instant bHour = b.truncatedTo(java.time.temporal.ChronoUnit.HOURS);
    Instant bMinute = b.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
    assertThat(aHour).isBefore(bHour);
    Map<Instant, long[]> raw = series(List.of(new TimeSegment(TableLevel.RAW, a, b)), 3600, app);
    Map<Instant, long[]> mixed =
        series(
            List.of(
                new TimeSegment(TableLevel.RAW, a, aMinute),
                new TimeSegment(TableLevel.MINUTE, aMinute, aHour),
                new TimeSegment(TableLevel.HOUR, aHour, bHour),
                new TimeSegment(TableLevel.MINUTE, bHour, bMinute),
                new TimeSegment(TableLevel.RAW, bMinute, b)),
            3600,
            app);
    assertThat(raw).hasSize(3);
    assertThat(raw.values().stream().mapToLong(v -> v[0]).sum()).isGreaterThan(1000);
    assertSameBuckets(mixed, raw);
  }

  @Test
  @DisplayName(
      "T-API-004a a statement over the read limit or the time limit answers 504 query_timeout")
  void limits() {
    assertThatThrownBy(
            () ->
                executor.query(
                    "numbers_count",
                    QueryParams.create().uint64("n", 1_000_000),
                    QueryOptions.defaults().withMaxRowsToRead(1_000)))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.QUERY_TIMEOUT));
    long started = System.nanoTime();
    assertThatThrownBy(
            () ->
                executor.query(
                    "numbers_heavy",
                    QueryParams.create().uint64("n", 1_900_000_000L),
                    QueryOptions.defaults().withTimeoutSeconds(1)))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.QUERY_TIMEOUT));
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
  }

  @Test
  @DisplayName(
      "T-API-004a a defect answers 500 internal, a missing parameter never runs, ClickHouse down"
          + " answers 503 storage_unavailable")
  void failures() throws Exception {
    assertThatThrownBy(
            () -> executor.query("broken", QueryParams.create(), QueryOptions.defaults()))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INTERNAL));
    assertThatThrownBy(
            () -> executor.query("count_xlog_app", QueryParams.create(), QueryOptions.defaults()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app");
    int port;
    try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      port = socket.getLocalPort();
    }
    ArgosProperties.ClickHouse real = shared.server().bean(ArgosProperties.class).clickhouse();
    ArgosProperties.ClickHouse down =
        new ArgosProperties.ClickHouse(
            "http://127.0.0.1:" + port,
            real.database(),
            real.tablePrefix(),
            real.timezone(),
            real.writer(),
            real.reader(),
            real.migrator(),
            real.migration());
    QueryExecutor unreachable = new QueryExecutor(down, queries);
    try {
      assertThatThrownBy(
              () ->
                  unreachable.query(
                      "count_xlog_app",
                      QueryParams.create().uint32("app", 1),
                      QueryOptions.defaults()))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.STORAGE_UNAVAILABLE));
    } finally {
      unreachable.destroy();
    }
  }

  @Test
  @DisplayName(
      "13 §13.2 step 3: a rollup_alias switch in PostgreSQL redirects {rollup:...} reads within"
          + " the cache time")
  void rollupAlias() throws Exception {
    ServerTestEnvironment env = shared.env();
    RollupAliases aliases = shared.server().bean(RollupAliases.class);
    long before =
        count(executor.query("rollup_count", QueryParams.create(), QueryOptions.defaults()));
    assertThat(before).isPositive();
    env.clickHouse(Account.MIGRATOR)
        .execute("CREATE TABLE IF NOT EXISTS argos.app_stat_1m_alias_test AS argos.app_stat_1m");
    try (Connection c = env.connect(PgAccount.APP)) {
      try (PreparedStatement ps =
          c.prepareStatement(
              "INSERT INTO argos.settings (key, value, updated_at) VALUES ('rollup_alias',"
                  + " jsonb_build_object('app_stat_1m', 'app_stat_1m_alias_test'), now())"
                  + " ON CONFLICT (key) DO UPDATE SET value = argos.settings.value"
                  + " || EXCLUDED.value")) {
        ps.executeUpdate();
      }
      aliases.invalidate();
      assertThat(aliases.physical("app_stat_1m")).isEqualTo("app_stat_1m_alias_test");
      assertThat(aliases.physical("app_stat_1h")).isEqualTo("app_stat_1h");
      QueryRow switched =
          executor.query("rollup_count", QueryParams.create(), QueryOptions.defaults()).single();
      assertThat(switched.isNull("c") || switched.getLong("c") == 0).isTrue();
    } finally {
      try (Connection c = env.connect(PgAccount.APP);
          Statement st = c.createStatement()) {
        st.executeUpdate(
            "UPDATE argos.settings SET value = value - 'app_stat_1m' WHERE key = 'rollup_alias'");
      }
      aliases.invalidate();
      env.clickHouse(Account.MIGRATOR).execute("DROP TABLE IF EXISTS argos.app_stat_1m_alias_test");
    }
    assertThat(count(executor.query("rollup_count", QueryParams.create(), QueryOptions.defaults())))
        .isEqualTo(before);
  }

  @Test
  @DisplayName("dictionary keys resolve to the seeded names; unknown keys show <unknown:hash>")
  void dictionaryNames() throws Exception {
    DictionaryNames names = shared.server().bean(DictionaryNames.class);
    long serviceId = shared.dataset().samples().transactions().get(0).serviceId();
    int app = shared.dataset().samples().transactions().get(0).appId();
    String expected;
    try (Connection c = shared.env().connect(PgAccount.APP);
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT text FROM argos.dictionary WHERE app_id = ? AND kind = 'service'"
                    + " AND hash64 = ?")) {
      ps.setInt(1, app);
      ps.setLong(2, serviceId);
      try (ResultSet rs = ps.executeQuery()) {
        assertThat(rs.next()).isTrue();
        expected = rs.getString(1);
      }
    }
    Map<Long, String> resolved =
        names.names(app, DictionaryNames.Kind.SERVICE, List.of(serviceId, 0L, 0x1234L));
    assertThat(resolved)
        .containsEntry(serviceId, expected)
        .containsEntry(0x1234L, "<unknown:0000000000001234>")
        .doesNotContainKey(0L);
    assertThat(names.name(app, DictionaryNames.Kind.SERVICE, serviceId)).isEqualTo(expected);
    assertThat(names.name(app, DictionaryNames.Kind.SERVICE, 0L)).isNull();
    assertThat(names.entries(app, DictionaryNames.Kind.SQL, List.of(0x1234L))).isEmpty();
  }
}
