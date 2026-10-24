/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

  private final SchemaRenderer renderer = SchemaRenderer.fromRepository();

  @Test
  @DisplayName("V001 renders to 1 database + 17 tables + 12 views, every placeholder resolved")
  void rendersEveryObject() {
    List<String> statements = renderer.renderAll(SchemaParameters.defaults());

    assertThat(statements).hasSize(30);
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
  @DisplayName("retention and bloom variables are substituted (13 §8, PoC Q5/Q6/Q11)")
  void retentionAndBloom() {
    SchemaParameters params =
        SchemaParameters.defaults()
            .withRetentionDays("xlog-days", 14)
            .withRetentionDays("rollup-1h-days", 400)
            .withBloom("tx", 0.025, 1)
            .withBloom("sql", 0.001, 2);
    String xlog = statement(renderer.renderAll(params), "argos.xlog\n");

    assertThat(xlog)
        .contains("TTL toDateTime(ts) + INTERVAL 14 DAY")
        .contains("INDEX idx_tx      tx_id      TYPE bloom_filter(0.025)      GRANULARITY 1")
        .contains("INDEX idx_trace   trace_id   TYPE bloom_filter(0.01)   GRANULARITY 4")
        .contains("INDEX idx_sql     sql_ids    TYPE bloom_filter(0.001)     GRANULARITY 2");
    assertThat(statement(renderer.renderAll(params), "argos.app_stat_1h\n"))
        .contains("TTL hour + INTERVAL 400 DAY");
    assertThat(statement(renderer.renderAll(params), "argos.profile_step\n"))
        .contains("TTL toDateTime(ts) + INTERVAL 7 DAY");
  }

  @Test
  @DisplayName("C-1 step type numbering and C-17 xlog columns are in the DDL")
  void alignmentDecisions() {
    List<String> statements = renderer.renderAll(SchemaParameters.defaults());
    String profile = SqlScript.withoutComments(statement(statements, "argos.profile_step\n"));
    assertThat(profile.replaceAll("\\s+", " "))
        .contains(
            "type Enum8('SQL' = 1, 'DBC' = 2, 'COMMIT' = 3, 'ROLLBACK' = 4, 'HTTP' = 5,"
                + " 'METHOD' = 6, 'ASYNC' = 7, 'ERROR' = 8, 'STACK' = 9, 'MESSAGE' = 10,"
                + " 'SUMMARY' = 11)");
    String xlog = statement(statements, "argos.xlog\n");
    for (String column :
        List.of(
            "dbc_time_ms ",
            "request_id ",
            "dispatch_count ",
            "streaming ",
            "timeout_flag ",
            "incoming_trace ",
            "version_id ",
            "late ",
            "flags ")) {
      assertThat(xlog).contains("\n    " + column);
    }
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
    assertThat(plain).contains("trace_id         FixedString(16),");
    assertThat(plain).contains("INDEX idx_tx");
  }

  @Test
  @DisplayName("parameters reject names that could inject SQL")
  void parameterValidation() {
    assertThatThrownBy(() -> SchemaParameters.defaults().withDatabase("argos; DROP"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SchemaParameters.defaults().withTablePrefix("a.b"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SchemaParameters.defaults().withBloom("tx", 1.5, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SchemaParameters.defaults().withRetentionDays("xlog-days", 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static String statement(List<String> statements, String marker) {
    return statements.stream()
        .filter(s -> s.contains(marker))
        .findFirst()
        .orElseThrow(() -> new AssertionError("No statement with " + marker));
  }
}
