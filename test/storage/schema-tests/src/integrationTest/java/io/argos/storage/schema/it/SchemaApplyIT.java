/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.schema.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ArgosClickHouse;
import io.argos.storage.clickhouse.ClickHouseException;
import io.argos.storage.clickhouse.ClickHouseHttp;
import io.argos.storage.schema.SchemaApplier;
import io.argos.storage.schema.SchemaParameters;
import io.argos.storage.schema.SchemaRenderer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Applies V001 on an empty server (own container: no argos database) and applies it again (13 §13.2
 * migration path "empty DB -> all scripts", T-ST-052). Verifies the DDL of 13 §3/§4 as created by
 * the server: engines, partition and sort keys, TTL, codecs, skip indexes, definer views.
 */
@Tag("integration")
@Tag("req:N-22")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SchemaApplyIT {

  /** table -> engine, partition key, sorting key, TTL expression (as ClickHouse prints them). */
  private static final Map<String, List<String>> TABLES =
      Map.ofEntries(
          Map.entry(
              "xlog",
              List.of(
                  "MergeTree",
                  "toDate(ts)",
                  "app_id, ts, tx_id",
                  "TTL toDateTime(ts) + toIntervalDay(30)")),
          Map.entry(
              "profile_step",
              List.of(
                  "MergeTree",
                  "toDate(ts)",
                  "app_id, tx_id, seq",
                  "TTL toDateTime(ts) + toIntervalDay(7)")),
          Map.entry(
              "metric",
              List.of(
                  "MergeTree",
                  "toDate(ts)",
                  "app_id, instance_id, name, ts",
                  "TTL ts + toIntervalDay(14)")),
          Map.entry(
              "sql_stat_raw",
              List.of(
                  "MergeTree",
                  "toDate(ts)",
                  "app_id, sql_id, db_id, kind, ts",
                  "TTL ts + toIntervalDay(14)")),
          Map.entry(
              "ext_stat_raw",
              List.of(
                  "MergeTree",
                  "toDate(ts)",
                  "app_id, host_id, kind, ts",
                  "TTL ts + toIntervalDay(14)")),
          Map.entry(
              "app_stat_1m",
              List.of(
                  "AggregatingMergeTree",
                  "toDate(minute)",
                  "app_id, kind, minute",
                  "TTL minute + toIntervalDay(90)")),
          Map.entry(
              "app_stat_1h",
              List.of(
                  "AggregatingMergeTree",
                  "toYYYYMM(hour)",
                  "app_id, kind, hour",
                  "TTL hour + toIntervalDay(365)")),
          Map.entry(
              "service_stat_1m",
              List.of(
                  "AggregatingMergeTree",
                  "toDate(minute)",
                  "app_id, kind, service_id, version_id, minute",
                  "TTL minute + toIntervalDay(90)")),
          Map.entry(
              "service_stat_1h",
              List.of(
                  "AggregatingMergeTree",
                  "toYYYYMM(hour)",
                  "app_id, kind, service_id, version_id, hour",
                  "TTL hour + toIntervalDay(365)")),
          Map.entry(
              "instance_stat_1m",
              List.of(
                  "AggregatingMergeTree",
                  "toDate(minute)",
                  "app_id, instance_id, kind, minute",
                  "TTL minute + toIntervalDay(90)")),
          Map.entry(
              "error_stat_1m",
              List.of(
                  "AggregatingMergeTree",
                  "toDate(minute)",
                  "app_id, error_group_id, service_id, version_id, minute",
                  "TTL minute + toIntervalDay(90)")),
          Map.entry(
              "sql_stat_1m",
              List.of(
                  "AggregatingMergeTree",
                  "toDate(minute)",
                  "app_id, kind, sql_id, db_id, minute",
                  "TTL minute + toIntervalDay(90)")),
          Map.entry(
              "sql_stat_1h",
              List.of(
                  "AggregatingMergeTree",
                  "toYYYYMM(hour)",
                  "app_id, kind, sql_id, db_id, hour",
                  "TTL hour + toIntervalDay(365)")),
          Map.entry(
              "ext_stat_1m",
              List.of(
                  "AggregatingMergeTree",
                  "toDate(minute)",
                  "app_id, kind, host_id, minute",
                  "TTL minute + toIntervalDay(90)")),
          Map.entry(
              "ext_stat_1h",
              List.of(
                  "AggregatingMergeTree",
                  "toYYYYMM(hour)",
                  "app_id, kind, host_id, hour",
                  "TTL hour + toIntervalDay(365)")),
          Map.entry(
              "metric_1m",
              List.of(
                  "AggregatingMergeTree",
                  "toDate(minute)",
                  "app_id, instance_id, name, minute",
                  "TTL minute + toIntervalDay(90)")),
          Map.entry(
              "metric_1h",
              List.of(
                  "AggregatingMergeTree",
                  "toYYYYMM(hour)",
                  "app_id, instance_id, name, hour",
                  "TTL hour + toIntervalDay(365)")));

  private static ArgosClickHouse server;
  private static ClickHouseHttp migrator;

  @BeforeAll
  static void startEmptyServer() {
    server = ArgosClickHouse.builder().start();
    migrator = server.client(Account.MIGRATOR);
  }

  @AfterAll
  static void stop() {
    if (server != null) {
      server.close();
    }
  }

  @Test
  @Order(1)
  @DisplayName("T-ST-052 V001 applies on an empty server: database, 17 tables, 12 definer views")
  void appliesOnEmptyServer() {
    assertThat(migrator.scalarLong("SELECT count() FROM system.databases WHERE name = 'argos'"))
        .isZero();

    long started = System.nanoTime();
    List<String> statements = SchemaApplier.apply(migrator, SchemaParameters.defaults());
    long millis = (System.nanoTime() - started) / 1_000_000;
    Evidence.record("schema-apply", "statements applied on an empty server", statements.size());
    Evidence.record("schema-apply", "apply time on an empty server (ms)", millis);
    Evidence.record("schema-apply", "server version", migrator.scalar("SELECT version()"));

    List<List<String>> tables =
        migrator.rows(
            "SELECT name, engine, partition_key, sorting_key, create_table_query FROM system.tables"
                + " WHERE database = 'argos' AND engine != 'MaterializedView' ORDER BY name");
    assertThat(tables)
        .extracting(r -> r.get(0))
        .containsExactlyInAnyOrderElementsOf(TABLES.keySet());
    for (List<String> row : tables) {
      List<String> expected = TABLES.get(row.get(0));
      assertThat(row.subList(1, 4))
          .as(row.get(0))
          .containsExactlyElementsOf(expected.subList(0, 3));
      assertThat(row.get(4))
          .as(row.get(0))
          .contains(expected.get(3))
          .contains("ttl_only_drop_parts = 1")
          .contains("non_replicated_deduplication_window = 1000");
    }

    List<List<String>> views =
        migrator.rows(
            "SELECT name, create_table_query FROM system.tables"
                + " WHERE database = 'argos' AND engine = 'MaterializedView' ORDER BY name");
    assertThat(views).hasSize(12);
    for (List<String> view : views) {
      assertThat(view.get(1))
          .as(view.get(0))
          .contains("DEFINER = argos_migrator SQL SECURITY DEFINER")
          .contains(" TO argos." + view.get(0).replace("_mv", "") + " ");
    }
  }

  @Test
  @Order(2)
  @DisplayName("T-ST-052 codecs, skip indexes and enums are created as specified (13 §3, C-1)")
  void columnDetails() {
    Map<String, String> codecs =
        Map.of(
            "xlog.ts", "CODEC(DoubleDelta, LZ4)",
            "xlog.trace_id", "CODEC(NONE)",
            "xlog.elapsed_ms", "CODEC(T64, LZ4)",
            "xlog.user_id", "CODEC(ZSTD(3))",
            "xlog.alloc_bytes", "CODEC(T64, LZ4)",
            "metric.value", "CODEC(Gorilla(8), LZ4)",
            "metric.ts", "CODEC(DoubleDelta, LZ4)",
            "profile_step.detail", "CODEC(ZSTD(3))");
    codecs.forEach(
        (column, codec) -> {
          String[] parts = column.split("\\.", -1);
          assertThat(
                  migrator.scalar(
                      "SELECT compression_codec FROM system.columns WHERE database = 'argos'"
                          + " AND table = '"
                          + parts[0]
                          + "' AND name = '"
                          + parts[1]
                          + "'"))
              .as(column)
              .isEqualTo(codec);
        });

    String xlog =
        migrator.scalar(
            "SELECT create_table_query FROM system.tables WHERE database = 'argos' AND name = 'xlog'");
    for (String index :
        List.of(
            "INDEX idx_tx tx_id TYPE bloom_filter(0.01) GRANULARITY 4",
            "INDEX idx_trace trace_id TYPE bloom_filter(0.01) GRANULARITY 4",
            "INDEX idx_user user_id TYPE bloom_filter(0.01) GRANULARITY 4",
            "INDEX idx_request request_id TYPE bloom_filter(0.01) GRANULARITY 4",
            "INDEX idx_sql sql_ids TYPE bloom_filter(0.01) GRANULARITY 4")) {
      assertThat(xlog).contains(index);
    }

    assertThat(
            migrator.scalar(
                "SELECT type FROM system.columns WHERE database = 'argos' AND table = 'profile_step'"
                    + " AND name = 'type'"))
        .isEqualTo(
            "Enum8('SQL' = 1, 'DBC' = 2, 'COMMIT' = 3, 'ROLLBACK' = 4, 'HTTP' = 5, 'METHOD' = 6,"
                + " 'ASYNC' = 7, 'ERROR' = 8, 'STACK' = 9, 'MESSAGE' = 10, 'SUMMARY' = 11)");
    assertThat(
            migrator.scalar(
                "SELECT type FROM system.columns WHERE database = 'argos' AND table = 'sql_stat_1m'"
                    + " AND name = 'buckets'"))
        .isEqualTo("AggregateFunction(sumForEach, Array(UInt64))");
  }

  @Test
  @Order(3)
  @DisplayName("T-ST-052 applying V001 twice is a no-op: same objects (UUID, DDL), data kept")
  void secondApplyIsNoOp() {
    String snapshot =
        "SELECT name, uuid, create_table_query, metadata_modification_time FROM system.tables"
            + " WHERE database = 'argos' ORDER BY name";
    List<List<String>> before = migrator.rows(snapshot);
    server
        .client(Account.WRITER)
        .insert(
            Workload.insert(SchemaParameters.defaults(), "xlog", Workload.XLOG_COLUMNS),
            Workload.blocks(
                    Workload.generate(7, Instant.parse("2026-10-24T10:00:00Z"), 2, 50).xlog, 1000)
                .get(0),
            Map.of("insert_deduplication_token", "argos:xlog:apply-twice"));
    long rows = migrator.scalarLong("SELECT count() FROM argos.xlog");
    long rollup = migrator.scalarLong("SELECT sum(count) FROM argos.app_stat_1h");

    List<String> second = SchemaApplier.apply(migrator, SchemaParameters.defaults());

    assertThat(second).hasSize(30);
    assertThat(migrator.rows(snapshot)).isEqualTo(before);
    assertThat(migrator.scalarLong("SELECT count() FROM argos.xlog"))
        .isEqualTo(rows)
        .isEqualTo(100);
    assertThat(migrator.scalarLong("SELECT sum(count) FROM argos.app_stat_1h")).isEqualTo(rollup);
  }

  @Test
  @Order(4)
  @DisplayName(
      "T-ST-052 a non-idempotent script fails safely: lint rejects it, the server refuses it")
  void nonIdempotentScriptFailsSafely() {
    String create =
        SchemaRenderer.fromRepository()
            .renderAll(SchemaParameters.defaults())
            .get(1)
            .replace("CREATE TABLE IF NOT EXISTS", "CREATE TABLE");
    String snapshot =
        "SELECT name, uuid, metadata_modification_time FROM system.tables WHERE database = 'argos'"
            + " ORDER BY name";
    List<List<String>> before = migrator.rows(snapshot);

    assertThatThrownBy(() -> SchemaApplier.apply(migrator, List.of(create)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("CREATE without IF NOT EXISTS");
    ClickHouseException refused = migrator.failure(create).orElseThrow();
    assertThat(refused.errorName()).isEqualTo("TABLE_ALREADY_EXISTS");
    assertThat(migrator.rows(snapshot)).isEqualTo(before);
  }
}
