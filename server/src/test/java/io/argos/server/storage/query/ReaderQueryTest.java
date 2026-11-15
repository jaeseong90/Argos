/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The template rules of the query layer (SEC-76, ADR-0007 C-64, 13 §13.2 step 3) and the rendering
 * of identifiers and segmented queries.
 */
@Tag("req:SEC-76")
@Tag("req:DSN-14-06")
class ReaderQueryTest {

  private static ReaderQuery parse(String sql) {
    return ReaderQuery.parse("t", sql);
  }

  private static void refused(String sql, String rule) {
    assertThatThrownBy(() -> parse(sql))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(rule);
  }

  @Test
  @DisplayName(
      "T-ST-053a a template: comments dropped, parameters with types, rollups, @tables directive")
  void parses() {
    ReaderQuery q =
        parse(
            """
            -- A template header.
            -- @tables xlog, app_stat_1m
            SELECT count() AS c
            FROM {db}.{p}{rollup:app_stat_1m}
            WHERE app_id = {app:UInt32} AND minute >= {from:DateTime('UTC')}
              AND minute < {to:DateTime('UTC')} AND app_id = {app:UInt32};
            """);
    assertThat(q.sql()).doesNotContain("--").doesNotEndWith(";").startsWith("SELECT count()");
    assertThat(q.parameters())
        .containsExactly(
            Map.entry("app", "UInt32"),
            Map.entry("from", "DateTime('UTC')"),
            Map.entry("to", "DateTime('UTC')"));
    assertThat(q.rollups()).containsExactly("app_stat_1m");
    assertThat(q.tables()).containsExactly("xlog", "app_stat_1m");
    assertThat(q.segmentsPlaceholder()).isFalse();
  }

  @Test
  @DisplayName(
      "T-ST-053a a template carries no SETTINGS, FORMAT, OUTFILE, second statement or top-level"
          + " UNION")
  void forbiddenClauses() {
    refused("SELECT 1 SETTINGS max_execution_time = 100", "SETTINGS");
    refused("SELECT 1 FORMAT TSV", "FORMAT");
    refused("SELECT 1 INTO OUTFILE 'x'", "OUTFILE");
    refused("SELECT 1; SELECT 2", "more than one statement");
    refused("INSERT INTO {db}.{p}xlog SELECT 1", "one SELECT");
    refused("SELECT 1 UNION ALL SELECT 2", "UNION");
    assertThat(parse("SELECT * FROM (SELECT 1 UNION ALL SELECT 2)").sql()).contains("UNION");
    assertThat(parse("SELECT 'union' AS w").sql()).contains("'union'");
    assertThat(parse("SELECT formatDateTime(now(), '%F') AS d").sql()).contains("formatDateTime");
    assertThat(parse("SELECT Settings['max_execution_time'] AS t FROM system.query_log").sql())
        .contains("Settings[");
    assertThat(parse("SELECT format FROM {db}.{p}xlog").sql()).contains("format FROM");
    refused("SELECT 1 FORMAT JSON SETTINGS max_threads = 1", "SETTINGS");
    refused("SELECT 1 FORMAT JSON SETTINGS", "FORMAT");
  }

  @Test
  @DisplayName(
      "T-ST-053a identifiers come only from {db}, {p} and {rollup:...}; values only as parameters")
  void placeholders() {
    refused("SELECT {app} FROM {db}.{p}xlog", "unknown placeholder");
    refused("SELECT 1 FROM {db}.xlog", "{db}.{p}");
    refused("SELECT 1 FROM {db}.{p}app_stat_1m", "{rollup:app_stat_1m}");
    refused("SELECT 1 FROM {db}.{p}{rollup:xlog}", "unknown rollup");
    refused("SELECT {from_s0:DateTime} AS x", "reserved");
    refused("SELECT {a:UInt32} + {a:UInt64} AS x", "declared as");
    refused("SELECT 1 FROM ({segments}) UNION ALL ({segments})", "UNION");
  }

