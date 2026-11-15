/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseHttp;
import io.argos.storage.generator.Generator;
import io.argos.storage.generator.GeneratorConfig;
import io.argos.storage.generator.RunRange;
import io.argos.storage.generator.load.ClickHouseInserter;
import io.argos.storage.generator.rows.Table;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The incremental migration path of 13 §13.2 (T-ST-052 path 2, T-SV-002): the bundled scripts (V001
 * and the V002 ingest columns of ADR-0010 C-144) on empty databases, generator data in every raw
 * table (rollups filled by the views), then the next-version fixture ({@code
 * migration-fixtures/upgrade-v003}: V003 with {@code ADD COLUMN IF NOT EXISTS}, the M001 procedure
 * that replaces the 1m rollup {@code instance_stat_1m} in four steps with a daily backfill, and the
 * M002 procedure that replaces the 1h rollup {@code app_stat_1h} with a monthly backfill). Row
 * counts must be the same before and after, the new rollups must equal their source per minute and
 * per hour (D-03 form), D-12 must hold before and after (Q13 form), {@code settings.rollup_alias}
 * must point at the new tables, every script, step and backfill partition must finish within 10
 * minutes, and a further start applies nothing.
 *
 * <p>Generated rows are placed relative to the current time (the generator's default range ends
 * before now and crosses the last UTC midnight), so they are inside every retention whatever the
 * date of the run (WO-03F anchor-date rule).
 */
final class SchemaUpgradeScenario {

  private SchemaUpgradeScenario() {}

  /**
   * Runs the scenario.
   *
   * @param profile generator profile ({@code tiny} 1/1000, {@code ci} 1/100)
   */
  static void run(ServerTestEnvironment env, String profile, MigrationTimings timings)
      throws Exception {
    ClickHouseHttp ch = env.clickHouse(Account.MIGRATOR);
    try (ConfigurableApplicationContext ctx = MigrationApps.start(env)) {
      timings.flyway("empty", ctx.getBean(PostgresMigrationPhase.class).lastResult());
      timings.clickHouse("empty", ctx.getBean(ClickHouseMigrationPhase.class).lastResult());
    }

    GeneratorConfig config =
        GeneratorConfig.defaults()
            .with("profile", profile)
            // 50,000 rows per block: the batch writer size of 13 §7.4, and a small test heap.
            .with("block-rows", "50000")
            .with("monitor-interval-s", "30");
    long loadStart = System.nanoTime();
    Generator.Result data =
        new Generator(
                config,
                new ClickHouseInserter(
                    env.clickHouse(Account.WRITER), config.database, config.tablePrefix),
                null,
                new PrintStream(System.out, true, StandardCharsets.UTF_8))
            .run(RunRange.bulk(config, System.currentTimeMillis()), null);
    Map<String, Long> rawBefore = rawCounts(ch);
    long xlog = rawBefore.get("xlog");
    timings.row(
        profile + " data",
        "ClickHouse",
        "generator bulk load (argos_writer, not a migration)",
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - loadStart),
        String.format(
            Locale.ROOT, "%,d xlog rows, %,d rows in all raw tables", xlog, sum(rawBefore)));
    for (Table table : Table.ALL) {
      assertThat(rawBefore.get(table.name()))
          .as(table.name())
          .isEqualTo(data.manifest().rows(table))
          .isPositive();
    }
    assertThat(ch.scalarLong("SELECT sum(count) FROM argos.instance_stat_1m")).isEqualTo(xlog);
    assertThat(ch.scalarLong("SELECT sum(count) FROM argos.app_stat_1h")).isEqualTo(xlog);
    assertD12(ch, data, "before the upgrade");

