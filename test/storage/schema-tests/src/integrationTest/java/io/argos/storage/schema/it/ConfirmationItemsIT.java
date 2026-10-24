/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.schema.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.storage.clickhouse.ClickHouseException;
import io.argos.storage.clickhouse.ClickHouseHttp;
import io.argos.storage.clickhouse.ClickHouseHttp.Response;
import io.argos.storage.schema.SchemaParameters;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Confirmation items of docs/13 §17 on the bundled ClickHouse version, plus the behaviours of this
 * version that shaped V001 and the configuration. Items 1 (dedup token + views) and 6 (aggregate
 * state compatibility) are in DeduplicationTokenIT and UpgradeIT; item 7 (Replicated conversion) in
 * ReplicatedConversionIT.
 */
@Tag("integration")
class ConfirmationItemsIT {

  private static final String TOPIC = "confirmations";

  private static SchemaParameters p;
  private static ClickHouseHttp migrator;

  @BeforeAll
  static void schema() {
    p = Fixture.schema("conf_");
    migrator = Fixture.migrator();
    Evidence.record(TOPIC, "server version", migrator.scalar("SELECT version()"));
  }

  @Test
  @Tag("req:A-11")
  @DisplayName("13§17 #2 quantilesBFloat16 and quantilesDD support -State/-MergeState; state sizes")
  void quantileStates() {
    String values =
        "SELECT toStartOfMinute(toDateTime('2026-10-24 00:00:00', 'UTC') + number % 60 * 60) AS minute,"
            + " toUInt32(greatest(1, exp(log(80) + sqrt(-2 * log((cityHash64(number) % 1000000 + 1) / 1000001))"
            + " * cos(2 * pi() * (cityHash64(number, 1) % 1000000) / 1000000)))) AS v"
            + " FROM numbers(600000)";
    Map<String, String> functions =
        Map.of(
            "tdigest", "quantilesTDigest(0.5, 0.95, 0.99)",
            "bfloat16", "quantilesBFloat16(0.5, 0.95, 0.99)",
            "dd", "quantilesDD(0.01, 0.5, 0.95, 0.99)");
    String exact =
        migrator.scalar("SELECT quantilesExact(0.5, 0.95, 0.99)(v) FROM (" + values + ")");
    Evidence.record(TOPIC, "#2 exact p50/p95/p99 of 600,000 log-normal values", exact);
    functions.forEach(
        (name, function) -> {
          String minute = p.table("q_" + name + "_1m");
          String hour = p.table("q_" + name + "_1h");
          migrator.execute(
              "CREATE TABLE IF NOT EXISTS "
                  + minute
                  + " (minute DateTime('UTC'), q AggregateFunction("
                  + function
                  + ", UInt32)) ENGINE = AggregatingMergeTree ORDER BY minute");
          migrator.execute(
              "CREATE TABLE IF NOT EXISTS "
                  + hour
                  + " (hour DateTime('UTC'), q AggregateFunction("
                  + function
                  + ", UInt32)) ENGINE = AggregatingMergeTree ORDER BY hour");
          String state = function.replaceFirst("\\(", "State(");
          String mergeState = function.replaceFirst("\\(", "MergeState(");
          String merge = function.replaceFirst("\\(", "Merge(");
          migrator.execute(
              "INSERT INTO "
                  + minute
                  + " SELECT minute, "
                  + state
                  + "(v) FROM ("
                  + values
                  + ") GROUP BY minute");
          migrator.execute(
              "INSERT INTO "
                  + hour
                  + " SELECT toStartOfHour(minute), "
                  + mergeState
                  + "(q) FROM "
                  + minute
                  + " GROUP BY 1");
          String fromMinutes = migrator.scalar("SELECT " + merge + "(q) FROM " + minute);
          String fromHour = migrator.scalar("SELECT " + merge + "(q) FROM " + hour);
          // RowBinary carries the serialized state as stored (system.columns does not size compact
          // parts).
          long bytes = migrator.queryBytes("SELECT q FROM " + minute + " FORMAT RowBinary").length;
          Evidence.record(
              TOPIC,
              "#2 "
                  + function
                  + ": 60 x 1m states merged / 1h MergeState / uncompressed state bytes per 1m row",
              fromMinutes + " / " + fromHour + " / " + bytes / 60);
          assertThat(fromMinutes).as(name).startsWith("[");
          assertThat(fromHour).as(name).startsWith("[");
        });
  }

