/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import static io.argos.server.ops.migration.MigrationApps.pg;
import static io.argos.server.ops.migration.MigrationApps.pgExecute;
import static io.argos.server.ops.migration.MigrationApps.pgScalar;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.bootstrap.PhaseOrder;
import io.argos.server.bootstrap.StartupAbortedException;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.storage.clickhouse.Account;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * T-ST-052 refusals against real PostgreSQL and ClickHouse: a changed ClickHouse checksum, a
 * non-idempotent statement, a placeholder outside C-56, a column type change and a changed Flyway
 * checksum each stop the start in their migration phase, before any listener, with a one-line
 * reason that names the version and contains no secret; nothing is applied. The one-shot checksum
 * repair is the documented way out.
 */
@Tag("req:N-22")
@Tag("req:DSN-12-15")
class MigrationRefusalIT {

  private static ServerTestEnvironment env;
  private static List<String> chHistory;

  @BeforeAll
  static void start() throws Exception {
    env = ServerTestEnvironment.start();
    try (ConfigurableApplicationContext ctx = MigrationApps.start(env)) {
      assertThat(ctx.getBean(ClickHouseMigrationPhase.class).lastResult().applied()).hasSize(1);
    }
    chHistory = history();
  }

  @AfterAll
  static void stop() {
    if (env != null) {
      env.close();
    }
  }

  @AfterEach
  void historyUnchangedByRefusals() throws Exception {
    // Every test restores what it changed; refusals never write history.
    assertThat(history()).isEqualTo(chHistory);
  }

  private static List<String> history() throws Exception {
    return pg(
        env,
        PgAccount.APP,
        "SELECT version || ' ' || checksum || ' ' || success FROM argos.ch_schema_history"
            + " WHERE version NOT LIKE 'repair.%' ORDER BY version");
  }

  private static void assertRefused(StartupAbortedException e, PhaseOrder phase, String... parts) {
    assertThat(e.phase()).isEqualTo(phase);
    assertThat(e.getMessage()).doesNotContain("\n").contains("The server does not start");
    for (String part : parts) {
      assertThat(e.getMessage()).contains(part);
    }
    for (String secret : MigrationApps.secrets(env)) {
      assertThat(e.getMessage()).doesNotContain(secret);
    }
  }

  @Test
  @DisplayName(
      "T-ST-052 a changed checksum of an applied ClickHouse script refuses the start, naming V001")
  void changedClickHouseChecksum() throws Exception {
    String original =
        pgScalar(
            env,
            PgAccount.OWNER,
            "SELECT checksum FROM argos.ch_schema_history WHERE version = 'V001'");
    pgExecute(
        env,
        PgAccount.OWNER,
        "UPDATE argos.ch_schema_history SET checksum = repeat('0', 64) WHERE version = 'V001'");
    try {
      StartupAbortedException e = MigrationApps.refused(env, null, Map.of());
      assertRefused(
          e,
          PhaseOrder.CLICKHOUSE_MIGRATION,
          "ClickHouse schema V001 was applied with checksum 000000000000...",
          "argos.clickhouse.migration.repair-checksums=true");
    } finally {
      pgExecute(
          env,
          PgAccount.OWNER,
          "UPDATE argos.ch_schema_history SET checksum = '"
              + original
              + "' WHERE version = 'V001'");
    }
  }

