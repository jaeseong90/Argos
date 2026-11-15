/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.ops.migration.Procedure.Step;
import io.argos.storage.clickhouse.RepositoryLayout;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The M procedure format of deploy/clickhouse/migrations/README.md (13 §13.2). */
@Tag("req:N-22")
class ProcedureParserTest {

  static final String FOUR_STEPS =
      """
      # SPDX-License-Identifier: Apache-2.0
      description: replace instance_stat_1m by a copy
      steps:
        - id: create_v2
          kind: ddl
          statements:
            - CREATE TABLE IF NOT EXISTS {db}.{p}instance_stat_1m_v2 AS {db}.{p}instance_stat_1m
            - >-
              CREATE MATERIALIZED VIEW IF NOT EXISTS {db}.{p}instance_stat_1m_v2_mv
              TO {db}.{p}instance_stat_1m_v2 SQL SECURITY DEFINER AS {rollup:instance_stat_1m}
        - id: backfill
          kind: backfill
          table: instance_stat_1m
          days: "{retention.xlog-days}"
        - id: switch
          kind: switch
          alias: instance_stat_1m
          table: instance_stat_1m_v2
        - id: drop_old
          kind: drop
          statements:
            - DROP VIEW IF EXISTS {db}.{p}instance_stat_1m_mv
      """;

  private final MigrationSource base =
      MigrationSource.fromDirectory(RepositoryLayout.clickhouseDir());

  @Test
  @DisplayName("T-ST-052 a four-step procedure parses into ddl, backfill, switch and drop steps")
  void fourSteps() {
    Procedure p = parse(FOUR_STEPS);

    assertThat(p.version()).isEqualTo("M001");
    assertThat(p.steps())
        .extracting(Step::version)
        .containsExactly("M001.create_v2", "M001.backfill", "M001.switch", "M001.drop_old");
    assertThat(p.steps())
        .extracting(Step::kind)
        .containsExactly(Step.Kind.DDL, Step.Kind.BACKFILL, Step.Kind.SWITCH, Step.Kind.DROP);
    Step ddl = p.steps().get(0);
    assertThat(ddl.statements()).hasSize(2);
    assertThat(ddl.statements().get(1))
        .contains("SQL SECURITY DEFINER AS SELECT")
        .contains("FROM {db}.{p}xlog")
        .doesNotContain("{rollup:");
    Step backfill = p.steps().get(1);
    assertThat(backfill.unit()).isEqualTo("day");
    assertThat(backfill.days()).isEqualTo("{retention.xlog-days}");
    assertThat(p.steps())
        .extracting(Step::checksum)
        .allMatch(c -> c.matches("[0-9a-f]{64}"))
        .doesNotHaveDuplicates();
  }

  @Test
  @DisplayName(
      "T-ST-052 step checksums change with the step text and the included rollup, not with other"
          + " steps")
  void checksums() {
    Procedure p = parse(FOUR_STEPS);
    Procedure changedDrop =
        parse(FOUR_STEPS.replace("instance_stat_1m_mv\n", "instance_stat_1m_mv SYNC\n"));
    assertThat(changedDrop.steps().subList(0, 3))
        .extracting(Step::checksum)
        .isEqualTo(p.steps().subList(0, 3).stream().map(Step::checksum).toList());
    assertThat(changedDrop.steps().get(3).checksum()).isNotEqualTo(p.steps().get(3).checksum());

    MigrationSource changedRollup =
        base.overlay(
            MigrationSource.of(
                "fixture",
                Map.of(
                    "rollups/instance_stat_1m.sql",
                    base.read("rollups/instance_stat_1m.sql")
                        .orElseThrow()
                        .replace("count()", "count(*)"))));
    Procedure withChangedRollup = parse(changedRollup, FOUR_STEPS);
    assertThat(withChangedRollup.steps().get(0).checksum())
        .isNotEqualTo(p.steps().get(0).checksum());
    assertThat(withChangedRollup.steps().get(1).checksum())
        .isNotEqualTo(p.steps().get(1).checksum());
    assertThat(withChangedRollup.steps().get(2).checksum()).isEqualTo(p.steps().get(2).checksum());
  }

  @Test
  @DisplayName("T-ST-052 invalid procedures are refused with the file and the problem")
  void invalid() {
    refused("description: x\nsteps: []\n", "non-empty list");
    refused("description: x\nsteps:\n  - id: a\n    kind: rename\n", "kind must be");
    refused(
        "description: x\nsteps:\n  - id: A\n    kind: ddl\n    statements: [x]\n", "must match");
    refused(
        "description: x\nsteps:\n  - id: a\n    kind: ddl\n    statements: ['CREATE TABLE IF NOT EXISTS {db}.t (x UInt8) ENGINE = Memory']\n  - id: a\n    kind: ddl\n    statements: [x]\n",
        "duplicate step id a");
    refused(
        "description: x\nsteps:\n  - id: a\n    kind: ddl\n    statements: ['DROP TABLE IF EXISTS {db}.t']\n",
        "belong to a drop step");
    refused(
        "description: x\nsteps:\n  - id: a\n    kind: drop\n    statements: ['CREATE TABLE IF NOT EXISTS {db}.t (x UInt8) ENGINE = Memory']\n",
        "DROP statements only");
    refused(
        "description: x\nsteps:\n  - id: a\n    kind: ddl\n    statements: ['SELECT {nope}']\n",
        "unknown placeholders [nope]");
    refused(
        "description: x\nsteps:\n  - id: a\n    kind: ddl\n    statements: ['CREATE TABLE IF NOT EXISTS {db}.a (x UInt8) ENGINE = Memory; CREATE TABLE IF NOT EXISTS {db}.b (x UInt8) ENGINE = Memory']\n",
        "exactly one statement");
    refused(
        "description: x\nsteps:\n  - id: a\n    kind: backfill\n    table: no_such_rollup\n    days: 3\n",
        "no rollup SELECT file");
    refused(
        "description: x\nsteps:\n  - id: a\n    kind: backfill\n    table: app_stat_1m\n    days: '{retention.nope}'\n",
        "days must be");
    refused(
        "description: x\nsteps:\n  - id: a\n    kind: backfill\n    table: app_stat_1m\n    days: 3\n    unit: week\n",
        "unit must be");
    refused(
        "description: x\nsteps:\n  - id: a\n    kind: switch\n    alias: app_stat_1m\n    table: x\n    days: 3\n",
        "unknown key 'days'");
    refused(
        "description: x\nextra: 1\nsteps:\n  - id: a\n    kind: switch\n", "unknown key 'extra'");
    refused("steps:\n  - id: a\n", "description");
    refused("description: [unclosed\n", "invalid YAML");
    refused("description: !!java.util.Date 1\nsteps: []\n", "invalid YAML");
  }

  private Procedure parse(String yaml) {
    return parse(base, yaml);
  }

  private static Procedure parse(MigrationSource source, String yaml) {
    return new ProcedureParser(new SchemaTemplates(source))
        .parse(1, "M001", "migrations/M001__test.yaml", yaml);
  }

  private void refused(String yaml, String message) {
    assertThatThrownBy(() -> parse(yaml))
        .as(yaml)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("migrations/M001__test.yaml")
        .hasMessageContaining(message);
  }
}
