/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import static io.argos.server.ops.migration.MigrationApps.pg;
import static io.argos.server.ops.migration.MigrationApps.pgScalar;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.bootstrap.StartupSequence;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseHttp;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The server on empty PostgreSQL 16 and ClickHouse 26.8 (Testcontainers, pinned images): the
 * migration phases create every object of PostgreSQL V001 and ClickHouse V001, a second start
 * applies nothing (T-SV-001), the owner connections are closed after the phases and the runtime
 * pool runs as {@code argos_app} (C-11).
 */
@Tag("req:N-22")
@Tag("req:DSN-12-15")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationStartupIT {

  /**
   * The PostgreSQL scripts of db/pg: V001 (13 §12, WO-20), V002 (authentication, WO-24) and V003
   * (dict_kind 'pool', ADR-0010 C-101, and apps.registered_from_app_id, C-155; WO-21), V004
   * (pending_registrations.home_app_id, WO-22), V005 ({@code reconcile_results}, WO-27) and V006
   * ({@code service_apdex_overrides}, WO-27), V007 (alert engine: {@code alert_states}, {@code
   * mutes}, WO-25), V008 (alert channels: {@code webhook_signing_keys}, WO-25) and V009 ({@code
   * alert_events.reason}, ADR-0010 C-227 (3), WO-38), V010 (agent token revocation columns), V011
   * (commands and config changes) and V012 (app management: {@code apps.app_packages}, soft delete,
   * WO-26).
   */
  static final List<String> PG_VERSIONS =
      List.of("001", "002", "003", "004", "005", "006", "007", "008", "009", "010", "011", "012");

  /**
   * The bundled ClickHouse scripts: V001, V002 (ingest columns, ADR-0010 C-144, WO-21) and V003
   * ({@code instance_stat_1h}, C-212 (1), WO-27).
   */
  static final List<String> CH_VERSIONS = List.of("V001", "V002", "V003");

  /**
   * The 13 §12 tables of V001 (ADR-0010 C-98) and {@code user_permissions} and {@code
   * login_failures_unknown} of V002 (WO-24), {@code reconcile_results} of V005 and {@code
   * service_apdex_overrides} of V006 (WO-27), {@code alert_states} and {@code mutes} of V007 and
   * {@code webhook_signing_keys} of V008 (WO-25) and {@code app_config} of V011 (WO-26).
   */
  static final List<String> PG_TABLES =
      List.of(
          "agent_tokens",
          "alert_events",
          "alert_rule_history",
          "alert_rules",
          "alert_states",
          "app_config",
          "apps",
          "audit_logs",
          "ch_schema_history",
          "command_results",
          "commands",
          "config_change_results",
          "config_changes",
          "dictionary",
          "error_groups",
          "events",
          "instances",
          "login_failures_unknown",
          "mutes",
          "notification_channels",
          "notification_deliveries",
          "pending_registrations",
          "privacy_jobs",
          "reconcile_results",
          "service_apdex_overrides",
          "settings",
          "user_permissions",
          "user_sessions",
          "users",
          "webhook_signing_keys");

  private static final Pattern CREATED =
      Pattern.compile(
          "^CREATE (?:TABLE|MATERIALIZED VIEW) IF NOT EXISTS argos\\.([a-z0-9_]+)",
          Pattern.MULTILINE);

  /** The start steps of the migration lane (other test configurations may add probe steps). */
  private static final Set<String> MIGRATION_STEPS =
      Set.of("postgres-flyway", "postgres-runtime-pool", "clickhouse-migration");

  private static ServerTestEnvironment env;
  private static final MigrationTimings TIMINGS =
      new MigrationTimings(
          "path-1-empty", "T-ST-052 path 1 and T-SV-001: empty databases, two starts");

  @BeforeAll
  static void startDatabases() {
    env = ServerTestEnvironment.start();
  }

  @AfterAll
  static void stopDatabases() {
    if (env != null) {
      env.close();
    }
    TIMINGS.write();
  }

  @Test
  @Order(1)
  @DisplayName(
      "T-SV-001/T-ST-052 empty databases: the first start creates every V001 object, the second"
          + " applies nothing and leaves both histories unchanged")
  void twoStartsOnEmptyDatabases() throws Exception {
    List<String> flywayBefore;
    List<String> chBefore;
    try (ConfigurableApplicationContext ctx = MigrationApps.start(env)) {
      PostgresMigrationPhase.Result flyway = ctx.getBean(PostgresMigrationPhase.class).lastResult();
      ClickHouseMigrationRunner.Result ch =
          ctx.getBean(ClickHouseMigrationPhase.class).lastResult();
      assertThat(flyway.migrationsExecuted()).isEqualTo(PG_VERSIONS.size());
      assertThat(flyway.schemaVersion()).isEqualTo(PG_VERSIONS.get(PG_VERSIONS.size() - 1));
      assertThat(ch.applied())
          .extracting(ClickHouseMigrationRunner.Applied::version)
          .containsExactlyElementsOf(CH_VERSIONS);
      TIMINGS.flyway("empty, start 1", flyway);
      TIMINGS.clickHouse("empty, start 1", ch);
      for (StartupSequence.StepResult r : ctx.getBean(StartupSequence.class).results()) {
        if (MIGRATION_STEPS.contains(r.name())) {
          TIMINGS.row(
              "empty, start 1",
              "phase",
              r.phase() + " " + r.name(),
              r.durationMillis(),
              "start step");
        }
      }
      assertThat(ctx.getBeanDefinitionNames())
          .as("no Flyway bean or auto-configuration: Flyway runs in the phase only")
          .noneMatch(n -> n.toLowerCase(Locale.ROOT).contains("flyway"));

      assertPostgresSchema();
      assertAlertEventReason();
      assertClickHouseSchema();
      assertConnections(ctx);
      flywayBefore = flywayHistory();
      chBefore = chHistory();
    }

    long start = System.nanoTime();
    try (ConfigurableApplicationContext ctx = MigrationApps.start(env)) {
      long ms = (System.nanoTime() - start) / 1_000_000;
      assertThat(ctx.getBean(PostgresMigrationPhase.class).lastResult().migrationsExecuted())
          .isZero();
      ClickHouseMigrationRunner.Result ch =
          ctx.getBean(ClickHouseMigrationPhase.class).lastResult();
      assertThat(ch.applied()).isEmpty();
      assertThat(ch.recreated()).isFalse();
      assertThat(flywayHistory()).isEqualTo(flywayBefore);
      assertThat(chHistory()).isEqualTo(chBefore);
      for (StartupSequence.StepResult r : ctx.getBean(StartupSequence.class).results()) {
        if (MIGRATION_STEPS.contains(r.name())) {
          TIMINGS.row(
              "empty, start 2",
              "phase",
              r.phase() + " " + r.name(),
              r.durationMillis(),
              "nothing to apply");
        }
      }
      TIMINGS.row("empty, start 2", "server", "whole start (context refresh)", ms, "");
    }
  }

  private static void assertPostgresSchema() throws Exception {
    List<String> tables =
        pg(
            env,
            PgAccount.OWNER,
            "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                + " WHERE n.nspname = 'argos' AND c.relkind IN ('r', 'p') AND NOT c.relispartition"
                + " AND c.relname <> 'flyway_schema_history' ORDER BY 1");
    assertThat(tables).containsExactlyElementsOf(PG_TABLES);
    assertThat(
            pg(
                env,
                PgAccount.OWNER,
                "SELECT c.relname FROM pg_class c JOIN pg_inherits i ON i.inhrelid = c.oid"
                    + " WHERE i.inhparent IN ('argos.alert_events'::regclass,"
                    + " 'argos.audit_logs'::regclass) ORDER BY 1"))
        .as("current and next two monthly partitions")
        .hasSize(6)
        .allMatch(n -> n.matches("(alert_events|audit_logs)_p\\d{6}"));
    assertThat(
            pgScalar(
                env,
                PgAccount.OWNER,
                "SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder) FROM pg_enum e"
                    + " JOIN pg_type t ON t.oid = e.enumtypid WHERE t.typname = 'dict_kind'"))
        .as("C-16, and 'pool' of V003 (ADR-0010 C-101)")
        .isEqualTo(
            "service,sql,class,method,thread,host,frame,metric,error_group,db_target,app_version,"
                + "pool");
    assertThat(
            pgScalar(
                env,
                PgAccount.OWNER,
                "SELECT data_type || ' ' || is_nullable FROM information_schema.columns"
                    + " WHERE table_schema = 'argos' AND table_name = 'apps'"
                    + " AND column_name = 'registered_from_app_id'"))
        .as("V003 apps.registered_from_app_id (ADR-0010 C-155)")
        .isEqualTo("integer YES");
    assertThat(
            pgScalar(
                env,
                PgAccount.OWNER,
                "SELECT c.confdeltype::text || ' ' || r.relname || ' ' || a.attname"
                    + " FROM pg_constraint c JOIN pg_class r ON r.oid = c.confrelid"
                    + " JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1]"
                    + " WHERE c.conrelid = 'argos.apps'::regclass AND c.contype = 'f'"))
        .as("its foreign key to apps(id), ON DELETE SET NULL (C-155 (5))")
        .isEqualTo("n apps registered_from_app_id");
    assertThat(
            pgScalar(
                env,
                PgAccount.OWNER,
                "SELECT n.nspname FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace"
                    + " WHERE e.extname = 'pg_trgm'"))
        .isEqualTo("argos");
    assertThat(
            pg(
                env,
                PgAccount.OWNER,
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'argos' AND indexname LIKE 'ix\\_%'"
                    + " AND tablename NOT LIKE '%\\_p______' ORDER BY 1"))
        .containsExactly(
            "ix_agent_tokens_app",
            "ix_alert_events_app_ts",
            "ix_alert_events_rule_ts",
            "ix_alert_rules_app",
            "ix_alert_states_active",
            "ix_audit_actor_ts",
            "ix_audit_ts",
            // V011 (WO-26): commands and config changes.
            "ix_commands_inflight",
            "ix_commands_instance_requested",
            "ix_commands_requested",
            "ix_config_change_results_awaiting",
            "ix_config_change_results_instance",
            "ix_config_changes_app_requested",
            "ix_deliveries_channel_sent",
            "ix_deliveries_event",
            "ix_deliveries_sent",
            "ix_dictionary_last_seen",
            "ix_dictionary_text_trgm",
            "ix_events_app_ts",
            "ix_instances_app_status",
            "ix_login_failures_unknown_last_failed",
            "ix_mutes_ends",
            "ix_pending_registrations_last_attempt",
            "ix_reconcile_results_day",
            "ix_user_sessions_family",
            "ix_user_sessions_user");
    assertThat(
            pgScalar(
                env,
                PgAccount.OWNER,
                "SELECT count(*) FROM pg_tables WHERE schemaname = 'argos'"
                    + " AND tableowner <> current_user"))
        .as("the owner owns every table")
        .isEqualTo("0");
    String installedBy =
        pgScalar(
            env,
            PgAccount.OWNER,
            "SELECT installed_by FROM argos.flyway_schema_history WHERE version = '001'");
    assertThat(installedBy).isEqualTo(PgAccount.OWNER.user());
  }

  /**
   * V009 (ADR-0010 C-227 (3), WO-38): {@code alert_events.reason VARCHAR(16)} NULL with a
   * closed-list CHECK on the partitioned parent and every partition; NULL and the listed codes are
   * accepted, any other value is refused (23514); running the script again changes nothing. The
   * probe rows are rolled back.
   */
  private static void assertAlertEventReason() throws Exception {
    assertThat(
            pgScalar(
                env,
                PgAccount.OWNER,
                "SELECT data_type || ' ' || character_maximum_length || ' ' || is_nullable"
                    + " FROM information_schema.columns WHERE table_schema = 'argos'"
                    + " AND table_name = 'alert_events' AND column_name = 'reason'"))
        .isEqualTo("character varying 16 YES");
    String checks =
        "SELECT count(*) FROM pg_constraint c JOIN pg_class r ON r.oid = c.conrelid"
            + " WHERE c.conname = 'ck_alert_events_reason' AND c.contype = 'c'"
            + " AND (r.oid = 'argos.alert_events'::regclass OR r.oid IN (SELECT inhrelid"
            + " FROM pg_inherits WHERE inhparent = 'argos.alert_events'::regclass))";
    assertThat(pgScalar(env, PgAccount.OWNER, checks))
        .as("the parent and its three monthly partitions")
        .isEqualTo("4");
    String script;
    try (var in =
        MigrationStartupIT.class.getResourceAsStream("/db/pg/V009__alert_event_reason.sql")) {
      assertThat(in).isNotNull();
      script = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
    try (Connection c = env.connect(PgAccount.OWNER)) {
      c.setAutoCommit(false);
      try (Statement st = c.createStatement()) {
        java.util.UUID rule = java.util.UUID.randomUUID();
        st.executeUpdate(
            "INSERT INTO argos.alert_rules (id, name, enabled, spec) VALUES ('"
                + rule
                + "', 'v009 probe', false, '{}'::jsonb)");
        for (String reason :
            List.of(
                "NULL",
                "'condition'",
                "'cleared'",
                "'escalated'",
                "'acked'",
                "'resolved'",
                "'target_gone'",
                "'rule_disabled'",
                "'rule_deleted'",
                "'no_data'",
                "'data_returned'")) {
          st.executeUpdate(probeEvent(rule, reason));
        }
        java.sql.Savepoint sp = c.setSavepoint();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> st.executeUpdate(probeEvent(rule, "'flapping'")))
            .isInstanceOfSatisfying(
                java.sql.SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23514"))
            .hasMessageContaining("ck_alert_events_reason");
        c.rollback(sp);
        st.execute(script);
        try (ResultSet rs = st.executeQuery(checks)) {
          rs.next();
          assertThat(rs.getInt(1)).as("the script again: no second constraint").isEqualTo(4);
        }
        try (ResultSet rs =
            st.executeQuery(
                "SELECT count(*), count(reason) FROM argos.alert_events WHERE rule_id = '"
                    + rule
                    + "'")) {
          rs.next();
          assertThat(rs.getInt(1)).isEqualTo(11);
          assertThat(rs.getInt(2)).isEqualTo(10);
        }
      } finally {
        c.rollback();
      }
    }
  }

  private static String probeEvent(java.util.UUID rule, String reason) {
    return "INSERT INTO argos.alert_events (id, rule_id, from_state, to_state, ts, reason) VALUES"
        + " (gen_random_uuid(), '"
        + rule
        + "', 'OK', 'PENDING', now(), "
        + reason
        + ")";
  }

  private static void assertClickHouseSchema() {
    MigrationSource source = MigrationApps.bundled();
    MigrationCatalog catalog = MigrationCatalog.load(source, new SchemaTemplates(source));
    Set<String> expected = new LinkedHashSet<>();
    for (SchemaScript script : catalog.scripts()) {
      for (String statement :
          SchemaTemplates.render(script.expanded(), SchemaParameters.defaults())) {
        Matcher m = CREATED.matcher(statement);
        if (m.find()) {
          expected.add(m.group(1));
        }
      }
    }
    assertThat(expected)
        .as("5 raw + 12 rollup tables + 12 views of V001, instance_stat_1h and its view of V003")
        .hasSize(31)
        .contains("instance_stat_1h", "instance_stat_1h_mv");
    ClickHouseHttp migrator = env.clickHouse(Account.MIGRATOR);
    Set<String> actual = new LinkedHashSet<>();
    for (List<String> row :
        migrator.rows("SELECT name FROM system.tables WHERE database = 'argos' ORDER BY name")) {
      actual.add(row.get(0));
    }
    assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    assertThat(
            migrator.scalar(
                "SELECT engine FROM system.tables WHERE database = 'argos' AND name = 'app_stat_1m_mv'"))
        .isEqualTo("MaterializedView");
  }

  private static void assertConnections(ConfigurableApplicationContext ctx) throws Exception {
    DataSource pool = ctx.getBean(DataSource.class);
    try (Connection c = pool.getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT current_user, current_setting('application_name'),"
                    + " has_schema_privilege('argos', 'CREATE')")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString(1))
          .as("the runtime DataSource is argos_app (C-11)")
          .isEqualTo("argos_app");
      assertThat(rs.getString(2)).isEqualTo("argos-server");
      assertThat(rs.getBoolean(3)).as("no DDL on schema argos").isFalse();
    }
    // User and application name of the other sessions are visible to every role.
    List<String> sessions =
        pg(
            env,
            PgAccount.OWNER,
            "SELECT coalesce(usename, '?') || '/' || coalesce(application_name, '?')"
                + " FROM pg_stat_activity WHERE datname = 'argos' AND pid <> pg_backend_pid()");
    assertThat(sessions)
        .as("the migration data sources are closed after their phases (C-11)")
        .noneMatch(s -> s.startsWith(PgAccount.OWNER.user() + "/"))
        .noneMatch(s -> s.endsWith("/argos-server-migration"))
        .anyMatch(s -> s.equals("argos_app/argos-server"));
  }

  private static List<String> flywayHistory() throws Exception {
    return pg(
        env,
        PgAccount.OWNER,
        "SELECT installed_rank || ' ' || coalesce(version, '-') || ' ' || checksum || ' '"
            + " || installed_on || ' ' || success FROM argos.flyway_schema_history ORDER BY 1");
  }

  private static List<String> chHistory() throws Exception {
    return pg(
        env,
        PgAccount.APP,
        "SELECT version || ' ' || checksum || ' ' || applied_at || ' ' || success"
            + " FROM argos.ch_schema_history ORDER BY version");
  }

  @Test
  @Order(2)
  @DisplayName(
      "13 §17 #4 and #5 as seen by the runner account: max_partition_size_to_drop = 0 is in effect;"
          + " DateTime64 TTL with and without toDateTime()")
  void openItems() {
    ClickHouseHttp migrator = env.clickHouse(Account.MIGRATOR);
    List<String> report = new ArrayList<>();
    // #4: the migrator has no grant on system.server_settings (users.d); the value the server
    // loaded is read from its merged configuration (config.xml + config.d/argos.xml).
    assertThat(migrator.failure("SELECT value FROM system.server_settings LIMIT 1"))
        .as("argos_migrator cannot read system.server_settings")
        .isPresent();
    for (String setting : List.of("max_partition_size_to_drop", "max_table_size_to_drop")) {
      var exec =
          env.clickHouseServer()
              .execAsClickHouse(
                  "clickhouse",
                  "extract-from-config",
                  "--config-file",
                  "/etc/clickhouse-server/config.xml",
                  "--key",
                  setting);
      String value = exec.getStdout().strip();
      report.add(setting + " = " + value + " (merged server configuration)");
      assertThat(value).as(setting).isEqualTo("0");
    }
    // #5: V001 casts DateTime64 TTL columns (toDateTime(ts)); check what 26.8 does without the
    // cast.
    String probe = "argos.zz_ttl_probe_" + Long.toHexString(System.nanoTime());
    var withoutCast =
        migrator.failure(
            "CREATE TABLE IF NOT EXISTS "
                + probe
                + " (ts DateTime64(3, 'UTC')) ENGINE = MergeTree ORDER BY ts"
                + " TTL ts + INTERVAL 1 DAY");
    report.add(
        "DateTime64 TTL without toDateTime(): "
            + (withoutCast.isEmpty() ? "accepted" : "refused: " + withoutCast.get().getMessage()));
    migrator.execute("DROP TABLE IF EXISTS " + probe + " SYNC");
    var withCast =
        migrator.failure(
            "CREATE TABLE IF NOT EXISTS "
                + probe
                + " (ts DateTime64(3, 'UTC')) ENGINE = MergeTree ORDER BY ts"
                + " TTL toDateTime(ts) + INTERVAL 1 DAY");
    report.add(
        "DateTime64 TTL with toDateTime(): " + (withCast.isEmpty() ? "accepted" : "refused"));
    migrator.execute("DROP TABLE IF EXISTS " + probe + " SYNC");
    assertThat(withCast).isEmpty();
    assertThat(
            migrator.scalar(
                "SELECT engine_full FROM system.tables WHERE database = 'argos' AND name = 'xlog'"))
        .contains("TTL toDateTime(ts) + toIntervalDay(30)");
    report.forEach(TIMINGS::note);
  }

  @Test
  @Order(3)
  @DisplayName(
      "13 §10: a lost ClickHouse database with an intact PostgreSQL history is created again at"
          + " the next start")
  void clickHouseResetIsRecreated() throws Exception {
    env.clickHouse(Account.MIGRATOR).execute("DROP DATABASE IF EXISTS argos SYNC");
    List<String> before =
        pg(env, PgAccount.APP, "SELECT version FROM argos.ch_schema_history ORDER BY 1");

    try (ConfigurableApplicationContext ctx = MigrationApps.start(env, null, Map.of())) {
      ClickHouseMigrationRunner.Result ch =
          ctx.getBean(ClickHouseMigrationPhase.class).lastResult();
      assertThat(ch.recreated()).isTrue();
      assertThat(ch.applied())
          .extracting(ClickHouseMigrationRunner.Applied::version)
          .containsExactlyElementsOf(CH_VERSIONS);
      TIMINGS.clickHouse("ClickHouse reset", ch);
    }
    assertThat(
            MigrationApps.chCount(
                env, "SELECT count() FROM system.tables WHERE database = 'argos'"))
        .as("V001: 5 raw tables, 12 rollups, 12 views; V003: instance_stat_1h and its view")
        .isEqualTo(31);
    assertThat(pg(env, PgAccount.APP, "SELECT version FROM argos.ch_schema_history ORDER BY 1"))
        .isEqualTo(before);
  }
}