  @Test
  @DisplayName(
      "T-ST-052 a changed bundled file is refused; repair-checksums re-records it once (WARN +"
          + " repair row) and the original file needs a repair back")
  void changedFileAndRepair() throws Exception {
    MigrationSource bundled = MigrationApps.bundled();
    MigrationSource changed =
        bundled.overlay(
            MigrationSource.of(
                "edited",
                Map.of(
                    "schema/V001__init.sql",
                    bundled.read("schema/V001__init.sql").orElseThrow()
                        + "\n-- edited after release\n")));

    assertRefused(
        MigrationApps.refused(env, changed, Map.of()),
        PhaseOrder.CLICKHOUSE_MIGRATION,
        "ClickHouse schema V001 was applied");

    try (ConfigurableApplicationContext ctx =
        MigrationApps.start(env, changed, Map.of(ClickHouseMigrationPhase.REPAIR_KEY, "true"))) {
      ClickHouseMigrationRunner.Result r = ctx.getBean(ClickHouseMigrationPhase.class).lastResult();
      assertThat(r.repaired()).containsExactly("V001");
      assertThat(r.applied()).isEmpty();
    }
    assertThat(
            pgScalar(
                env,
                PgAccount.APP,
                "SELECT description FROM argos.ch_schema_history WHERE version LIKE 'repair.%'"
                    + " ORDER BY applied_at DESC LIMIT 1"))
        .isEqualTo("checksums re-recorded: V001");
    try (ConfigurableApplicationContext ctx = MigrationApps.start(env, changed, Map.of())) {
      assertThat(ctx.getBean(ClickHouseMigrationPhase.class).lastResult().applied()).isEmpty();
    }
    assertRefused(
        MigrationApps.refused(env, null, Map.of()), PhaseOrder.CLICKHOUSE_MIGRATION, "V001");
    try (ConfigurableApplicationContext ctx =
        MigrationApps.start(env, null, Map.of(ClickHouseMigrationPhase.REPAIR_KEY, "true"))) {
      assertThat(ctx.getBean(ClickHouseMigrationPhase.class).lastResult().repaired())
          .containsExactly("V001");
    }
  }

  @Test
  @DisplayName(
      "T-ST-052 a non-idempotent statement refuses the start before any statement of the script"
          + " runs")
  void nonIdempotent() throws Exception {
    StartupAbortedException e =
        MigrationApps.refused(env, MigrationApps.withFixture("bad-idempotency"), Map.of());

    assertRefused(
        e,
        PhaseOrder.CLICKHOUSE_MIGRATION,
        "nothing was applied",
        "schema/V002__fixture_not_idempotent.sql statement 2: CREATE without IF NOT EXISTS");
    assertThat(
            MigrationApps.chCount(
                env,
                "SELECT count() FROM system.tables WHERE database = 'argos' AND name LIKE 'fixture%'"))
        .as("the valid first statement did not run either")
        .isZero();
  }

  @Test
  @DisplayName("T-ST-052 a placeholder outside C-56 refuses the start, naming the file")
  void unknownPlaceholder() {
    assertRefused(
        MigrationApps.refused(env, MigrationApps.withFixture("bad-placeholder"), Map.of()),
        PhaseOrder.CLICKHOUSE_MIGRATION,
        "schema/V002__fixture_unknown_placeholder.sql: unknown placeholders [codec.default]");
  }

  @Test
  @DisplayName("T-ST-052 a MODIFY COLUMN type change refuses the start")
  void typeChange() {
    assertRefused(
        MigrationApps.refused(env, MigrationApps.withFixture("bad-type-change"), Map.of()),
        PhaseOrder.CLICKHOUSE_MIGRATION,
        "MODIFY COLUMN with a type change is forbidden");
    assertThat(
            env.clickHouse(Account.READER)
                .scalar(
                    "SELECT type FROM system.columns WHERE database = 'argos' AND table = 'xlog'"
                        + " AND name = 'sql_count'"))
        .isEqualTo("UInt16");
  }

  @Test
  @DisplayName(
      "T-ST-052 a changed Flyway checksum refuses the start in POSTGRES_MIGRATION, before"
          + " ClickHouse")
  void changedFlywayChecksum() throws Exception {
    pgExecute(
        env,
        PgAccount.OWNER,
        "UPDATE argos.flyway_schema_history SET checksum = checksum + 1 WHERE version = '001'");
    try {
      assertRefused(
          MigrationApps.refused(env, null, Map.of()),
          PhaseOrder.POSTGRES_MIGRATION,
          "PostgreSQL schema validation failed, nothing was changed",
          "checksum mismatch for migration version 001");
    } finally {
      pgExecute(
          env,
          PgAccount.OWNER,
          "UPDATE argos.flyway_schema_history SET checksum = checksum - 1 WHERE version = '001'");
    }
  }

  @Test
  @DisplayName(
      "argos.retention.stat != argos.retention.metric refuses the start (one raw retention)")
  void retentionMismatch() {
    assertRefused(
        MigrationApps.refused(env, null, Map.of("argos.retention.stat", "7d")),
        PhaseOrder.CLICKHOUSE_MIGRATION,
        "argos.retention.stat (7d) and argos.retention.metric (14d) must be equal");
  }
}
