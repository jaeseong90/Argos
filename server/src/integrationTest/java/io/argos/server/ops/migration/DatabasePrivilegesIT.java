/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import static io.argos.server.ops.migration.MigrationApps.pg;
import static io.argos.server.ops.migration.MigrationApps.pgExecute;
import static io.argos.server.ops.migration.MigrationApps.pgScalar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseException;
import io.argos.storage.clickhouse.ClickHouseHttp;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * T-SEC-76: least privilege of the database accounts after the server has migrated both databases
 * (C-11, ADR-0010 C-97, 13 §7.3, ADR-0007 C-60, DSN-12-13). SQLSTATE 42501 is PostgreSQL's
 * insufficient_privilege, code 497 ClickHouse's ACCESS_DENIED: a refusal must carry that reason.
 */
@Tag("req:SEC-76")
@Tag("req:SEC-82")
class DatabasePrivilegesIT {

  private static final String DENIED = "42501";

  /** ClickHouse error code ACCESS_DENIED. */
  private static final int CH_ACCESS_DENIED = 497;

  private static ServerTestEnvironment env;

  @BeforeAll
  static void start() {
    env = ServerTestEnvironment.start();
    try (ConfigurableApplicationContext ctx = MigrationApps.start(env)) {
      assertThat(ctx.isActive()).isTrue();
    }
  }

  @AfterAll
  static void stop() {
    if (env != null) {
      env.close();
    }
  }

