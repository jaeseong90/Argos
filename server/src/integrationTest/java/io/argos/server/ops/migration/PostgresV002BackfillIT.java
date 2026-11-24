/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * T-ST-052 path 2 for the PostgreSQL scripts (03 §3.2 "두 경로", N-22): an installation of V001 that
 * holds users and sessions, then the server's start applies V002 (WO-24), whose backfill gives
 * every existing session row its own id as {@code family_id} and its V001 {@code expires_at} (now
 * {@code absolute_expires_at}) as {@code idle_expires_at} before both columns become NOT NULL.
 * {@link MigrationStartupIT} covers path 1 (empty database).
 */
@Tag("req:N-22")
@Tag("req:DSN-12-15")
class PostgresV002BackfillIT {

  private static ServerTestEnvironment env;
  private static final MigrationTimings TIMINGS =
      new MigrationTimings(
          "path-2-pg-v002", "T-ST-052 path 2, PostgreSQL: V001 + users and sessions -> V002");

  /** One V001 session row as written. */
  private record V001Session(
      UUID id,
      UUID userId,
      Instant issuedAt,
      Instant expiresAt,
      Instant revokedAt,
      boolean kiosk,
      String userAgent,
      String ip) {}

  @BeforeAll
  static void start() {
    env = ServerTestEnvironment.start();
  }

  @AfterAll
  static void stop() {
    if (env != null) {
      env.close();
    }
    TIMINGS.write();
  }

