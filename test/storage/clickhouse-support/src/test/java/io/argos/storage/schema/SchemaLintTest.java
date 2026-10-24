/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("req:N-22")
class SchemaLintTest {

  @Test
  @DisplayName("T-ST-052 V001 follows the migration runner rules (idempotent DDL only)")
  void repositorySchemaIsClean() {
    assertThat(
            SchemaLint.violations(
                SchemaRenderer.fromRepository().renderAll(SchemaParameters.defaults())))
        .isEmpty();
  }

  @Test
  @DisplayName("T-ST-052 non-idempotent and forbidden statements are reported")
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
            "ALTER TABLE argos.t MODIFY TTL ts + INTERVAL 1 DAY",
            "INSERT INTO argos.t VALUES (1)");

    List<String> violations = SchemaLint.violations(bad);

    assertThat(violations).hasSize(bad.size());
    assertThat(violations.get(0)).contains("CREATE without IF NOT EXISTS");
    assertThat(violations.get(2)).contains("DROP without IF EXISTS");
    assertThat(violations.get(5)).contains("forbidden");
    assertThat(violations.get(7)).contains("materialize_ttl_after_modify");
    assertThat(violations.get(8)).contains("not a DDL statement");
  }

  @Test
  @DisplayName("T-ST-052 idempotent forms pass; keywords inside comments and strings are ignored")
  void allowed() {
    List<String> good =
        List.of(
            "CREATE TABLE IF NOT EXISTS argos.t (x UInt8 COMMENT 'DROP TABLE x') ENGINE = Memory",
            "-- CREATE TABLE argos.t\nCREATE DATABASE IF NOT EXISTS argos",
            "DROP VIEW IF EXISTS argos.v",
            "ALTER TABLE argos.t ADD COLUMN IF NOT EXISTS y UInt8, DROP COLUMN IF EXISTS z",
            "ALTER TABLE argos.t MODIFY TTL ts + INTERVAL 7 DAY SETTINGS materialize_ttl_after_modify = 0",
            "ALTER TABLE argos.t MODIFY SETTING ttl_only_drop_parts = 1",
            "RENAME TABLE argos.a TO argos.b");

    assertThat(SchemaLint.violations(good)).isEmpty();
  }
}