  @Test
  @DisplayName("13§17 #3 parts thresholds: 26.8 defaults 1000/3000, the Argos config sets 300/1000")
  void partsThresholds() {
    List<String> values =
        migrator
            .rows(
                "SELECT getMergeTreeSetting('parts_to_delay_insert'),"
                    + " getMergeTreeSetting('parts_to_throw_insert'),"
                    + " getServerSetting('background_pool_size'),"
                    + " getMergeTreeSetting('number_of_free_entries_in_pool_to_execute_mutation')")
            .get(0);
    Evidence.record(
        TOPIC,
        "#3 effective parts_to_delay_insert, parts_to_throw_insert, background_pool_size, mutation threshold",
        values);
    assertThat(values).containsExactly("300", "1000", "4", "5");
  }

  @Test
  @DisplayName(
      "13§17 #4 max_partition_size_to_drop: server 0 applies; no table-level setting in 26.8")
  void partitionDropLimit() {
    assertThat(migrator.scalar("SELECT getServerSetting('max_partition_size_to_drop')"))
        .isEqualTo("0");
    String table = p.table("drop_limit");
    migrator.execute(
        "CREATE TABLE IF NOT EXISTS "
            + table
            + " (d Date, x UInt64) ENGINE = MergeTree"
            + " PARTITION BY d ORDER BY x");
    migrator.execute("INSERT INTO " + table + " SELECT '2026-10-01', number FROM numbers(100000)");
    ClickHouseException tooBig =
        migrator
            .failure(
                "ALTER TABLE " + table + " DROP PARTITION '2026-10-01'",
                Map.of("max_partition_size_to_drop", "1"))
            .orElseThrow();
    assertThat(tooBig.errorName()).isEqualTo("TABLE_SIZE_EXCEEDS_MAX_DROP_SIZE_LIMIT");
    ClickHouseException tableLevel =
        migrator
            .failure("ALTER TABLE " + table + " MODIFY SETTING max_partition_size_to_drop = 1")
            .orElseThrow();
    assertThat(tableLevel.errorName()).isEqualTo("UNKNOWN_SETTING");
    migrator.execute("ALTER TABLE " + table + " DROP PARTITION '2026-10-01'");
    assertThat(migrator.scalarLong("SELECT count() FROM " + table)).isZero();
  }

  @Test
  @Tag("req:N-08")
  @DisplayName("13§17 #5 DateTime64 TTL works with and without toDateTime() (V001 keeps the cast)")
  void dateTime64Ttl() {
    for (String expression : List.of("ts + INTERVAL 1 DAY", "toDateTime(ts) + INTERVAL 1 DAY")) {
      String table = p.table(expression.startsWith("ts") ? "ttl_plain" : "ttl_cast");
      migrator.execute(
          "CREATE TABLE IF NOT EXISTS "
              + table
              + " (ts DateTime64(3, 'UTC'), x UInt8)"
              + " ENGINE = MergeTree PARTITION BY toDate(ts) ORDER BY ts TTL "
              + expression
              + " SETTINGS ttl_only_drop_parts = 1");
      migrator.execute(
          "INSERT INTO " + table + " VALUES (now64(3) - INTERVAL 3 DAY, 1), (now64(3), 2)");
      migrator.execute("ALTER TABLE " + table + " MATERIALIZE TTL", Map.of("mutations_sync", "2"));
      assertThat(migrator.rows("SELECT x FROM " + table))
          .as(expression)
          .containsExactly(List.of("2"));
    }
  }

