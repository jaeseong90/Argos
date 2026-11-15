/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Orchestration of the ClickHouse migration runner with in-memory ClickHouse and history (order,
 * idempotency, refusals, repair, procedures). The same behaviour against real PostgreSQL and
 * ClickHouse is covered by the integration tests (MigrationStartupIT, MigrationRefusalIT,
 * SchemaUpgradeIT).
 */
@Tag("req:N-22")
class ClickHouseMigrationRunnerTest {

  static final String V001 =
      """
      -- SPDX-License-Identifier: Apache-2.0
      CREATE DATABASE IF NOT EXISTS {db} ENGINE = Atomic;
      CREATE TABLE IF NOT EXISTS {db}.{p}xlog (ts DateTime('UTC'), app_id UInt32)
      ENGINE = MergeTree PARTITION BY toDate(ts) ORDER BY ts TTL ts + INTERVAL {retention.xlog-days} DAY;
      CREATE TABLE IF NOT EXISTS {db}.{p}r_1m (minute DateTime('UTC'), c UInt64)
      ENGINE = SummingMergeTree PARTITION BY toDate(minute) ORDER BY minute;
      CREATE MATERIALIZED VIEW IF NOT EXISTS {db}.{p}r_1m_mv TO {db}.{p}r_1m AS {rollup:r_1m};
      """;
  static final String V002 =
      "ALTER TABLE {db}.{p}xlog ADD COLUMN IF NOT EXISTS note String DEFAULT '';\n";
  static final String ROLLUP =
      "SELECT toStartOfMinute(ts) AS minute, count() AS c FROM {db}.{p}xlog WHERE true {where}"
          + " GROUP BY minute";
  static final String M001 =
      """
      description: r_1m to r_1m_v2
      steps:
        - id: create_v2
          kind: ddl
          statements:
            - CREATE TABLE IF NOT EXISTS {db}.{p}r_1m_v2 AS {db}.{p}r_1m
            - CREATE MATERIALIZED VIEW IF NOT EXISTS {db}.{p}r_1m_v2_mv TO {db}.{p}r_1m_v2 AS {rollup:r_1m_v2}
        - id: backfill
          kind: backfill
          table: r_1m_v2
          days: 3
        - id: switch
          kind: switch
          alias: r_1m
          table: r_1m_v2
        - id: drop_old
          kind: drop
          statements:
            - DROP VIEW IF EXISTS {db}.{p}r_1m_mv
            - DROP TABLE IF EXISTS {db}.{p}r_1m
      """;

  private final Clock clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
  private final LocalDate today = LocalDate.now(clock);
  private final FakeClickHouse clickHouse = new FakeClickHouse();
  private final FakeClickHouse.Store store = new FakeClickHouse.Store();

  private static MigrationSource source(Map<String, String> extra) {
    Map<String, String> files = new LinkedHashMap<>();
    files.put("schema/V001__init.sql", V001);
    files.put("rollups/r_1m.sql", ROLLUP);
    files.put("rollups/r_1m_v2.sql", ROLLUP);
    files.putAll(extra);
    return MigrationSource.of("test", files);
  }

  private static MigrationSource full() {
    return source(Map.of("schema/V002__note.sql", V002, "migrations/M001__r_v2.yaml", M001));
  }

  private ClickHouseMigrationRunner runner(MigrationSource source, boolean repair) {
    return new ClickHouseMigrationRunner(
        source, SchemaParameters.defaults(), clickHouse, store, clock, repair);
  }

  private String day(int daysAgo) {
    return DateTimeFormatter.BASIC_ISO_DATE.format(today.minusDays(daysAgo));
  }

