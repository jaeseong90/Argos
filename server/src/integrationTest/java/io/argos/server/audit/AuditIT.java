/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.AuditLog;
import io.argos.server.web.RequestIdFilter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The minimal audit service of WO-24 and the V002 schema on the real server (PostgreSQL 16 and
 * ClickHouse, Testcontainers): records reach {@code audit_logs} through the runtime account {@code
 * argos_app} with every 13 §12 column, masked; a failed insert is counted and never thrown (16
 * §11.3); V002 adds the 16 §7.3 session columns, the lockout level and {@code user_permissions}
 * with the grants of V001's rules.
 */
@Tag("req:SEC-80")
@Tag("req:SEC-82")
class AuditIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String DENIED = "42501";

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;

  @BeforeAll
  static void start() {
    env = ServerTestEnvironment.start();
    server = ArgosTestServer.start(env);
  }

  @AfterAll
  static void stop() {
    try {
      if (server != null) {
        server.close();
      }
    } finally {
      if (env != null) {
        env.close();
      }
    }
  }

  private static double failures() {
    return server
        .bean(MeterRegistry.class)
        .get(DefaultAuditService.FAILURES_METER)
        .counter()
        .count();
  }

  @Test
  @DisplayName(
      "T-SEC-80a an audit record reaches audit_logs as argos_app with every 13 §12 column, before"
          + " and after masked")
  void recordReachesPostgres() {
    AuditService audit = server.bean(AuditService.class);
    UUID userId = UUID.randomUUID();
    String requestId = "01JAUDITIT" + UUID.randomUUID().toString().substring(0, 8);
    Map<String, Object> before = new LinkedHashMap<>();
    before.put("role", "VIEWER");
    before.put("password", "never-stored");
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("role", "OPERATOR");
    after.put("must_change_password", true);
    MDC.put(RequestIdFilter.MDC_KEY, requestId);
    try {
      audit.record(
          AuditEvent.ROLE_CHANGED,
          new Actor(Actor.Type.USER, userId.toString(), "admin-1", "127.0.0.1", "JUnit/5"),
          Target.user(UUID.randomUUID(), "ops-1"),
          AuditResult.success("admin_change"),
          before,
          after);
    } finally {
      MDC.remove(RequestIdFilter.MDC_KEY);
    }
    List<AuditLog.Row> rows = AuditLog.ofRequest(env, requestId);
    assertThat(rows).hasSize(1);
    AuditLog.Row row = rows.get(0);
    assertThat(row.action()).isEqualTo("ROLE_CHANGED");
    assertThat(row.actorType()).isEqualTo("user");
    assertThat(row.actorId()).isEqualTo(userId.toString());
    assertThat(row.actorName()).isEqualTo("admin-1");
    assertThat(row.ip()).isEqualTo("127.0.0.1");
    assertThat(row.userAgent()).isEqualTo("JUnit/5");
    assertThat(row.targetType()).isEqualTo("user");
    assertThat(row.targetName()).isEqualTo("ops-1");
    assertThat(row.result()).isEqualTo("success");
    assertThat(row.reason()).isEqualTo("admin_change");
    assertThat(row.ts()).isNotNull();
    JsonNode storedBefore = JSON.readTree(row.before());
    assertThat(storedBefore.path("role").asString()).isEqualTo("VIEWER");
    assertThat(storedBefore.path("password").asString()).isEqualTo("****");
    assertThat(row.before()).doesNotContain("never-stored");
    JsonNode storedAfter = JSON.readTree(row.after());
    assertThat(storedAfter.path("must_change_password").asBoolean()).isTrue();
  }

  @Test
  @DisplayName(
      "T-SEC-80a a failed audit insert is counted in argos_audit_write_failures_total and never"
          + " thrown")
  void failedInsertCounted() {
    AuditService audit = server.bean(AuditService.class);
    double before = failures();
    String marker = "t-sec-80a-" + UUID.randomUUID();
    // 999.999.999.999 looks like an IPv4 literal but PostgreSQL rejects it as inet: the insert
    // fails.
    assertThatCode(
            () ->
                audit.record(
                    AuditEvent.LOGIN_FAILED,
                    new Actor(Actor.Type.USER, null, marker, "999.999.999.999", null),
                    Target.none(),
                    AuditResult.failure("bad_credentials")))
        .doesNotThrowAnyException();
    assertThat(failures()).isEqualTo(before + 1);
    assertThat(AuditLog.rows(env, "LOGIN_FAILED")).noneMatch(r -> marker.equals(r.actorName()));
  }

  @Test
  @DisplayName(
      "V002: session rotation and expiry columns, lockout level, user_permissions and"
          + " login_failures_unknown with argos_app DML and argos_backup SELECT")
  @Tag("req:SEC-41")
  @Tag("req:SEC-42")
  @Tag("req:SEC-48")
  void schemaV002() throws Exception {
    assertThat(columns("user_sessions"))
        .contains(
            "family_id uuid NO",
            "idle_expires_at timestamp with time zone NO",
            "absolute_expires_at timestamp with time zone NO",
            "rotated_at timestamp with time zone YES",
            "rotated_from uuid YES",
            "revoked_reason character varying YES",
            "refresh_hash character NO",
            "user_agent character varying YES",
            "ip inet YES")
        .noneMatch(c -> c.startsWith("expires_at "));
    assertThat(columns("users")).contains("lockout_level smallint NO");
    assertThat(columns("user_permissions"))
        .containsExactly(
            "user_id uuid NO",
            "permission character varying NO",
            "granted_by uuid YES",
            "granted_at timestamp with time zone NO");
    assertThat(columns("login_failures_unknown"))
        .as("the failure counter of unknown names: the users columns, keyed by a SHA-256")
        .containsExactly(
            "name_hash character NO",
            "failed_logins integer NO",
            "locked_until timestamp with time zone YES",
            "lockout_level smallint NO",
            "last_failed_at timestamp with time zone NO");
    assertThat(
            scalar(
                PgAccount.OWNER,
                "SELECT string_agg(grantee || ':' || privilege_type, ',' ORDER BY grantee,"
                    + " privilege_type) FROM information_schema.role_table_grants"
                    + " WHERE table_schema = 'argos' AND table_name = 'login_failures_unknown'"
                    + " AND grantee IN ('argos_app', 'argos_backup', 'argos_audit')"))
        .isEqualTo(
            "argos_app:DELETE,argos_app:INSERT,argos_app:SELECT,argos_app:UPDATE,"
                + "argos_backup:SELECT");
    assertThat(
            scalar(
                PgAccount.OWNER,
                "SELECT success::text FROM argos.flyway_schema_history WHERE version = '002'"))
        .isEqualTo("true");

    UUID user = UUID.randomUUID();
    try (Connection c = env.connect(PgAccount.APP);
        Statement s = c.createStatement()) {
      s.executeUpdate(
          "INSERT INTO argos.users (id, username, password_hash, role) VALUES ('"
              + user
              + "', 't-v002-"
              + user.toString().substring(0, 8)
              + "', 'x', 'ADMIN')");
      assertThat(scalar(s, "SELECT lockout_level::text FROM argos.users WHERE id = '" + user + "'"))
          .isEqualTo("0");
      s.executeUpdate(
          "INSERT INTO argos.user_permissions (user_id, permission, granted_by) VALUES ('"
              + user
              + "', 'pii.view', '"
              + user
              + "')");
      assertThat(
              scalar(
                  s, "SELECT count(*) FROM argos.user_permissions WHERE user_id = '" + user + "'"))
          .isEqualTo("1");
      assertThatThrownBy(
              () ->
                  s.executeUpdate(
                      "INSERT INTO argos.user_permissions (user_id, permission) VALUES ('"
                          + user
                          + "', 'admin.all')"))
          .isInstanceOf(SQLException.class)
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("23514"));
      UUID sid = UUID.randomUUID();
      s.executeUpdate(
          "INSERT INTO argos.user_sessions (id, user_id, family_id, refresh_hash, issued_at,"
              + " last_seen_at, idle_expires_at, absolute_expires_at) VALUES ('"
              + sid
              + "', '"
              + user
              + "', '"
              + sid
              + "', repeat('a', 64), now(), now(), now() + interval '8 hours',"
              + " now() + interval '24 hours')");
      s.executeUpdate(
          "UPDATE argos.user_sessions SET revoked_at = now(), revoked_reason = 'logout' WHERE id = '"
              + sid
              + "'");
      assertThat(
              scalar(
                  s,
                  "SELECT revoked_reason FROM argos.user_sessions WHERE family_id = '" + sid + "'"))
          .isEqualTo("logout");
      s.executeUpdate("DELETE FROM argos.user_permissions WHERE user_id = '" + user + "'");
      s.executeUpdate("DELETE FROM argos.users WHERE id = '" + user + "'");
    }
    try (Connection c = env.connect(PgAccount.BACKUP);
        Statement s = c.createStatement()) {
      assertThat(scalar(s, "SELECT count(*) FROM argos.user_permissions")).isNotNull();
      assertThatThrownBy(
              () ->
                  s.executeUpdate(
                      "INSERT INTO argos.user_permissions (user_id, permission) VALUES ('"
                          + UUID.randomUUID()
                          + "', 'pii.view')"))
          .isInstanceOf(SQLException.class)
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(DENIED));
    }
    try (Connection c = env.connect(PgAccount.APP);
        Statement s = c.createStatement()) {
      assertThatThrownBy(
              () -> s.executeUpdate("ALTER TABLE argos.user_permissions ADD COLUMN x int"))
          .isInstanceOf(SQLException.class)
          .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(DENIED));
    }
  }

  private static List<String> columns(String table) throws SQLException {
    List<String> out = new ArrayList<>();
    try (Connection c = env.connect(PgAccount.OWNER);
        Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT column_name || ' ' || data_type || ' ' || is_nullable"
                    + " FROM information_schema.columns WHERE table_schema = 'argos'"
                    + " AND table_name = '"
                    + table
                    + "' ORDER BY ordinal_position")) {
      while (rs.next()) {
        out.add(rs.getString(1));
      }
    }
    return out;
  }

  private static String scalar(PgAccount account, String sql) throws SQLException {
    try (Connection c = env.connect(account);
        Statement s = c.createStatement()) {
      return scalar(s, sql);
    }
  }

  private static String scalar(Statement s, String sql) throws SQLException {
    try (ResultSet rs = s.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }
}