  @Test
  @DisplayName("T-ST-053a rendering substitutes the identifiers, rollups through the aliases")
  void renders() {
    ReaderQuery q =
        parse(
            "SELECT count() AS c FROM {db}.{p}{rollup:app_stat_1h}"
                + " WHERE app_id = {app:UInt32} AND hour >= {from:DateTime('UTC')}");
    ReaderQueries queries =
        new ReaderQueries(
            Map.of("t", q), "argos", "pfx_", t -> t.equals("app_stat_1h") ? "app_stat_1h_v2" : t);
    assertThat(queries.render(queries.get("t")))
        .isEqualTo(
            "SELECT count() AS c FROM argos.pfx_app_stat_1h_v2"
                + " WHERE app_id = {app:UInt32} AND hour >= {from:DateTime('UTC')}");
    ReaderQueries bad = new ReaderQueries(Map.of("t", q), "argos", "", t -> "x; DROP");
    assertThatThrownBy(() -> bad.render(q)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ReaderQueries(Map.of(), "argos`", "", t -> t))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName(
      "T-API-003a a segmented query: one fragment per segment in a UNION ALL, bounds renamed and"
          + " bound")
  void segmented() {
    ReaderQuery outer =
        parse(
            "SELECT b, sum(c) AS cnt FROM ({segments}) WHERE x = {app:UInt32} GROUP BY b ORDER BY b");
    ReaderQuery raw =
        parse(
            "SELECT toStartOfInterval(ts, toIntervalSecond({step_seconds:UInt32})) AS b, count() AS c"
                + " FROM {db}.{p}xlog WHERE app_id = {app:UInt32}"
                + " AND ts >= {from:DateTime64(3, 'UTC')} AND ts < {to:DateTime64(3, 'UTC')}"
                + " GROUP BY b");
    ReaderQuery minute =
        parse(
            "SELECT toStartOfInterval(minute, toIntervalSecond({step_seconds:UInt32})) AS b,"
                + " sum(count) AS c FROM {db}.{p}{rollup:app_stat_1m} WHERE app_id = {app:UInt32}"
                + " AND minute >= {from:DateTime('UTC')} AND minute < {to:DateTime('UTC')}"
                + " GROUP BY b");
    EnumMap<TableLevel, ReaderQuery> fragments = new EnumMap<>(TableLevel.class);
    fragments.put(TableLevel.RAW, raw);
    fragments.put(TableLevel.MINUTE, minute);
    SegmentedQuery sq = new SegmentedQuery(outer, fragments);
    ReaderQueries queries = new ReaderQueries(Map.of(), "argos", "", t -> t);
    List<TimeSegment> segments =
        List.of(
            new TimeSegment(
                TableLevel.RAW,
                Instant.parse("2026-11-01T10:00:15Z"),
                Instant.parse("2026-11-01T10:01:00Z")),
            new TimeSegment(
                TableLevel.MINUTE,
                Instant.parse("2026-11-01T10:01:00Z"),
                Instant.parse("2026-11-01T12:00:00Z")),
            new TimeSegment(
                TableLevel.RAW,
                Instant.parse("2026-11-01T12:00:00Z"),
                Instant.parse("2026-11-01T12:00:30.5Z")));
    QueryParams params = QueryParams.create().uint32("app", 3).int64("step_seconds", 60);
    ReaderQueries.Rendered r = queries.render(sq, segments, params);
    assertThat(r.sql())
        .startsWith("SELECT b, sum(c) AS cnt FROM (SELECT * FROM (\nSELECT toStartOfInterval(ts")
        .contains("ts >= {from_s0:DateTime64(3, 'UTC')} AND ts < {to_s0:DateTime64(3, 'UTC')}")
        .contains("minute >= {from_s1:DateTime('UTC')} AND minute < {to_s1:DateTime('UTC')}")
        .contains("ts >= {from_s2:DateTime64(3, 'UTC')}")
        .contains("FROM argos.app_stat_1m")
        .contains("\nUNION ALL\n")
        .doesNotContain("{from:")
        .doesNotContain("{segments}");
    assertThat(r.params().asMap())
        .containsEntry("from_s0", "2026-11-01 10:00:15.000")
        .containsEntry("to_s0", "2026-11-01 10:01:00.000")
        .containsEntry("from_s1", "2026-11-01 10:01:00")
        .containsEntry("to_s1", "2026-11-01 12:00:00")
        .containsEntry("to_s2", "2026-11-01 12:00:30.500")
        .containsEntry("app", "3");
    assertThat(params.has("from_s0")).as("the caller's parameters are not changed").isFalse();
    assertThatThrownBy(
            () ->
                queries.render(
                    sq,
                    List.of(
                        new TimeSegment(
                            TableLevel.HOUR,
                            Instant.parse("2026-11-01T10:00:00Z"),
                            Instant.parse("2026-11-01T11:00:00Z"))),
                    params))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no fragment for level 1h");
    assertThatThrownBy(() -> new SegmentedQuery(raw, fragments))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