  @Test
  @DisplayName(
      "T-ST-052 empty history: scripts in order, then the four procedure steps; one history row"
          + " each")
  void emptyHistoryAppliesEverything() throws Exception {
    clickHouse.answers =
        q -> q.startsWith("SELECT DISTINCT") ? List.of(day(1), day(0)) : List.of("1");

    ClickHouseMigrationRunner.Result result = runner(full(), false).run();

    assertThat(clickHouse.executed.get(0))
        .isEqualTo("CREATE DATABASE IF NOT EXISTS argos ENGINE = Atomic");
    assertThat(clickHouse.executed.get(2)).startsWith("CREATE TABLE IF NOT EXISTS argos.r_1m");
    assertThat(clickHouse.executed.get(4))
        .isEqualTo("ALTER TABLE argos.xlog ADD COLUMN IF NOT EXISTS note String DEFAULT ''");
    assertThat(clickHouse.executed)
        .contains(
            "ALTER TABLE argos.r_1m_v2 REPLACE PARTITION '"
                + today.minusDays(1)
                + "' FROM argos.r_1m_v2_backfill",
            "DROP VIEW IF EXISTS argos.r_1m_mv",
            "DROP TABLE IF EXISTS argos.r_1m");
    assertThat(clickHouse.executed)
        .anyMatch(
            s ->
                s.startsWith("INSERT INTO argos.r_1m_v2_backfill\nSELECT")
                    && s.contains("WHERE true AND (toDate(ts) = '" + today + "')"));
    assertThat(clickHouse.queries)
        .anyMatch(
            q -> q.contains("toDate(ts) BETWEEN '" + today.minusDays(2) + "' AND '" + today + "'"));
    assertThat(store.rows.keySet())
        .containsExactly(
            "V001",
            "V002",
            "M001.create_v2",
            "M001.backfill/" + day(1),
            "M001.backfill/" + day(0),
            "M001.backfill",
            "M001.switch",
            "M001.drop_old");
    assertThat(store.rows.values()).allMatch(HistoryEntry::success);
    assertThat(store.rollupAlias).containsExactly(Map.entry("r_1m", "r_1m_v2"));
    assertThat(result.applied())
        .extracting(ClickHouseMigrationRunner.Applied::version)
        .startsWith("V001", "V002", "M001.create_v2");
    assertThat(store.locks).isOne();
    assertThat(store.unlocks).isOne();
  }

  @Test
  @DisplayName("T-SV-001 a second run applies nothing and leaves the history unchanged")
  void secondRunIsNoOp() throws Exception {
    runner(full(), false).run();
    Map<String, HistoryEntry> before = new LinkedHashMap<>(store.rows);
    clickHouse.executed.clear();

    ClickHouseMigrationRunner.Result again = runner(full(), false).run();

    assertThat(again.applied()).isEmpty();
    assertThat(clickHouse.executed).isEmpty();
    assertThat(store.rows).isEqualTo(before);
  }

  @Test
  @DisplayName(
      "T-ST-052 a changed checksum of an applied script refuses the start, naming the version")
  void changedChecksumRefused() throws Exception {
    runner(full(), false).run();
    clickHouse.executed.clear();
    MigrationSource changed =
        full()
            .overlay(
                MigrationSource.of("x", Map.of("schema/V001__init.sql", V001 + "-- edited\n")));

    assertThatThrownBy(() -> runner(changed, false).run())
        .isInstanceOf(MigrationException.class)
        .hasMessageContaining("ClickHouse schema V001 was applied with checksum")
        .hasMessageContaining("argos.clickhouse.migration.repair-checksums=true");
    assertThat(clickHouse.executed).isEmpty();
    assertThat(store.unlocks).isEqualTo(store.locks);

    MigrationSource changedRollup =
        full()
            .overlay(
                MigrationSource.of(
                    "x", Map.of("rollups/r_1m.sql", ROLLUP.replace("count()", "count(*)"))));
    assertThatThrownBy(() -> runner(changedRollup, false).run())
        .hasMessageContaining("ClickHouse schema V001 was applied");
  }

