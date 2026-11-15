/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("req:N-22")
class SchemaRendererTest {

  private static final List<String> RAW_TABLES =
      List.of("xlog", "profile_step", "metric", "sql_stat_raw", "ext_stat_raw");
  private static final List<String> ROLLUP_TABLES =
      List.of(
          "app_stat_1m",
          "app_stat_1h",
          "service_stat_1m",
          "service_stat_1h",
          "instance_stat_1m",
          "error_stat_1m",
          "sql_stat_1m",
          "sql_stat_1h",
          "ext_stat_1m",
          "ext_stat_1h",
          "metric_1m",
          "metric_1h");
  private static final List<String> TRANSACTION_ROLLUPS =
      List.of(
          "app_stat_1m", "app_stat_1h", "service_stat_1m", "service_stat_1h", "instance_stat_1m");

  private final SchemaRenderer renderer = SchemaRenderer.fromRepository();

  @Test
  @DisplayName(
      "V001 renders to 1 database + 17 tables + 12 views, V002 to 2 ADD COLUMN statements (C-144),"
          + " every placeholder resolved")
  void rendersEveryObject() {
    List<String> statements = renderer.renderAll(SchemaParameters.defaults());

    assertThat(renderer.render(renderer.migrations().get(0), SchemaParameters.defaults()))
        .hasSize(30);
    // V002 (ADR-0010 C-144, WO-21) only appends columns: one ALTER TABLE per table it changes.
    assertThat(statements).hasSize(32);
    assertThat(statements.subList(30, 32))
        .allMatch(s -> s.contains("ADD COLUMN IF NOT EXISTS"))
        .anyMatch(s -> s.startsWith("ALTER TABLE argos.xlog ADD COLUMN IF NOT EXISTS url_path "))
        .anyMatch(
            s ->
                s.startsWith("ALTER TABLE argos.profile_step ADD COLUMN IF NOT EXISTS truncated "));
    assertThat(statements.get(0)).isEqualTo("CREATE DATABASE IF NOT EXISTS argos ENGINE = Atomic");
    for (String table : RAW_TABLES) {
      assertThat(statements)
          .anyMatch(s -> s.startsWith("CREATE TABLE IF NOT EXISTS argos." + table + "\n"));
    }
    for (String table : ROLLUP_TABLES) {
      assertThat(statements)
          .anyMatch(s -> s.startsWith("CREATE TABLE IF NOT EXISTS argos." + table + "\n"));
      assertThat(statements)
          .anyMatch(
              s ->
                  s.startsWith(
                      "CREATE MATERIALIZED VIEW IF NOT EXISTS argos."
                          + table
                          + "_mv TO argos."
                          + table
                          + "\nSQL SECURITY DEFINER AS SELECT"));
    }
    for (String statement : statements) {
      assertThat(SqlScript.placeholders(statement)).as(statement).isEmpty();
    }
    assertThat(renderer.rollupTables()).containsExactlyInAnyOrderElementsOf(ROLLUP_TABLES);
  }

  @Test
  @DisplayName(
      "1h views come before the 1m views they cascade from (no row can bypass the 1h view)")
  void viewOrder() {
    List<String> statements = renderer.renderAll(SchemaParameters.defaults());
    int lastHourly = -1;
    int firstMinutely = Integer.MAX_VALUE;
    for (int i = 0; i < statements.size(); i++) {
      String s = statements.get(i);
      if (s.startsWith("CREATE MATERIALIZED VIEW") && s.contains("_1h_mv ")) {
        lastHourly = i;
      }
      if (s.startsWith("CREATE MATERIALIZED VIEW") && s.contains("_1m_mv ")) {
        firstMinutely = Math.min(firstMinutely, i);
      }
    }
    assertThat(lastHourly).isLessThan(firstMinutely);
  }

  @Test
  @DisplayName("table prefix and database apply to every table and every view reference")
  void prefixAndDatabase() {
    SchemaParameters params =
        SchemaParameters.defaults().withDatabase("argos_t").withTablePrefix("p1_");
    List<String> statements = renderer.renderAll(params);

    Pattern reference = Pattern.compile("\\b([a-z_]+)\\.([a-z0-9_]+)\\b");
    for (String statement : statements.subList(1, statements.size())) {
      Matcher m = reference.matcher(SqlScript.withoutComments(statement));
      while (m.find()) {
        if (m.group(1).equals("argos_t")) {
          assertThat(m.group(2)).as(statement).startsWith("p1_");
        }
      }
      assertThat(statement).doesNotContain(" argos.");
    }
    assertThat(statements.get(0))
        .isEqualTo("CREATE DATABASE IF NOT EXISTS argos_t ENGINE = Atomic");
  }