  @Test
  @DisplayName(
      "T-ST-052 PostgreSQL V001 with users and sessions -> V002 at the server's start: family_id ="
          + " id, idle_expires_at = absolute_expires_at (the V001 expires_at), both NOT NULL, the"
          + " other values kept")
  void backfillOfExistingRows() throws Exception {
    PostgresMigrationPhase.Result v001 =
        PostgresMigrationPhase.migrateTo(new OwnerDataSource(env), "1");
    assertThat(v001.schemaVersion()).isEqualTo("001");
    assertThat(v001.applied())
        .extracting(PostgresMigrationPhase.Applied::version)
        .containsOnly("001");
    TIMINGS.flyway("V001 (Flyway target 1)", v001);

    // Rows as a V001 server writes them, as its runtime account. Times are recent, so no cleanup
    // job of the started server deletes them (C-117: 7 days after the end).
    Instant t0 = Instant.now().truncatedTo(ChronoUnit.SECONDS).minus(Duration.ofHours(1));
    UUID admin = UUID.randomUUID();
    UUID viewer = UUID.randomUUID();
    List<V001Session> sessions =
        List.of(
            new V001Session(
                UUID.randomUUID(),
                admin,
                t0,
                t0.plus(Duration.ofHours(24)),
                null,
                false,
                "Mozilla/5.0 (test)",
                "192.0.2.10"),
            new V001Session(
                UUID.randomUUID(),
                viewer,
                t0.plusSeconds(60),
                t0.plusSeconds(60).plus(Duration.ofHours(24)),
                t0.plus(Duration.ofMinutes(30)),
                false,
                null,
                null),
            new V001Session(
                UUID.randomUUID(),
                viewer,
                t0.plusSeconds(120),
                t0.plusSeconds(120).plus(Duration.ofDays(30)),
                null,
                true,
                "kiosk",
                "198.51.100.7"));
    try (Connection c = env.connect(PgAccount.APP)) {
      insertUser(c, admin, "admin-" + admin.toString().substring(0, 8), "ADMIN", 0);
      insertUser(c, viewer, "viewer-" + viewer.toString().substring(0, 8), "VIEWER", 3);
      for (V001Session s : sessions) {
        insertV001Session(c, s);
      }
    }
    // An alert transition as a V001..V008 server writes it (no reason column yet, WO-38 V009).
    UUID oldRule = UUID.randomUUID();
    UUID oldEvent = UUID.randomUUID();
    try (Connection c = env.connect(PgAccount.OWNER);
        PreparedStatement rule =
            c.prepareStatement(
                "INSERT INTO argos.alert_rules (id, name, enabled, spec) VALUES (?, 'v001 rule',"
                    + " false, CAST(? AS jsonb))");
        PreparedStatement event =
            c.prepareStatement(
                "INSERT INTO argos.alert_events (id, rule_id, target_kind, from_state, to_state,"
                    + " severity, value, threshold, duration_s, ts) VALUES (?, ?, 'server', 'PENDING',"
                    + " 'FIRING', 'warn', 2, 1, 0, now())")) {
      rule.setObject(1, oldRule);
      rule.setString(
          2,
          "{\"name\":\"v001 rule\",\"enabled\":false,\"target\":{\"app_id\":null,"
              + "\"env\":null,\"type\":\"server\",\"ids\":[]},"
              + "\"metric\":\"argos_server_dropped_total\",\"window\":\"5m\",\"for\":\"0s\","
              + "\"condition\":{\"operator\":\">\",\"warn\":1}}");
      rule.executeUpdate();
      event.setObject(1, oldEvent);
      event.setObject(2, oldRule);
      event.executeUpdate();
    }

    try (ConfigurableApplicationContext ctx = MigrationApps.start(env)) {
      PostgresMigrationPhase.Result v002 = ctx.getBean(PostgresMigrationPhase.class).lastResult();
      // V002 (WO-24), the later V003 (dict_kind 'pool', ADR-0010 C-101, WO-21) and V004
      // (pending_registrations.home_app_id, C-155 (3), WO-22), V005 (reconcile_results) and V006
      // (service_apdex_overrides, both WO-27), V007 (alert engine) and V008 (alert channels, both
      // WO-25), V009 (alert_events.reason, WO-38) and V010 to V012 (agent token lifecycle, commands
      // and config changes, app management, WO-26) and the later ones (WO-28 and on): every bundled
      // version after V001, in order (MigrationStartupIT.PG_VERSIONS).
      List<String> later =
          MigrationStartupIT.PG_VERSIONS.subList(1, MigrationStartupIT.PG_VERSIONS.size());
      assertThat(v002.applied())
          .extracting(PostgresMigrationPhase.Applied::version)
          .containsExactlyElementsOf(later);
      assertThat(v002.schemaVersion()).isEqualTo(later.get(later.size() - 1));
      TIMINGS.flyway("V001 + 2 users, 3 sessions -> start", v002);
    }

    assertThat(
            MigrationApps.pg(
                env,
                PgAccount.OWNER,
                "SELECT to_state || ' ' || coalesce(reason, '<null>') FROM argos.alert_events"
                    + " WHERE id = '"
                    + oldEvent
                    + "'"))
        .as("V009: a row written before it keeps reason NULL")
        .containsExactly("FIRING <null>");

    Map<UUID, Map<String, Object>> after = sessionsAfter(sessions);
    assertThat(after).hasSize(sessions.size());
    for (V001Session s : sessions) {
      Map<String, Object> row = after.get(s.id());
      assertThat(row.get("family_id")).as("family_id of " + s.id()).isEqualTo(s.id());
      assertThat(row.get("absolute_expires_at"))
          .as("absolute_expires_at is the V001 expires_at")
          .isEqualTo(s.expiresAt());
      assertThat(row.get("idle_expires_at"))
          .as("idle_expires_at = absolute_expires_at")
          .isEqualTo(s.expiresAt());
      assertThat(row.get("rotated_at")).isNull();
      assertThat(row.get("rotated_from")).isNull();
      assertThat(row.get("revoked_reason")).isNull();
      assertThat(row.get("revoked_at")).isEqualTo(s.revokedAt());
      assertThat(row.get("user_id")).isEqualTo(s.userId());
      assertThat(row.get("issued_at")).isEqualTo(s.issuedAt());
      assertThat(row.get("kiosk")).isEqualTo(s.kiosk());
      assertThat(row.get("user_agent")).isEqualTo(s.userAgent());
      assertThat(row.get("ip")).isEqualTo(s.ip());
    }

    assertThat(
            MigrationApps.pg(
                env,
                PgAccount.OWNER,
                "SELECT username || ' ' || failed_logins || ' ' || lockout_level FROM argos.users"
                    + " WHERE id IN ('"
                    + admin
                    + "', '"
                    + viewer
                    + "') ORDER BY role"))
        .as("users keep their counters and start at lockout level 0")
        .containsExactly(
            "admin-" + admin.toString().substring(0, 8) + " 0 0",
            "viewer-" + viewer.toString().substring(0, 8) + " 3 0");

    assertThat(
            MigrationApps.pg(
                env,
                PgAccount.OWNER,
                "SELECT column_name || ' ' || is_nullable FROM information_schema.columns"
                    + " WHERE table_schema = 'argos' AND table_name = 'user_sessions'"
                    + " AND column_name IN ('family_id', 'idle_expires_at', 'absolute_expires_at',"
                    + " 'expires_at') ORDER BY column_name"))
        .containsExactly("absolute_expires_at NO", "family_id NO", "idle_expires_at NO");

    // The constraints hold for new rows too: without either column the insert fails.
    try (Connection c = env.connect(PgAccount.APP)) {
      assertThatThrownBy(() -> insertV002Session(c, admin, false, true))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23502"))
          .hasMessageContaining("family_id");
      assertThatThrownBy(() -> insertV002Session(c, admin, true, false))
          .isInstanceOfSatisfying(
              SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23502"))
          .hasMessageContaining("idle_expires_at");
    }
  }

  private static void insertUser(
      Connection c, UUID id, String username, String role, int failedLogins) throws SQLException {
    try (PreparedStatement insert =
        c.prepareStatement(
            "INSERT INTO argos.users (id, username, password_hash, role, failed_logins)"
                + " VALUES (?, ?, ?, ?, ?)")) {
      insert.setObject(1, id);
      insert.setString(2, username);
      // Not a usable hash: this test never logs in.
      insert.setString(3, "$2a$12$" + "x".repeat(53));
      insert.setString(4, role);
      insert.setInt(5, failedLogins);
      insert.executeUpdate();
    }
  }

  /** A row of the V001 shape: {@code expires_at}, no family, no idle expiry. */
  private static void insertV001Session(Connection c, V001Session s) throws SQLException {
    try (PreparedStatement insert =
        c.prepareStatement(
            "INSERT INTO argos.user_sessions (id, user_id, refresh_hash, issued_at, last_seen_at,"
                + " expires_at, revoked_at, user_agent, ip, kiosk)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS inet), ?)")) {
      insert.setObject(1, s.id());
      insert.setObject(2, s.userId());
      insert.setString(3, refreshHash());
      insert.setObject(4, timestamp(s.issuedAt()));
      insert.setObject(5, timestamp(s.issuedAt()));
      insert.setObject(6, timestamp(s.expiresAt()));
      insert.setObject(7, timestamp(s.revokedAt()));
      insert.setString(8, s.userAgent());
      insert.setString(9, s.ip());
      insert.setBoolean(10, s.kiosk());
      insert.executeUpdate();
    }
  }

  /** A row of the V002 shape that leaves out {@code family_id} or {@code idle_expires_at}. */
  private static void insertV002Session(
      Connection c, UUID userId, boolean withFamily, boolean withIdleExpiry) throws SQLException {
    Instant now = Instant.now();
    UUID id = UUID.randomUUID();
    StringBuilder columns =
        new StringBuilder(
            "id, user_id, refresh_hash, issued_at, last_seen_at, absolute_expires_at");
    StringBuilder values = new StringBuilder("?, ?, ?, ?, ?, ?");
    if (withFamily) {
      columns.append(", family_id");
      values.append(", ?");
    }
    if (withIdleExpiry) {
      columns.append(", idle_expires_at");
      values.append(", ?");
    }
    try (PreparedStatement insert =
        c.prepareStatement(
            "INSERT INTO argos.user_sessions (" + columns + ") VALUES (" + values + ")")) {
      int i = 1;
      insert.setObject(i++, id);
      insert.setObject(i++, userId);
      insert.setString(i++, refreshHash());
      insert.setObject(i++, timestamp(now));
      insert.setObject(i++, timestamp(now));
      insert.setObject(i++, timestamp(now.plus(Duration.ofHours(24))));
      if (withFamily) {
        insert.setObject(i++, id);
      }
      if (withIdleExpiry) {
        insert.setObject(i, timestamp(now.plus(Duration.ofHours(8))));
      }
      insert.executeUpdate();
    }
  }

  private static Map<UUID, Map<String, Object>> sessionsAfter(List<V001Session> sessions)
      throws SQLException {
    Map<UUID, Map<String, Object>> rows = new LinkedHashMap<>();
    try (Connection c = env.connect(PgAccount.OWNER);
        PreparedStatement select =
            c.prepareStatement(
                "SELECT id, user_id, family_id, issued_at, idle_expires_at, absolute_expires_at,"
                    + " rotated_at, rotated_from, revoked_at, revoked_reason, user_agent,"
                    + " host(ip) AS ip, kiosk FROM argos.user_sessions WHERE id = ANY (?)")) {
      select.setArray(
          1, c.createArrayOf("uuid", sessions.stream().map(V001Session::id).toArray(UUID[]::new)));
      try (ResultSet rs = select.executeQuery()) {
        while (rs.next()) {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("user_id", rs.getObject("user_id", UUID.class));
          row.put("family_id", rs.getObject("family_id", UUID.class));
          row.put("issued_at", instant(rs, "issued_at"));
          row.put("idle_expires_at", instant(rs, "idle_expires_at"));
          row.put("absolute_expires_at", instant(rs, "absolute_expires_at"));
          row.put("rotated_at", instant(rs, "rotated_at"));
          row.put("rotated_from", rs.getObject("rotated_from", UUID.class));
          row.put("revoked_at", instant(rs, "revoked_at"));
          row.put("revoked_reason", rs.getString("revoked_reason"));
          row.put("user_agent", rs.getString("user_agent"));
          row.put("ip", rs.getString("ip"));
          row.put("kiosk", rs.getBoolean("kiosk"));
          rows.put(rs.getObject("id", UUID.class), row);
        }
      }
    }
    return rows;
  }

  private static String refreshHash() {
    String hex = UUID.randomUUID().toString().replace("-", "");
    return hex + hex;
  }

  private static OffsetDateTime timestamp(Instant instant) {
    return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  /** The schema owner's connections of the environment, as the data source Flyway takes. */
  private record OwnerDataSource(ServerTestEnvironment env) implements DataSource {

    @Override
    public Connection getConnection() throws SQLException {
      return env.connect(PgAccount.OWNER);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      throw new SQLFeatureNotSupportedException("the owner account only");
    }

    @Override
    public PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {}

    @Override
    public void setLoginTimeout(int seconds) {}

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
      throw new SQLException("not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> type) {
      return false;
    }
  }
}