  @Test
  @DisplayName(
      "T-ST-052 repair-checksums re-records the checksum after a WARN, writes a repair row and"
          + " runs nothing again")
  void repair() throws Exception {
    runner(full(), false).run();
    clickHouse.executed.clear();
    MigrationSource changed =
        full()
            .overlay(
                MigrationSource.of("x", Map.of("schema/V001__init.sql", V001 + "-- edited\n")));
    String newChecksum =
        MigrationCatalog.load(changed, new SchemaTemplates(changed)).scripts().get(0).checksum();

    ClickHouseMigrationRunner.Result result = runner(changed, true).run();

    assertThat(result.repaired()).containsExactly("V001");
    assertThat(clickHouse.executed).isEmpty();
    assertThat(store.rows.get("V001").checksum()).isEqualTo(newChecksum);
    assertThat(store.rows.keySet()).anyMatch(v -> v.matches("repair\\.\\d{14}"));
    assertThat(store.rows.values())
        .filteredOn(h -> h.version().startsWith("repair."))
        .singleElement()
        .satisfies(h -> assertThat(h.description()).isEqualTo("checksums re-recorded: V001"));
    // The next start without the setting is clean again.
    assertThat(runner(changed, false).run().applied()).isEmpty();
  }

  @Test
  @DisplayName(
      "T-ST-052 non-idempotent or forbidden statements refuse the start before anything runs")
  void lintBeforeAnything() {
    MigrationSource bad =
        source(
            Map.of("schema/V002__bad.sql", "CREATE TABLE {db}.{p}t (x UInt8) ENGINE = Memory;\n"));

    assertThatThrownBy(() -> runner(bad, false).run())
        .isInstanceOf(MigrationException.class)
        .hasMessageContaining("nothing was applied")
        .hasMessageContaining("schema/V002__bad.sql statement 1: CREATE without IF NOT EXISTS");
    assertThat(clickHouse.executed).as("V001 is pending too, but nothing ran").isEmpty();
    assertThat(store.rows).isEmpty();

    MigrationSource modify =
        source(
            Map.of(
                "schema/V002__type.sql",
                "ALTER TABLE {db}.{p}xlog MODIFY COLUMN app_id UInt64;\n"));
    assertThatThrownBy(() -> runner(modify, false).run())
        .hasMessageContaining("MODIFY COLUMN with a type change is forbidden");
  }

  @Test
  @DisplayName("T-ST-052 unknown placeholder refuses the start before anything runs (C-56)")
  void unknownPlaceholder() {
    MigrationSource bad =
        source(Map.of("schema/V002__bad.sql", "CREATE TABLE IF NOT EXISTS {db}.{p}t {codec.x};\n"));

    assertThatThrownBy(() -> runner(bad, false).run())
        .isInstanceOf(MigrationException.class)
        .hasMessageContaining("schema/V002__bad.sql: unknown placeholders [codec.x]");
    assertThat(clickHouse.executed).isEmpty();
    assertThat(store.locks).isZero();
  }

  @Test
  @DisplayName(
      "T-ST-052 unknown applied versions (newer server) and out-of-order versions are refused")
  void unknownAndOutOfOrder() throws Exception {
    store.record("V009", "future", "0".repeat(64), 1, true, null);
    assertThatThrownBy(() -> runner(full(), false).run())
        .hasMessageContaining("does not bundle: [V009]");

    store.rows.clear();
    runner(full(), false).run();
    store.rows.remove("V001");
    assertThatThrownBy(() -> runner(full(), false).run())
        .hasMessageContaining("[V001] are not applied although later versions are");
  }

  @Test
  @DisplayName(
      "a failed statement records a failed row with the error and is retried at the next start")
  void failureRecordedAndRetried() throws Exception {
    clickHouse.failOn = "ADD COLUMN IF NOT EXISTS note";

    assertThatThrownBy(() -> runner(full(), false).run())
        .hasMessageContaining("ClickHouse migration V002 (note) failed at statement 1/1")
        .hasMessageContaining("simulated failure");
    assertThat(store.rows.get("V001").success()).isTrue();
    assertThat(store.rows.get("V002").success()).isFalse();
    assertThat(store.rows.get("V002").error()).contains("simulated failure");
    assertThat(store.rows).doesNotContainKey("M001.create_v2");

    clickHouse.failOn = null;
    clickHouse.executed.clear();
    runner(full(), false).run();
    assertThat(clickHouse.executed.get(0)).contains("ADD COLUMN IF NOT EXISTS note");
    assertThat(store.rows.get("V002").success()).isTrue();
    assertThat(store.rows.get("V002").error()).isNull();
  }