  @Test
  @Tag("req:A-10")
  @DisplayName(
      "13§17 #8 bloom_filter on Array(UInt64) serves has(): fewer rows read than without it")
  void arrayBloomFilter() {
    String table = p.table("xlog");
    migrator.execute(
        "INSERT INTO "
            + table
            + " (app_id, env, instance_id, ts, tx_id, kind, elapsed_ms, sql_ids)"
            + " SELECT 1, 'prod', 11, addMilliseconds(toDateTime64('2026-10-24 12:00:00', 3, 'UTC'), number),"
            + " number, 'HTTP', 10, if(number BETWEEN 150000 AND 150010, [424242, number], [number % 997,"
            + " number % 991 + 1000]) FROM numbers(300000)");
    String query = "SELECT count() FROM " + table + " WHERE has(sql_ids, 424242)";
    Response withIndex = migrator.execute(query, Map.of("wait_end_of_query", "1"));
    Response withoutIndex =
        migrator.execute(query, Map.of("wait_end_of_query", "1", "use_skip_indexes", "0"));
    String explain = migrator.execute("EXPLAIN indexes = 1 " + query);
    long with = withIndex.summaryValue("read_rows");
    long without = withoutIndex.summaryValue("read_rows");
    Evidence.record(
        TOPIC,
        "#8 has(sql_ids, x) rows read with / without idx_sql (300,000 rows)",
        with + " / " + without);
    assertThat(withIndex.body().strip()).isEqualTo("11");
    assertThat(explain).contains("idx_sql");
    // GRANULARITY 4: one index block covers 4 granules (32,768 rows); the 11 rows sit in one block.
    assertThat(with).isPositive().isLessThan(without / 2);
  }

  @Test
  @Tag("req:N-19")
  @DisplayName("13§17 #9 Gorilla on Float64 is accepted and round-trips values exactly")
  void gorillaFloat64() {
    assertThat(
            migrator.scalar(
                "SELECT compression_codec FROM system.columns WHERE database = 'argos' AND table = '"
                    + p.tablePrefix()
                    + "metric' AND name = 'value'"))
        .isEqualTo("CODEC(Gorilla(8), LZ4)");
    Fixture.writer()
        .insert(
            "INSERT INTO "
                + p.table("metric")
                + " (app_id, env, instance_id, name, kind, ts, value, delta)"
                + " FORMAT TabSeparated",
            """
            1\tprod\t1\tg\tgauge\t2026-10-24 00:00:00\t0.1\t0
            1\tprod\t1\tg\tgauge\t2026-10-24 00:00:05\t-1.7976931348623157E308\t0
            1\tprod\t1\tg\tgauge\t2026-10-24 00:00:10\t123456789.125\t0
            """,
            Map.of("insert_deduplication_token", "argos:metric:gorilla"));
    assertThat(
            migrator.rows(
                "SELECT toString(value) FROM "
                    + p.table("metric")
                    + " WHERE name = 'g' ORDER BY ts"))
        .containsExactly(
            List.of("0.1"), List.of("-1.7976931348623157e308"), List.of("123456789.125"));
  }

  @Test
  @DisplayName(
      "13§17 #10 SimpleAggregateFunction(sumForEach) is unsupported; V001 uses the fallback")
  void sumForEach() {
    ClickHouseException e =
        migrator
            .failure(
                "CREATE TABLE "
                    + p.table("sfe")
                    + " (k UInt8, b SimpleAggregateFunction(sumForEach,"
                    + " Array(UInt64))) ENGINE = AggregatingMergeTree ORDER BY k")
            .orElseThrow();
    assertThat(e.getMessage()).contains("Unsupported aggregate function sumForEach");
    assertThat(
            migrator.scalar(
                "SELECT type FROM system.columns WHERE database = 'argos' AND table = '"
                    + p.tablePrefix()
                    + "ext_stat_1h' AND name = 'buckets'"))
        .isEqualTo("AggregateFunction(sumForEach, Array(UInt64))");
  }

