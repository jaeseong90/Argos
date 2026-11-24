/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditRecord;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.AuditSubject;
import io.argos.server.audit.SyslogFormat;
import io.argos.server.audit.Target;
import io.argos.server.security.SecuritySuiteAccess;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.AuditLog;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The audit record and its immutability on the real database (SEC-81, SEC-82; 13 §12, 16 §11.2,
 * §11.3): every column of {@code audit_logs}; the runtime account {@code argos_app} cannot update,
 * delete or truncate a record (only insert and read); the syslog form of a stored record.
 */
@Tag("req:SEC-81")
@Tag("req:SEC-82")
class AuditSecurityTest {

  private static final String DENIED = "42501";

  private static ServerTestEnvironment env() {
    return SecuritySuiteAccess.environment();
  }

  private static ArgosTestServer server() {
    return SecuritySuiteAccess.server();
  }

  private static List<String> columns() throws SQLException {
    List<String> out = new ArrayList<>();
    try (Connection c = env().connect(PgAccount.OWNER);
        Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT column_name || ' ' || data_type || ' ' || is_nullable"
                    + " FROM information_schema.columns WHERE table_schema = 'argos'"
                    + " AND table_name = 'audit_logs' ORDER BY ordinal_position")) {
      while (rs.next()) {
        out.add(rs.getString(1));
      }
    }
    return out;
  }

  @Test
  @DisplayName(
      "T-SEC-81 audit_logs has every 13 §12 column: time, actor (type, id, name, ip, user agent),"
          + " request id, action, target, result, reason, masked before and after, personal data"
          + " subject")
  void everyColumn() throws SQLException {
    assertThat(columns())
        .containsExactly(
            "id bigint NO",
            "ts timestamp with time zone NO",
            "actor_type character varying NO",
            "actor_id character varying YES",
            "actor_name character varying YES",
            "ip inet YES",
            "user_agent character varying YES",
            "request_id character varying YES",
            "action character varying NO",
            "target_type character varying YES",
            "target_id character varying YES",
            "target_name character varying YES",
            "result character varying NO",
            "reason character varying YES",
            "before jsonb YES",
            "after jsonb YES",
            "subject_user_id character varying YES",
            "subject_tx_id bigint YES");
    String marker = "sec-81-" + UUID.randomUUID();
    server()
        .bean(AuditService.class)
        .record(
            AuditEvent.SENSITIVE_VIEW,
            new Actor(Actor.Type.USER, "u-81", "admin-81", "192.0.2.10", "Mozilla/5.0"),
            new Target("transaction", marker, null),
            AuditResult.success("sql_params_full"),
            null,
            Map.of("type", "sql_params_full"),
            new AuditSubject("user-81", 81L));
    try (Connection c = env().connect(PgAccount.APP);
        PreparedStatement p =
            c.prepareStatement(
                "SELECT actor_type, actor_id, actor_name, host(ip), user_agent, action, target_type,"
                    + " result, reason, after->>'type', subject_user_id, subject_tx_id, ts"
                    + " FROM argos.audit_logs WHERE target_id = ?")) {
      p.setString(1, marker);
      try (ResultSet rs = p.executeQuery()) {
        assertThat(rs.next()).isTrue();
        assertThat(rs.getString(1)).isEqualTo("user");
        assertThat(rs.getString(2)).isEqualTo("u-81");
        assertThat(rs.getString(3)).isEqualTo("admin-81");
        assertThat(rs.getString(4)).isEqualTo("192.0.2.10");
        assertThat(rs.getString(5)).isEqualTo("Mozilla/5.0");
        assertThat(rs.getString(6)).isEqualTo("SENSITIVE_VIEW");
        assertThat(rs.getString(7)).isEqualTo("transaction");
        assertThat(rs.getString(8)).isEqualTo("success");
        assertThat(rs.getString(9)).isEqualTo("sql_params_full");
        assertThat(rs.getString(10)).isEqualTo("sql_params_full");
        assertThat(rs.getString(11)).isEqualTo("user-81");
        assertThat(rs.getLong(12)).isEqualTo(81L);
        assertThat(rs.getTimestamp(13)).isNotNull();
        assertThat(rs.next()).isFalse();
      }
    }
  }

  @Test
  @DisplayName(
      "T-SEC-82 argos_app (the runtime account) cannot UPDATE, DELETE or TRUNCATE audit_logs:"
          + " permission denied, the record unchanged")
  void appendOnly() throws SQLException {
    String marker = "sec-82-" + UUID.randomUUID();
    server()
        .bean(AuditService.class)
        .record(
            AuditEvent.ROLE_CHANGED,
            Actor.system(),
            new Target("user", marker, "victim"),
            AuditResult.success());
    try (Connection c = env().connect(PgAccount.APP);
        Statement s = c.createStatement()) {
      for (String sql :
          List.of(
              "UPDATE argos.audit_logs SET action = 'LOGOUT' WHERE target_id = '" + marker + "'",
              "DELETE FROM argos.audit_logs WHERE target_id = '" + marker + "'",
              "TRUNCATE argos.audit_logs")) {
        assertThatThrownBy(() -> s.executeUpdate(sql))
            .as(sql)
            .isInstanceOfSatisfying(
                SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo(DENIED));
      }
    }
    assertThat(AuditLog.rows(env(), "ROLE_CHANGED"))
        .filteredOn(r -> marker.equals(r.targetId()))
        .singleElement()
        .satisfies(r -> assertThat(r.targetName()).isEqualTo("victim"));
  }

  @Test
  @DisplayName(
      "T-SEC-82 the syslog form of a stored record is one RFC 5424 message with the argos SD"
          + " element and the record's time")
  void syslogForm() {
    String marker = "sec-82-syslog-" + UUID.randomUUID();
    server()
        .bean(AuditService.class)
        .record(
            AuditEvent.LOGIN_FAILED,
            new Actor(Actor.Type.USER, null, "x\"]\\y", "192.0.2.1", null),
            new Target("user", marker, null),
            AuditResult.failure("bad_credentials"));
    AuditLog.Row row =
        AuditLog.rows(env(), "LOGIN_FAILED").stream()
            .filter(r -> marker.equals(r.targetId()))
            .findFirst()
            .orElseThrow();
    AuditRecord record =
        new AuditRecord(
            row.ts(),
            row.actorType(),
            row.actorId(),
            row.actorName(),
            row.ip(),
            row.userAgent(),
            row.requestId(),
            row.action(),
            row.targetType(),
            row.targetId(),
            row.targetName(),
            row.result(),
            row.reason(),
            row.before(),
            row.after(),
            null,
            null);
    String message = SyslogFormat.message(record, "argos", "1", 8192);
    assertThat(message)
        .matches(
            Pattern.compile(
                "<108>1 \\S+Z argos argos 1 LOGIN_FAILED \\[argos@32473 .*", Pattern.DOTALL))
        .contains(" actor_name=\"x\\\"\\]\\\\y\"")
        .contains(" target_id=\"" + marker + "\"")
        .contains(" result=\"failure\"")
        .doesNotContain("\n");
    assertThat(
            Instant.parse(
                message.substring(
                    message.indexOf(" ") + 1, message.indexOf(" ", message.indexOf(" ") + 1))))
        .isEqualTo(row.ts());
  }
}