  @Test
  @DisplayName(
      "T-ST-052 backfill resumes after the partitions with a progress row and leaves empty"
          + " partitions alone")
  void backfillResumes() throws Exception {
    MigrationSource source = full();
    MigrationCatalog catalog = MigrationCatalog.load(source, new SchemaTemplates(source));
    for (SchemaScript s : catalog.scripts()) {
      store.record(s.version(), s.description(), s.checksum(), 1, true, null);
    }
    Procedure.Step create = catalog.procedures().get(0).steps().get(0);
    Procedure.Step backfill = catalog.procedures().get(0).steps().get(1);
    store.record(create.version(), "", create.checksum(), 1, true, null);
    store.record(backfill.version() + "/" + day(2), "", backfill.checksum(), 1, true, null);
    clickHouse.answers =
        q -> {
          if (q.startsWith("SELECT DISTINCT")) {
            return List.of(day(2), day(1), day(0));
          }
          if (q.contains("count()) FROM argos.r_1m_v2_backfill")) {
            return List.of("0");
          }
          return List.of("1");
        };

    runner(source, false).run();

    List<String> inserts = new ArrayList<>();
    for (String s : clickHouse.executed) {
      if (s.startsWith("INSERT INTO argos.r_1m_v2_backfill")) {
        inserts.add(s);
      }
    }
    assertThat(inserts).hasSize(2).noneMatch(s -> s.contains("'" + today.minusDays(2) + "'"));
    assertThat(clickHouse.executed).noneMatch(s -> s.contains("REPLACE PARTITION"));
    assertThat(store.rows.get("M001.backfill/" + day(0)).description()).endsWith(": 0 rows");
    assertThat(store.rows.get("M001.backfill").success()).isTrue();
  }

  @Test
  @DisplayName("a missing ClickHouse database with history rows recreates the schema (13 §10)")
  void missingDatabaseRecreates() throws Exception {
    runner(full(), false).run();
    clickHouse.executed.clear();
    clickHouse.answers = q -> answer(q, "0", "1");

    ClickHouseMigrationRunner.Result result = runner(full(), false).run();

    assertThat(result.recreated()).isTrue();
    assertThat(clickHouse.executed.get(0)).startsWith("CREATE DATABASE IF NOT EXISTS argos");
    assertThat(store.rows.get("V001").success()).isTrue();
  }

  @Test
  @DisplayName("a switch to a table that does not exist is refused and recorded as failed")
  void switchTargetMissing() {
    clickHouse.answers = q -> answer(q, "1", "0");

    assertThatThrownBy(() -> runner(full(), false).run())
        .hasMessageContaining("switch target argos.r_1m_v2 does not exist");
    assertThat(store.rows.get("M001.switch").success()).isFalse();
    assertThat(store.rollupAlias).isEmpty();
  }

  @Test
  @DisplayName("an unexpected partition key from ClickHouse stops the backfill")
  void unexpectedPartitionKey() {
    clickHouse.answers = q -> List.of("1");

    assertThatThrownBy(() -> runner(full(), false).run())
        .hasMessageContaining("unexpected partition key '1'");
    assertThat(store.rows.get("M001.backfill").success()).isFalse();
  }

  /** Database count, table count, no backfill partitions, else "1". */
  private static List<String> answer(String query, String databases, String tables) {
    if (query.startsWith("SELECT DISTINCT")) {
      return List.of();
    }
    if (query.contains("system.databases")) {
      return List.of(databases);
    }
    if (query.contains("system.tables")) {
      return List.of(tables);
    }
    return List.of("1");
  }
}