  private static void denied(PgAccount account, String sql) {
    assertThatThrownBy(() -> pgExecute(env, account, sql))
        .as(account.user() + ": " + sql)
        .isInstanceOf(SQLException.class)
        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(DENIED));
  }

  private static String currentPartition(String table) {
    return table
        + "_p"
        + DateTimeFormatter.ofPattern("yyyyMM", Locale.ROOT).format(LocalDate.now(ZoneOffset.UTC));
  }

  @Test
  @DisplayName(
      "T-SEC-76 roles: LOGIN only, no superuser, createdb, createrole, replication, bypassrls")
  void roleAttributes() throws Exception {
    assertThat(
            pg(
                env,
                PgAccount.APP,
                "SELECT rolname || ':' || rolcanlogin::text || rolsuper::text || rolcreatedb::text"
                    + " || rolcreaterole::text || rolreplication::text || rolbypassrls::text"
                    + " FROM pg_roles"
                    + " WHERE rolname IN ('argos', 'argos_app', 'argos_audit', 'argos_backup')"
                    + " ORDER BY 1"))
        .containsExactly(
            "argos:truefalsefalsefalsefalsefalse",
            "argos_app:truefalsefalsefalsefalsefalse",
            "argos_audit:truefalsefalsefalsefalsefalse",
            "argos_backup:truefalsefalsefalsefalsefalse");
  }

  @Test
  @DisplayName(
      "T-SEC-76 argos_app: DML works; CREATE, ALTER, DROP, TEMP and writes to the histories fail")
  void appCannotChangeTheSchema() throws Exception {
    pgExecute(
        env,
        PgAccount.APP,
        "INSERT INTO argos.settings (key, value) VALUES ('t-sec-76', '{}') ON CONFLICT (key) DO"
            + " UPDATE SET value = '{}'");
    pgExecute(env, PgAccount.APP, "DELETE FROM argos.settings WHERE key = 't-sec-76'");
    denied(PgAccount.APP, "CREATE TABLE argos.t_sec_76 (x int)");
    denied(PgAccount.APP, "CREATE TABLE public.t_sec_76 (x int)");
    denied(PgAccount.APP, "CREATE TEMP TABLE t_sec_76 (x int)");
    denied(PgAccount.APP, "ALTER TABLE argos.users ADD COLUMN t_sec_76 int");
    denied(PgAccount.APP, "DROP TABLE argos.settings");
    denied(PgAccount.APP, "TRUNCATE argos.settings");
    denied(PgAccount.APP, "CREATE INDEX ix_t_sec_76 ON argos.users (display_name)");
    denied(PgAccount.APP, "UPDATE argos.ch_schema_history SET success = true");
    denied(PgAccount.APP, "DELETE FROM argos.flyway_schema_history");
    denied(
        PgAccount.APP,
        "SELECT count(*) FROM argos.maintain_monthly_partitions('argos.audit_logs', 2, NULL)");
    denied(PgAccount.APP, "SELECT count(*) FROM argos.audit_logs_maintain_partitions(2, NULL)");
  }

  @Test
  @DisplayName(
      "T-SEC-76 audit_logs: argos_app inserts and reads; UPDATE, DELETE, TRUNCATE fail, also on"
          + " the partitions")
  void auditLogsAppendOnly() throws Exception {
    pgExecute(
        env,
        PgAccount.APP,
        "INSERT INTO argos.audit_logs (actor_type, action, result) VALUES ('system', 'T_SEC_76', 'SUCCESS')");
    assertThat(
            pgScalar(
                env,
                PgAccount.APP,
                "SELECT count(*) FROM argos.audit_logs WHERE action = 'T_SEC_76'"))
        .isEqualTo("1");
    denied(PgAccount.APP, "UPDATE argos.audit_logs SET reason = 'x'");
    denied(PgAccount.APP, "DELETE FROM argos.audit_logs");
    denied(PgAccount.APP, "TRUNCATE argos.audit_logs");
    String partition = "argos." + currentPartition("audit_logs");
    denied(PgAccount.APP, "UPDATE " + partition + " SET reason = 'x'");
    denied(PgAccount.APP, "DELETE FROM " + partition);
    denied(PgAccount.APP, "SELECT count(*) FROM " + partition);
  }

  @Test
  @DisplayName(
      "T-SEC-76 argos_backup reads every table and partition, cannot write or create anything")
  void backupReadOnly() throws Exception {
    List<String> tables =
        pg(
            env,
            PgAccount.OWNER,
            "SELECT 'argos.' || c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE n.nspname = 'argos' AND c.relkind IN ('r', 'p') ORDER BY 1");
    assertThat(tables).hasSizeGreaterThan(MigrationStartupIT.PG_TABLES.size());
    for (String table : tables) {
      pgScalar(env, PgAccount.BACKUP, "SELECT count(*) FROM " + table);
    }
    denied(PgAccount.BACKUP, "INSERT INTO argos.settings (key, value) VALUES ('x', '{}')");
    denied(PgAccount.BACKUP, "UPDATE argos.users SET enabled = false");
    denied(PgAccount.BACKUP, "DELETE FROM argos.events");
    denied(
        PgAccount.BACKUP,
        "INSERT INTO argos.audit_logs (actor_type, action, result) VALUES ('system', 'X', 'X')");
    denied(PgAccount.BACKUP, "CREATE TABLE argos.t_sec_76 (x int)");
  }

  @Test
  @DisplayName("argos_audit: no table access; drops only expired whole months through its function")
  void auditRetentionRole() throws Exception {
    denied(PgAccount.AUDIT, "SELECT count(*) FROM argos.audit_logs");
    denied(PgAccount.AUDIT, "DELETE FROM argos.audit_logs");
    // An expired month (14 months back, relative to today) with one row.
    LocalDate month = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).minusMonths(14);
    String name = "audit_logs_p" + DateTimeFormatter.ofPattern("yyyyMM", Locale.ROOT).format(month);
    pgExecute(
        env,
        PgAccount.OWNER,
        "CREATE TABLE argos."
            + name
            + " PARTITION OF argos.audit_logs FOR VALUES FROM ('"
            + month
            + " 00:00:00+00') TO ('"
            + month.plusMonths(1)
            + " 00:00:00+00')");
    pgExecute(
        env,
        PgAccount.APP,
        "INSERT INTO argos.audit_logs (ts, actor_type, action, result) VALUES ('"
            + month.plusDays(3)
            + " 12:00:00+00', 'system', 'OLD', 'SUCCESS')");

    assertThat(
            pg(
                env,
                PgAccount.AUDIT,
                "SELECT action || ' ' || partition_name FROM"
                    + " argos.audit_logs_maintain_partitions(2, now() - interval '365 days')"))
        .containsExactly("dropped " + name);
    assertThat(pgScalar(env, PgAccount.OWNER, "SELECT to_regclass('argos." + name + "') IS NULL"))
        .isEqualTo("t");
    assertThatThrownBy(
            () ->
                pg(
                    env,
                    PgAccount.AUDIT,
                    "SELECT * FROM argos.audit_logs_maintain_partitions(2, now())"))
        .hasMessageContaining("never dropped");
    assertThat(
            pgScalar(
                env,
                PgAccount.OWNER,
                "SELECT count(*) FROM pg_inherits WHERE inhparent = 'argos.audit_logs'::regclass"))
        .isEqualTo("3");
  }

  /**
   * The statement fails with ClickHouse's ACCESS_DENIED (497), not with any other error (a missing
   * table or a syntax error would also "fail" without proving the privilege is missing).
   */
  private static void chDenied(ClickHouseHttp account, String sql) {
    Optional<ClickHouseException> failure = account.failure(sql);
    assertThat(failure).as(account.user() + ": " + sql + " must fail").isPresent();
    ClickHouseException e = failure.orElseThrow();
    assertThat(e.code())
        .as(
            account.user()
                + ": "
                + sql
                + " must fail with "
                + CH_ACCESS_DENIED
                + " (ACCESS_DENIED), got "
                + e.code()
                + " ("
                + e.errorName()
                + ")")
        .isEqualTo(CH_ACCESS_DENIED);
  }

  @Test
  @DisplayName(
      "T-SEC-76 ClickHouse: argos_reader cannot run DDL or insert; argos_writer cannot run DDL or"
          + " insert into a rollup (C-60); every refusal is ACCESS_DENIED")
  void clickHouseAccounts() {
    ClickHouseHttp reader = env.clickHouse(Account.READER);
    for (String sql :
        List.of(
            "CREATE TABLE argos.t_sec_76 (x UInt8) ENGINE = Memory",
            "ALTER TABLE argos.xlog ADD COLUMN IF NOT EXISTS t_sec_76 UInt8",
            "DROP TABLE argos.app_stat_1m",
            "DROP TABLE argos.xlog",
            "INSERT INTO argos.metric (app_id) VALUES (1)")) {
      chDenied(reader, sql);
    }
    ClickHouseHttp writer = env.clickHouse(Account.WRITER);
    chDenied(writer, "CREATE TABLE argos.t_sec_76 (x UInt8) ENGINE = Memory");
    chDenied(writer, "DROP TABLE argos.app_stat_1m");
    chDenied(writer, "INSERT INTO argos.app_stat_1m (app_id) VALUES (1)");
    assertThat(
            MigrationApps.chCount(
                env, "SELECT count() FROM system.tables WHERE database = 'argos'"))
        .isEqualTo(29);
  }
}
