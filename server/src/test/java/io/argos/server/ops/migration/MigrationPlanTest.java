/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The comparison of bundled files and ch_schema_history (pure). */
@Tag("req:N-22")
class MigrationPlanTest {

  private final MigrationSource source =
      MigrationSource.of(
          "test",
          Map.of(
              "schema/V001__init.sql", ClickHouseMigrationRunnerTest.V001,
              "schema/V002__note.sql", ClickHouseMigrationRunnerTest.V002,
              "rollups/r_1m.sql", ClickHouseMigrationRunnerTest.ROLLUP,
              "rollups/r_1m_v2.sql", ClickHouseMigrationRunnerTest.ROLLUP,
              "migrations/M001__r_v2.yaml", ClickHouseMigrationRunnerTest.M001));
  private final MigrationCatalog catalog =
      MigrationCatalog.load(source, new SchemaTemplates(source));

  private HistoryEntry row(String version, String checksum, boolean success) {
    return new HistoryEntry(version, "", checksum, null, 1, success, success ? null : "error");
  }

  private String checksum(String version) {
    for (SchemaScript s : catalog.scripts()) {
      if (s.version().equals(version)) {
        return s.checksum();
      }
    }
    for (Procedure.Step s : catalog.steps()) {
      if (s.version().equals(version)) {
        return s.checksum();
      }
    }
    throw new IllegalArgumentException(version);
  }

  @Test
  @DisplayName("T-ST-052 empty history: every script and step is pending, in order")
  void empty() {
    MigrationPlan plan = MigrationPlan.of(catalog, List.of(), false);

    assertThat(plan.pendingScripts())
        .extracting(SchemaScript::version)
        .containsExactly("V001", "V002");
    assertThat(plan.pendingSteps())
        .extracting(Procedure.Step::version)
        .containsExactly("M001.create_v2", "M001.backfill", "M001.switch", "M001.drop_old");
    assertThat(plan.mismatches()).isEmpty();
    assertThat(plan.upToDate()).isFalse();
  }

  @Test
  @DisplayName("T-SV-001 everything applied: nothing pending")
  void upToDate() {
    List<HistoryEntry> history = new ArrayList<>();
    for (String v :
        List.of(
            "V001", "V002", "M001.create_v2", "M001.backfill", "M001.switch", "M001.drop_old")) {
      history.add(row(v, checksum(v), true));
    }
    history.add(row("M001.backfill/20260101", checksum("M001.backfill"), true));
    history.add(row("repair.20260101000000", "0".repeat(64), true));

    MigrationPlan plan = MigrationPlan.of(catalog, history, false);

    assertThat(plan.upToDate()).isTrue();
    assertThat(plan.unknown()).isEmpty();
    assertThat(plan.completedProgress()).containsExactly("M001.backfill/20260101");
  }

  @Test
  @DisplayName("T-ST-052 failed rows are pending again; mismatches name the version once")
  void failedAndMismatched() {
    List<HistoryEntry> history =
        List.of(
            row("V001", "f".repeat(64), true),
            row("V002", checksum("V002"), false),
            row("M001.create_v2", checksum("M001.create_v2"), true),
            row("M001.backfill/20260101", "e".repeat(64), true),
            row("M001.backfill/20260102", "e".repeat(64), true));

    MigrationPlan plan = MigrationPlan.of(catalog, history, false);

    assertThat(plan.pendingScripts()).extracting(SchemaScript::version).containsExactly("V002");
    assertThat(plan.mismatches())
        .extracting(MigrationPlan.Mismatch::version)
        .containsExactly("V001", "M001.backfill");
    assertThat(plan.mismatches().get(0).recorded()).isEqualTo("f".repeat(64));
    assertThat(plan.mismatches().get(0).bundled()).isEqualTo(checksum("V001"));
  }

  @Test
  @DisplayName("T-ST-052 unknown versions and out-of-order versions are reported")
  void unknownAndOutOfOrder() {
    List<HistoryEntry> history =
        List.of(
            row("V002", checksum("V002"), true),
            row("V007", "0".repeat(64), true),
            row("V008", "0".repeat(64), false),
            row("M001.switch", checksum("M001.switch"), true),
            row("garbage", "0".repeat(64), true));

    MigrationPlan plan = MigrationPlan.of(catalog, history, false);

    assertThat(plan.unknown()).containsExactly("V007", "garbage");
    assertThat(plan.outOfOrder()).containsExactly("V001", "M001.create_v2", "M001.backfill");
  }

  @Test
  @DisplayName("ignoring the history (ClickHouse reset) makes everything pending")
  void ignoreHistory() {
    MigrationPlan plan =
        MigrationPlan.of(catalog, List.of(row("V001", "f".repeat(64), true)), true);

    assertThat(plan.pendingScripts()).hasSize(2);
    assertThat(plan.mismatches()).isEmpty();
  }
}