  @Test
  @DisplayName(
      "26.8: plain non-key columns in AggregatingMergeTree need allow_dimensions_outside_sorting_key")
  void aggregatingDimensions() {
    String create =
        "CREATE TABLE "
            + p.table("dims")
            + " (app_id UInt32, env LowCardinality(String),"
            + " c SimpleAggregateFunction(sum, UInt64)) ENGINE = AggregatingMergeTree ORDER BY app_id";
    assertThat(migrator.failure(create).orElseThrow().getMessage())
        .contains("allow_dimensions_outside_sorting_key");
    migrator.execute(create + " SETTINGS allow_dimensions_outside_sorting_key = 1");
    ClickHouseException narrowSum =
        migrator
            .failure(
                "CREATE TABLE "
                    + p.table("sum32")
                    + " (k UInt8, c SimpleAggregateFunction(sum, UInt32))"
                    + " ENGINE = AggregatingMergeTree ORDER BY k")
            .orElseThrow();
    assertThat(narrowSum.getMessage()).contains("returns UInt64 and column storage type UInt32");
  }

  @Test
  @Tag("req:SEC-76")
  @DisplayName("26.8: a view without SQL SECURITY DEFINER makes the INSERT-only writer fail")
  void viewsNeedDefiner() {
    String source = p.table("sec_src");
    migrator.execute(
        "CREATE TABLE IF NOT EXISTS " + source + " (x UInt32) ENGINE = MergeTree ORDER BY x");
    migrator.execute(
        "CREATE TABLE IF NOT EXISTS "
            + p.table("sec_dst")
            + " (x UInt32, c UInt64)"
            + " ENGINE = SummingMergeTree ORDER BY x");
    migrator.execute(
        "CREATE MATERIALIZED VIEW IF NOT EXISTS "
            + p.table("sec_mv")
            + " TO "
            + p.table("sec_dst")
            + " AS SELECT x, count() AS c FROM "
            + source
            + " GROUP BY x");
    ClickHouseException e =
        catchFailure(
            () ->
                Fixture.writer()
                    .insert("INSERT INTO " + source + " FORMAT TabSeparated", "1\n", Map.of()));
    assertThat(e.errorName()).isEqualTo("ACCESS_DENIED");
    assertThat(e.getMessage()).contains("while pushing to view");
  }

  @Test
  @DisplayName(
      "26.8: settings named by 13 §7.3 / §5: unknown token_support setting, writer defaults")
  void settingsOfThisVersion() {
    ClickHouseHttp writer = Fixture.writer();
    assertThat(
            writer
                .failure("SELECT getSetting('insert_deduplication_token_support')")
                .orElseThrow()
                .errorName())
        .isEqualTo("UNKNOWN_SETTING");
    List<String> writerSettings =
        writer
            .rows(
                "SELECT getSetting('async_insert'), getSetting('insert_deduplicate'),"
                    + " getSetting('deduplicate_blocks_in_dependent_materialized_views'),"
                    + " getSetting('max_insert_threads')")
            .get(0);
    assertThat(writerSettings).containsExactly("false", "true", "true", "1");
    assertThat(migrator.scalar("SELECT getSetting('async_insert')")).isEqualTo("true");
    Evidence.record(
        TOPIC,
        "async_insert default of 26.8 (migrator profile) vs writer profile",
        "true vs false");
  }