  @Test
  @DisplayName(
      "retention variables are substituted (13 §8); bloom parameters are V001 literals (C-55)")
  void retentionAndBloom() {
    SchemaParameters params =
        SchemaParameters.defaults()
            .withRetentionDays("xlog-days", 14)
            .withRetentionDays("rollup-1h-days", 400);
    String xlog = statement(renderer.renderAll(params), "argos.xlog\n");

    assertThat(xlog)
        .contains("TTL toDateTime(ts) + INTERVAL 14 DAY")
        .contains("INDEX idx_tx      tx_id      TYPE bloom_filter(0.01) GRANULARITY 4,")
        .contains("INDEX idx_trace   trace_id   TYPE bloom_filter(0.01) GRANULARITY 4,")
        .contains("INDEX idx_user    user_id    TYPE bloom_filter(0.01) GRANULARITY 1,")
        .contains("INDEX idx_request request_id TYPE bloom_filter(0.01) GRANULARITY 4,")
        .contains("INDEX idx_sql     sql_ids    TYPE bloom_filter(0.01) GRANULARITY 4\n");
    assertThat(statement(renderer.renderAll(params), "argos.app_stat_1h\n"))
        .contains("TTL hour + INTERVAL 400 DAY");
    assertThat(statement(renderer.renderAll(params), "argos.profile_step\n"))
        .contains("TTL toDateTime(ts) + INTERVAL 7 DAY");
  }