    ClickHouseMigrationRunner.Result upgrade;
    long upgradeStart = System.nanoTime();
    try (ConfigurableApplicationContext ctx =
        MigrationApps.start(env, MigrationApps.withFixture("upgrade-v003"), Map.of())) {
      upgrade = ctx.getBean(ClickHouseMigrationPhase.class).lastResult();
      assertThat(ctx.getBean(PostgresMigrationPhase.class).lastResult().migrationsExecuted())
          .isZero();
    }
    timings.clickHouse(profile + " V001 + data -> next version", upgrade);
    timings.row(
        profile + " V001 + data -> next version",
        "server",
        "whole start with the upgrade",
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - upgradeStart),
        "");

    List<String> versions =
        upgrade.applied().stream().map(ClickHouseMigrationRunner.Applied::version).toList();
    // Scripts first, then the procedures in number and step order; backfill progress rows (one per
    // day of M001, one per month of M002) come right before their step.
    assertThat(versions).startsWith("V003", "M001.create_v2");
    assertThat(versions.stream().filter(v -> !v.contains("/")).toList())
        .containsExactly(
            "V003",
            "M001.create_v2",
            "M001.backfill",
            "M001.switch",
            "M001.drop_old",
            "M002.create_v2",
            "M002.backfill",
            "M002.switch",
            "M002.drop_old");
    assertThat(versions).endsWith("M002.backfill", "M002.switch", "M002.drop_old");
    assertThat(versions)
        .filteredOn(v -> v.startsWith("M001.backfill/"))
        .isNotEmpty()
        .allMatch(v -> v.matches("M001\\.backfill/\\d{8}"));
    assertThat(versions)
        .filteredOn(v -> v.startsWith("M002.backfill/"))
        .as("monthly backfill progress rows (M002.backfill/<yyyyMM>)")
        .isNotEmpty()
        .allMatch(v -> v.matches("M002\\.backfill/\\d{6}"));
    assertThat(
            versions.subList(
                versions.indexOf("M002.create_v2") + 1, versions.indexOf("M002.backfill")))
        .allMatch(v -> v.startsWith("M002.backfill/"));
    // Every script, step and backfill partition (M001 and M002) within 10 minutes (13 §13.2).
    assertThat(upgrade.applied())
        .extracting(ClickHouseMigrationRunner.Applied::version)
        .contains("M002.create_v2", "M002.backfill", "M002.switch", "M002.drop_old");
    for (ClickHouseMigrationRunner.Applied a : upgrade.applied()) {
      assertThat(a.durationMs())
          .as(a.version() + " within 10 minutes")
          .isLessThanOrEqualTo(MigrationTimings.LIMIT_MS);
    }

    // Counts are unchanged; the new columns exist.
    assertThat(rawCounts(ch)).isEqualTo(rawBefore);
    assertThat(
            ch.scalar(
                "SELECT groupArray(name) FROM (SELECT name FROM system.columns WHERE database = 'argos'"
                    + " AND name IN ('fixture_v002', 'fixture_unit') ORDER BY name)"))
        .isEqualTo("['fixture_unit','fixture_v002']");

    // The backfilled rollup equals the source per minute (D-03 form, ADR-0007 C-65).
    assertThat(ch.scalarLong("SELECT sum(count) FROM argos.instance_stat_1m_v2")).isEqualTo(xlog);
    assertThat(
            ch.scalarLong(
                "SELECT count() FROM (SELECT m, sumIf(c, src = 'xlog') AS xc, sumIf(c, src = 'v2') AS vc"
                    + " FROM (SELECT 'xlog' AS src, toStartOfMinute(ts) AS m, count() AS c FROM argos.xlog"
                    + " GROUP BY m UNION ALL SELECT 'v2', minute, sum(count) FROM argos.instance_stat_1m_v2"
                    + " GROUP BY minute) GROUP BY m HAVING xc != vc)"))
        .as("minutes where instance_stat_1m_v2 differs from xlog")
        .isZero();
    assertThat(
            ch.scalarLong(
                "SELECT count() FROM argos.instance_stat_1m_v2 FINAL WHERE max_cpu_time_ms != 0"))
        .as("the new measure is filled")
        .isPositive();

    // M002 (monthly backfill of a 1h table from its 1m source): every hour of app_stat_1h_v2 equals
    // the source app_stat_1m summed per hour (D-03 form, C-65), and the total is the xlog count.
    assertThat(ch.scalarLong("SELECT sum(count) FROM argos.app_stat_1h_v2")).isEqualTo(xlog);
    assertThat(
            ch.scalarLong(
                "SELECT count() FROM (SELECT h, sumIf(c, src = '1m') AS mc, sumIf(c, src = 'v2') AS vc"
                    + " FROM (SELECT '1m' AS src, toStartOfHour(minute) AS h, sum(count) AS c"
                    + " FROM argos.app_stat_1m GROUP BY h UNION ALL SELECT 'v2', hour, sum(count)"
                    + " FROM argos.app_stat_1h_v2 GROUP BY hour) GROUP BY h HAVING mc != vc)"))
        .as("hours where app_stat_1h_v2 differs from app_stat_1m")
        .isZero();

    // Step 4: the old views and tables are gone, the new views and no staging table remain.
    assertThat(
            ch.rows(
                "SELECT name FROM system.tables WHERE database = 'argos' AND name LIKE 'instance_stat%'"
                    + " ORDER BY name"))
        .extracting(r -> r.get(0))
        .containsExactly("instance_stat_1m_v2", "instance_stat_1m_v2_mv");
    assertThat(
            ch.rows(
                "SELECT name FROM system.tables WHERE database = 'argos' AND name LIKE 'app_stat_1h%'"
                    + " ORDER BY name"))
        .extracting(r -> r.get(0))
        .containsExactly("app_stat_1h_v2", "app_stat_1h_v2_mv");

    // Step 3: the query layer's aliases, readable by the runtime account.
    assertThat(
            MigrationApps.pgScalar(
                env,
                PgAccount.APP,
                "SELECT value ->> 'instance_stat_1m' FROM argos.settings WHERE key = 'rollup_alias'"))
        .isEqualTo("instance_stat_1m_v2");
    assertThat(
            MigrationApps.pgScalar(
                env,
                PgAccount.APP,
                "SELECT value ->> 'app_stat_1h' FROM argos.settings WHERE key = 'rollup_alias'"))
        .isEqualTo("app_stat_1h_v2");
    assertThat(
            MigrationApps.pg(
                env,
                PgAccount.APP,
                "SELECT version FROM argos.ch_schema_history WHERE NOT success"))
        .isEmpty();
    assertThat(
            MigrationApps.pg(
                env,
                PgAccount.APP,
                "SELECT version FROM argos.ch_schema_history"
                    + " WHERE success AND version ~ '^M002[.]backfill/[0-9]{6}$'"))
        .as("monthly backfill progress rows in ch_schema_history")
        .isNotEmpty();

    // D-12 still holds after the upgrade (the upgrade touches no statistics table).
    assertD12(ch, data, "after the upgrade");

    // Once applied, a further start applies nothing.
    try (ConfigurableApplicationContext ctx =
        MigrationApps.start(env, MigrationApps.withFixture("upgrade-v003"), Map.of())) {
      assertThat(ctx.getBean(ClickHouseMigrationPhase.class).lastResult().applied()).isEmpty();
    }
    timings.note(
        String.format(
            Locale.ROOT,
            "Generator profile %s (scale %s): %,d xlog rows over %s; data range relative to the run time.",
            profile,
            GeneratorConfig.PROFILES.get(profile),
            xlog,
            java.time.Duration.ofMillis(data.range().end() - data.range().start())));
  }

  /**
   * D-12 in the form of Q13 {@code total_30d} (test/storage/queries, ADR-0007 C-65; the whole data
   * lies inside every retention, so no range): per (app, kind), sum(xlog.sql_count) =
   * sum(sql_stat_1m.count) and sum(xlog.ext_count) = sum(ext_stat_1m.count); 0 disagreeing groups,
   * and the totals equal the generator manifest.
   */
  private static void assertD12(ClickHouseHttp ch, Generator.Result data, String when) {
    assertThat(
            ch.scalarLong(
                "SELECT count() FROM (SELECT app_id, kind,"
                    + " sumIf(c, src = 'xlog_sql') AS xlog_sql, sumIf(c, src = 'sql_1m') AS sql_1m,"
                    + " sumIf(c, src = 'xlog_ext') AS xlog_ext, sumIf(c, src = 'ext_1m') AS ext_1m"
                    + " FROM (SELECT 'xlog_sql' AS src, app_id, kind, sum(sql_count) AS c"
                    + " FROM argos.xlog GROUP BY app_id, kind"
                    + " UNION ALL SELECT 'xlog_ext', app_id, kind, sum(ext_count)"
                    + " FROM argos.xlog GROUP BY app_id, kind"
                    + " UNION ALL SELECT 'sql_1m', app_id, kind, sum(count)"
                    + " FROM argos.sql_stat_1m GROUP BY app_id, kind"
                    + " UNION ALL SELECT 'ext_1m', app_id, kind, sum(count)"
                    + " FROM argos.ext_stat_1m GROUP BY app_id, kind)"
                    + " GROUP BY app_id, kind HAVING xlog_sql != sql_1m OR xlog_ext != ext_1m)"))
        .as("D-12 " + when + ": (app, kind) groups where xlog and sql/ext_stat_1m disagree")
        .isZero();
    assertThat(ch.scalarLong("SELECT sum(sql_count) FROM argos.xlog"))
        .as("D-12 " + when + ": sum(xlog.sql_count) = sum(sql_stat_1m.count)")
        .isEqualTo(ch.scalarLong("SELECT sum(count) FROM argos.sql_stat_1m"))
        .isEqualTo(data.manifest().xlogSqlCount())
        .isPositive();
    assertThat(ch.scalarLong("SELECT sum(ext_count) FROM argos.xlog"))
        .as("D-12 " + when + ": sum(xlog.ext_count) = sum(ext_stat_1m.count)")
        .isEqualTo(ch.scalarLong("SELECT sum(count) FROM argos.ext_stat_1m"))
        .isEqualTo(data.manifest().xlogExtCount())
        .isPositive();
  }

  private static Map<String, Long> rawCounts(ClickHouseHttp ch) {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (Table table : Table.ALL) {
      counts.put(table.name(), ch.scalarLong("SELECT count() FROM argos." + table.name()));
    }
    return counts;
  }

  private static long sum(Map<String, Long> counts) {
    long n = 0;
    for (long v : counts.values()) {
      n += v;
    }
    return n;
  }
}