  @Test
  @Tag("req:SEC-33")
  @DisplayName(
      "12 open item 6: a deletion mutation (migrator) is polled to completion in system.mutations (reader)")
  void deletionMutationPolling() throws InterruptedException {
    String table = p.table("xlog");
    String name = p.tablePrefix() + "xlog";
    migrator.execute(
        "INSERT INTO "
            + table
            + " (app_id, env, instance_id, ts, tx_id, kind, user_id, user_id_mode)"
            + " SELECT 7, 'prod', 71, addMilliseconds(toDateTime64('2026-10-22 10:00:00', 3, 'UTC'),"
            + " number * 10000), 1000000 + number, 'HTTP', if(number % 100 = 0, 'u-del', ''),"
            + " if(number % 100 = 0, 'hashed', 'off') FROM numbers(20000)");
    ClickHouseHttp reader = Fixture.reader();
    String parts =
        "SELECT partition, name FROM system.parts WHERE database = 'argos' AND table = '"
            + name
            + "' AND active AND partition IN ('2026-10-22', '2026-10-23', '2026-10-24') ORDER BY name";
    List<List<String>> before = reader.rows(parts);
    String perDay =
        "SELECT toDate(ts) AS d, countIf(user_id = 'u-del'), count() FROM "
            + table
            + " WHERE app_id = 7 GROUP BY d ORDER BY d";
    List<List<String>> countsBefore = migrator.rows(perDay);

    long started = System.nanoTime();
    // 13 §9: one mutation per day partition, asynchronous (argos_migrator profile mutations_sync =
    // 0).
    migrator.execute(
        "ALTER TABLE "
            + table
            + " DELETE IN PARTITION '2026-10-22'"
            + " WHERE toDate(ts) = '2026-10-22' AND app_id = 7 AND user_id = 'u-del'");
    List<String> status = List.of();
    for (int i = 0; i < 120 && !status.equals(List.of("1", "")); i++) {
      Thread.sleep(250);
      status =
          reader
              .rows(
                  "SELECT is_done, latest_fail_reason FROM system.mutations WHERE database = 'argos'"
                      + " AND table = '"
                      + name
                      + "' ORDER BY create_time DESC LIMIT 1")
              .get(0);
    }
    long millis = (System.nanoTime() - started) / 1_000_000;

    assertThat(status).containsExactly("1", "");
    // Day 2026-10-22 lost exactly its 'u-del' rows; the other days are untouched.
    List<List<String>> countsAfter = migrator.rows(perDay);
    assertThat(countsAfter).hasSameSizeAs(countsBefore).hasSize(3);
    for (int i = 0; i < countsBefore.size(); i++) {
      List<String> b = countsBefore.get(i);
      List<String> a = countsAfter.get(i);
      if (b.get(0).equals("2026-10-22")) {
        assertThat(a.get(1)).isEqualTo("0");
        assertThat(Long.parseLong(a.get(2)))
            .isEqualTo(Long.parseLong(b.get(2)) - Long.parseLong(b.get(1)));
      } else {
        assertThat(a).isEqualTo(b);
      }
    }
    List<List<String>> after = reader.rows(parts);
    Evidence.record(
        TOPIC, "12 #6 deletion mutation polled to is_done=1 (ms, 20,000 rows / 3 days)", millis);
    Evidence.record(
        TOPIC, "12 #6 active parts before / after DELETE IN PARTITION", before + " -> " + after);
    // Every part gets the new mutation version in its name; parts of other days are expected to
    // be hard links of the old ones (no rewrite). Recorded, not asserted: file link count > 1.
    for (List<String> part : after) {
      String path =
          reader.scalar(
              "SELECT path FROM system.parts WHERE database = 'argos' AND table = '"
                  + name
                  + "' AND name = '"
                  + part.get(1)
                  + "'");
      String links =
          Fixture.server()
              .execAsClickHouse("sh", "-c", "stat -c %h " + path + "*.bin | sort -u | tr '\n' ' '")
              .getStdout()
              .strip();
      Evidence.record(TOPIC, "12 #6 hard link count of " + part.get(1) + " files", links);
    }
  }

  private static ClickHouseException catchFailure(Runnable action) {
    try {
      action.run();
    } catch (ClickHouseException e) {
      return e;
    }
    throw new AssertionError("expected a ClickHouse error");
  }
}