  @Test
  @DisplayName(
      "C-56 only {db}, {p}, {retention.*} and {rollup:<table>} in V001; {bloom.*} is refused")
  void allowedDirectives(@TempDir Path dir) throws IOException {
    Path v001 = renderer.migrations().get(0);
    for (String name : SqlScript.placeholders(Files.readString(v001, StandardCharsets.UTF_8))) {
      assertThat(
              name.equals("db")
                  || name.equals("p")
                  || name.startsWith("retention.")
                  || name.startsWith("rollup:"))
          .as(name)
          .isTrue();
    }
    Path copy = copyClickHouseDir(dir);
    Files.writeString(
        copy.resolve("schema/V002__bloom.sql"),
        "ALTER TABLE {db}.{p}xlog ADD INDEX IF NOT EXISTS idx_x tx_id"
            + " TYPE bloom_filter({bloom.tx.fpp}) GRANULARITY 4;\n",
        StandardCharsets.UTF_8);
    assertThatThrownBy(() -> new SchemaRenderer(copy).renderAll(SchemaParameters.defaults()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown placeholders [bloom.tx.fpp]");
    Files.writeString(
        copy.resolve("schema/V002__bloom.sql"),
        "ALTER TABLE {db}.{p}xlog MODIFY TTL toDateTime(ts) + INTERVAL {retention.other} DAY"
            + " SETTINGS materialize_ttl_after_modify = 0;\n",
        StandardCharsets.UTF_8);
    assertThatThrownBy(() -> new SchemaRenderer(copy).renderAll(SchemaParameters.defaults()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown placeholders [retention.other]");
  }

  @Test
  @DisplayName(
      "C-56 checksum: SHA-256 of the text after {rollup:} expansion, before {db}/{p}/{retention.*}")
  void checksumDefinition() throws Exception {
    Path v001 = renderer.migrations().get(0);
    String expanded = renderer.expand(v001);

    assertThat(SqlScript.placeholders(expanded)).noneMatch(n -> n.startsWith("rollup:"));
    assertThat(SqlScript.placeholders(expanded))
        .containsExactlyInAnyOrder(
            "db",
            "p",
            "retention.xlog-days",
            "retention.profile-days",
            "retention.raw-days",
            "retention.rollup-1m-days",
            "retention.rollup-1h-days");
    assertThat(expanded).doesNotContain("\r").contains("FROM {db}.{p}xlog\nWHERE true \nGROUP BY");
    assertThat(renderer.checksum(v001))
        .matches("[0-9a-f]{64}")
        .isEqualTo(
            HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest(expanded.getBytes(StandardCharsets.UTF_8))));
    // Rendering is the expansion with the environment placeholders substituted.
    SchemaParameters params =
        SchemaParameters.defaults()
            .withDatabase("argos_c")
            .withTablePrefix("c_")
            .withRetentionDays("xlog-days", 9);
    assertThat(renderer.render(v001, params))
        .isEqualTo(
            SqlScript.split(
                SqlScript.replacePlaceholders(expanded, params.placeholderValues()::get)));
    assertThat(statement(renderer.render(v001, params), "argos_c.c_app_stat_1m_mv TO"))
        .endsWith(renderer.rollupSelect("app_stat_1m", params, ""));
  }

  @Test
  @DisplayName(
      "C-56 checksum: same for another database, prefix or retention; changes with a rollup file")
  void checksumStability(@TempDir Path dir) throws IOException {
    Path copy = copyClickHouseDir(dir);
    SchemaRenderer copied = new SchemaRenderer(copy);
    Path v001 = copied.migrations().get(0);
    String original = copied.checksum(v001);
    assertThat(original).isEqualTo(renderer.checksum(renderer.migrations().get(0)));

    // The checksum is a property of the script: two sets of environment values render different
    // statements from the same expansion.
    List<String> a = copied.render(v001, SchemaParameters.defaults());
    List<String> b =
        copied.render(
            v001,
            SchemaParameters.defaults()
                .withDatabase("other")
                .withTablePrefix("t9_")
                .withRetentionDays("raw-days", 3));
    assertThat(a).isNotEqualTo(b);
    assertThat(copied.checksum(v001)).isEqualTo(original);

    // CRLF line endings (a checkout without the .gitattributes rule) give the same checksum.
    String text = Files.readString(v001, StandardCharsets.UTF_8);
    Files.writeString(v001, text.replace("\n", "\r\n"), StandardCharsets.UTF_8);
    assertThat(copied.checksum(v001)).isEqualTo(original);
    Files.writeString(v001, text, StandardCharsets.UTF_8);

    // A changed rollup SELECT (an included file) changes the checksum.
    Path rollup = copy.resolve("rollups/app_stat_1m.sql");
    String select = Files.readString(rollup, StandardCharsets.UTF_8);
    Files.writeString(
        rollup,
        select.replace("toStartOfMinute(ts)", "toStartOfFiveMinutes(ts)"),
        StandardCharsets.UTF_8);
    String changed = copied.checksum(v001);
    assertThat(changed).isNotEqualTo(original);

    // So does a changed script.
    Files.writeString(rollup, select, StandardCharsets.UTF_8);
    assertThat(copied.checksum(v001)).isEqualTo(original);
    Files.writeString(
        v001, text.replace("GRANULARITY 1,", "GRANULARITY 2,"), StandardCharsets.UTF_8);
    assertThat(copied.checksum(v001)).isNotEqualTo(original).isNotEqualTo(changed);
  }

  @Test
  @DisplayName(
      "C-1 step types, C-17/C-67 column names, C-69 flags, C-70 profile_step batch_size/detail_enc")
  void alignmentDecisions() {
    List<String> statements = renderer.renderAll(SchemaParameters.defaults());
    String profile = SqlScript.withoutComments(statement(statements, "argos.profile_step\n"));
    assertThat(profile.replaceAll("\\s+", " "))
        .contains(
            "type Enum8('SQL' = 1, 'DBC' = 2, 'COMMIT' = 3, 'ROLLBACK' = 4, 'HTTP' = 5,"
                + " 'METHOD' = 6, 'ASYNC' = 7, 'ERROR' = 8, 'STACK' = 9, 'MESSAGE' = 10,"
                + " 'SUMMARY' = 11)")
        .contains(" batch_size UInt32,")
        .contains(" detail_enc String,");
    String xlog = statement(statements, "argos.xlog\n");
    for (String column :
        List.of(
            "dbc_wait_ms ",
            "request_id ",
            "dispatch_count ",
            "streaming ",
            "timed_out ",
            "incoming_trace ",
            "app_version_id ",
            "late ",
            "flags ")) {
      assertThat(xlog).contains("\n    " + column);
    }
    assertThat(xlog)
        .contains("C-69 bits: 1 virtual thread, 2 leaked, 4 summarized; other bits reserved");
    String code = SqlScript.withoutComments(String.join("\n", statements));
    for (String old : List.of("dbc_time_ms", "timeout_flag", " version_id", "sum_dbc_time_ms")) {
      assertThat(code).as(old).doesNotContain(old);
    }
    for (String table : List.of("app_stat_1m", "service_stat_1h", "instance_stat_1m")) {
      assertThat(statement(statements, "argos." + table + "\n"))
          .contains("sum_dbc_wait_ms   SimpleAggregateFunction(sum, UInt64),");
    }
    assertThat(statement(statements, "argos.service_stat_1m\n"))
        .contains("ORDER BY (app_id, kind, service_id, app_version_id, minute)");
    assertThat(statement(statements, "argos.error_stat_1m\n"))
        .contains("ORDER BY (app_id, error_group_id, service_id, app_version_id, minute)");
  }

  @Test
  @DisplayName(
      "C-53 transaction rollups: q_elapsed is a quantilesDD(0.01, 0.5, 0.95, 0.99) state (1m State,"
          + " 1h MergeState)")
  void percentileStates() {
    List<String> statements = renderer.renderAll(SchemaParameters.defaults());
    String type = "q_elapsed         AggregateFunction(quantilesDD(0.01, 0.5, 0.95, 0.99), UInt32)";
    for (String table : TRANSACTION_ROLLUPS) {
      assertThat(statement(statements, "argos." + table + "\n")).as(table).contains(type);
      assertThat(renderer.rollupSelect(table, SchemaParameters.defaults(), ""))
          .as(table)
          .contains(
              table.endsWith("_1h")
                  ? "quantilesDDMergeState(0.01, 0.5, 0.95, 0.99)(q_elapsed) AS q_elapsed"
                  : "quantilesDDState(0.01, 0.5, 0.95, 0.99)(elapsed_ms)")
          .doesNotContain("TDigest")
          .doesNotContain("BFloat16");
    }
    assertThat(SqlScript.withoutComments(String.join("\n", statements)))
        .doesNotContain("TDigest")
        .doesNotContain("BFloat16");
  }

  @Test
  @DisplayName(
      "C-54 codecs: no LZ4, ZSTD(1) stages, xlog trace_id/parent_span_id without CODEC(NONE),"
          + " tx_id Delta + ZSTD(1), span_id NONE, ZSTD(3) kept")
  void codecs() {
    List<String> statements = renderer.renderAll(SchemaParameters.defaults());
    assertThat(SqlScript.withoutComments(String.join("\n", statements))).doesNotContain("LZ4");
    String xlog = statement(statements, "argos.xlog\n");
    assertThat(xlog)
        .contains("ts               DateTime64(3, 'UTC') CODEC(DoubleDelta, ZSTD(1)),")
        .contains("tx_id            UInt64 CODEC(Delta, ZSTD(1)),")
        .contains("trace_id         FixedString(16),")
        .contains("span_id          UInt64 CODEC(NONE),")
        .contains("parent_span_id   UInt64,")
        .contains("elapsed_ms       UInt32 CODEC(T64, ZSTD(1)),")
        .contains("user_id          String CODEC(ZSTD(3)),")
        .contains("request_id       String CODEC(ZSTD(3)),")
        .contains("alloc_bytes      Int64  CODEC(T64, ZSTD(1)),");
    assertThat(SqlScript.withoutComments(xlog)).containsOnlyOnce("CODEC(NONE)");
    assertThat(statement(statements, "argos.profile_step\n"))
        .contains("detail          String CODEC(ZSTD(3)),");
    assertThat(statement(statements, "argos.metric\n"))
        .contains("value       Float64 CODEC(Gorilla, ZSTD(1)),");
  }

  @Test
  @DisplayName("rollup SELECT: MV body has an empty {where}, rebuild gets AND (<predicate>) (D-14)")
  void rollupSelect() {
    SchemaParameters params = SchemaParameters.defaults().withTablePrefix("r_");

    String mvBody = renderer.rollupSelect("error_stat_1m", params, "");
    assertThat(mvBody)
        .startsWith("SELECT")
        .contains("FROM argos.r_xlog")
        .contains("WHERE error = 1 AND error_group_id != 0 \nGROUP BY")
        .doesNotContain("--");

    String rebuild =
        renderer.rebuildInsert(
            "app_stat_1m", "argos.r_app_stat_1m_staging", params, "toDate(ts) = '2026-10-24'");
    assertThat(rebuild)
        .startsWith("INSERT INTO argos.r_app_stat_1m_staging\nSELECT")
        .contains("WHERE true AND (toDate(ts) = '2026-10-24')\nGROUP BY");

    String view = statement(renderer.renderAll(params), "argos.r_app_stat_1m_mv TO");
    assertThat(view).endsWith(renderer.rollupSelect("app_stat_1m", params, ""));
  }

  @Test
  @DisplayName(
      "C-59 rebuild: staging table + REPLACE PARTITION per whole partition, 1m day before the 1h"
          + " month of that day, never INSERT INTO a rollup table")
  void rebuildStatements() {
    SchemaParameters params = SchemaParameters.defaults().withTablePrefix("r_");
    LocalDate day = LocalDate.parse("2026-10-24");
    List<String> statements = RollupRebuild.dayStatements(renderer, params, day);

    assertThat(statements).hasSize(12 * 5);
    assertThat(statements.subList(0, 5))
        .containsExactly(
            "DROP TABLE IF EXISTS argos.r_app_stat_1m_rebuild SYNC",
            "CREATE TABLE argos.r_app_stat_1m_rebuild AS argos.r_app_stat_1m",
            renderer.rebuildInsert(
                "app_stat_1m", "argos.r_app_stat_1m_rebuild", params, "toDate(ts) = '2026-10-24'"),
            "ALTER TABLE argos.r_app_stat_1m REPLACE PARTITION '2026-10-24' FROM"
                + " argos.r_app_stat_1m_rebuild",
            "DROP TABLE argos.r_app_stat_1m_rebuild SYNC");
    for (String s : statements) {
      if (s.startsWith("INSERT INTO ")) {
        assertThat(s.substring(0, s.indexOf('\n'))).as(s).endsWith("_rebuild");
      }
    }
    int lastMinute = -1;
    int firstHour = Integer.MAX_VALUE;
    for (int i = 0; i < statements.size(); i++) {
      String s = statements.get(i);
      if (s.contains(" REPLACE PARTITION '2026-10-24' FROM ")) {
        lastMinute = i;
      }
      if (s.contains(" REPLACE PARTITION 202610 FROM ")) {
        firstHour = Math.min(firstHour, i);
      }
    }
    assertThat(lastMinute).isLessThan(firstHour);
    assertThat(statements.stream().filter(s -> s.startsWith("ALTER TABLE")).count()).isEqualTo(12);
    assertThat(statements).noneMatch(s -> s.contains("app_id ="));
    assertThat(statements)
        .contains(
            renderer.rebuildInsert(
                "service_stat_1h",
                "argos.r_service_stat_1h_rebuild",
                params,
                "toYYYYMM(minute) = 202610"));
  }

  @Test
  @DisplayName("{where} must be empty or start with AND; table names are validated")
  void rollupContract() {
    SchemaParameters params = SchemaParameters.defaults();
    assertThatThrownBy(() -> renderer.rollupSelect("app_stat_1m", params, "WHERE x = 1"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> renderer.rollupSelect("../schema/V001__init", params, ""))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("unknown placeholders are rejected (13 §13.2: only defined placeholders)")
  void unknownPlaceholder(@TempDir Path dir) throws IOException {
    Files.createDirectories(dir.resolve("schema"));
    Files.createDirectories(dir.resolve("rollups"));
    Files.writeString(
        dir.resolve("schema/V001__x.sql"),
        "-- {mentioned} in a comment is fine\nCREATE TABLE IF NOT EXISTS {db}.{p}t (x UInt8) ENGINE = Memory;\n"
            + "CREATE TABLE IF NOT EXISTS {db}.{other} (x UInt8) ENGINE = Memory;",
        StandardCharsets.UTF_8);

    assertThatThrownBy(() -> new SchemaRenderer(dir).renderAll(SchemaParameters.defaults()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown placeholders [other]");
  }

  @Test
  @DisplayName("Q20 variant: withoutColumnCodecs drops CODEC clauses only")
  void withoutCodecs() {
    String xlog = statement(renderer.renderAll(SchemaParameters.defaults()), "argos.xlog\n");
    String plain = SchemaRenderer.withoutColumnCodecs(xlog);

    assertThat(plain).doesNotContain("CODEC");
    assertThat(plain).contains("ts               DateTime64(3, 'UTC'),");
    assertThat(plain).contains("span_id          UInt64,");
    assertThat(plain).contains("INDEX idx_tx");
  }

  @Test
  @DisplayName("parameters reject names that could inject SQL")
  void parameterValidation() {
    assertThatThrownBy(() -> SchemaParameters.defaults().withDatabase("argos; DROP"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SchemaParameters.defaults().withTablePrefix("a.b"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SchemaParameters.defaults().withRetentionDays("xlog-days", 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** A copy of deploy/clickhouse/schema and rollups that a test may change. */
  private static Path copyClickHouseDir(Path dir) throws IOException {
    Path source = SchemaRenderer.fromRepository().migrations().get(0).getParent().getParent();
    for (String sub : List.of("schema", "rollups")) {
      Files.createDirectories(dir.resolve(sub));
      try (Stream<Path> files = Files.list(source.resolve(sub))) {
        for (Path f : files.toList()) {
          Files.copy(f, dir.resolve(sub).resolve(f.getFileName()));
        }
      }
    }
    return dir;
  }

  private static String statement(List<String> statements, String marker) {
    return statements.stream()
        .filter(s -> s.contains(marker))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No statement with " + marker));
  }
}
