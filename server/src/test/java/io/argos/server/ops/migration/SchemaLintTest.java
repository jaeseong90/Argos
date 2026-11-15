/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The idempotency and ALTER rules of 13 §13.2. */
@Tag("req:N-22")
class SchemaLintTest {

  @Test
  @DisplayName("T-ST-052 the bundled ClickHouse schema follows the runner rules")
  void bundledSchemaIsClean() {
    MigrationSource source = MigrationSource.fromClasspath(getClass().getClassLoader());
    SchemaTemplates templates = new SchemaTemplates(source);
    MigrationCatalog catalog = MigrationCatalog.load(source, templates);
    for (SchemaScript script : catalog.scripts()) {
      assertThat(
              SchemaLint.violations(
                  SchemaTemplates.render(script.expanded(), SchemaParameters.defaults())))
          .as(script.path())
          .isEmpty();
    }
  }

  @Test
  @DisplayName(
      "T-ST-052 non-idempotent and forbidden statements are reported, one per statement (RENAME"
          + " COLUMN without IF EXISTS and RENAME TABLE/DATABASE included)")
  void violations() {
    List<String> bad =
        List.of(
            "CREATE TABLE argos.t (x UInt8) ENGINE = Memory",
            "CREATE MATERIALIZED VIEW argos.v TO argos.t AS SELECT 1",
            "DROP TABLE argos.t",
            "ALTER TABLE argos.t ADD COLUMN y UInt8",
            "ALTER TABLE argos.t DROP COLUMN y",
            "ALTER TABLE argos.t MODIFY ORDER BY (x, y)",
            "ALTER TABLE argos.t MODIFY COLUMN x UInt16",
            "ALTER TABLE argos.t MODIFY COLUMN IF EXISTS x Nullable(UInt8)",
            "ALTER TABLE argos.t MODIFY COLUMN `x` LowCardinality(String) CODEC(ZSTD(1))",
            "ALTER TABLE argos.t MODIFY TTL ts + INTERVAL 1 DAY",
            "INSERT INTO argos.t VALUES (1)",
            "CREATE OR REPLACE VIEW argos.v AS SELECT 1",
            "ALTER TABLE argos.t RENAME COLUMN a TO b",
            "ALTER TABLE argos.t RENAME COLUMN IF EXISTS a TO b, RENAME COLUMN c TO d",
            "RENAME TABLE argos.a TO argos.b",
            "RENAME DATABASE argos TO argos_old");

    List<String> violations = SchemaLint.violations(bad);

    assertThat(violations).hasSize(bad.size());
    assertThat(violations.get(0)).startsWith("statement 1: CREATE without IF NOT EXISTS");
    assertThat(violations.get(2)).contains("DROP without IF EXISTS");
    assertThat(violations.get(3)).contains("ADD without IF NOT EXISTS");
    assertThat(violations.get(5)).contains("MODIFY ORDER BY is forbidden");
    assertThat(violations.subList(6, 9)).allMatch(v -> v.contains("type change is forbidden"));
    assertThat(violations.get(9)).contains("materialize_ttl_after_modify");
    assertThat(violations.get(10)).contains("not a DDL statement");
    assertThat(violations.get(11)).contains("unsupported CREATE");
    assertThat(violations.get(12)).startsWith("statement 13: RENAME COLUMN without IF EXISTS");
    assertThat(violations.get(13)).startsWith("statement 14: RENAME COLUMN without IF EXISTS");
    assertThat(violations.subList(14, 16))
        .allMatch(v -> v.contains("RENAME is not idempotent (use a 4-step M procedure)"));
  }

  @Test
  @DisplayName(
      "T-ST-052 idempotent forms (RENAME COLUMN IF EXISTS included) and MODIFY COLUMN without a"
          + " type pass; comments and strings are ignored")
  void allowed() {
    List<String> good =
        List.of(
            "CREATE TABLE IF NOT EXISTS argos.t (x UInt8 COMMENT 'DROP TABLE x') ENGINE = Memory",
            "-- CREATE TABLE argos.t\nCREATE DATABASE IF NOT EXISTS argos",
            "DROP VIEW IF EXISTS argos.v",
            "ALTER TABLE argos.t ADD COLUMN IF NOT EXISTS y UInt8, DROP COLUMN IF EXISTS z",
            "ALTER TABLE argos.t MODIFY TTL ts + INTERVAL 7 DAY SETTINGS materialize_ttl_after_modify = 0",
            "ALTER TABLE argos.t MODIFY SETTING ttl_only_drop_parts = 1",
            "ALTER TABLE argos.t MODIFY COLUMN x CODEC(ZSTD(3))",
            "ALTER TABLE argos.t MODIFY COLUMN x COMMENT 'MODIFY COLUMN x UInt16'",
            "ALTER TABLE argos.t MODIFY COLUMN x DEFAULT 0, MODIFY COLUMN y REMOVE TTL",
            "ALTER TABLE argos.t MODIFY COLUMN IF EXISTS x TTL ts + INTERVAL 1 DAY",
            "ALTER TABLE argos.t RENAME COLUMN IF EXISTS a TO b",
            "ALTER TABLE argos.t RENAME COLUMN IF EXISTS a TO b, ADD COLUMN IF NOT EXISTS c UInt8");

    assertThat(SchemaLint.violations(good)).isEmpty();
  }
}
