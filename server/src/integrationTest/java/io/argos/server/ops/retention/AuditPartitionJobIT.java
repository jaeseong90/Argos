/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.ops.retention.AuditPartitionJob.Result;
import io.argos.server.storage.postgres.ops.AuditPartitionRepository.PartitionAction;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ArgosTestServer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The audit and alert history partitions on real PostgreSQL (SEC-82, ADR-0010 C-116, 17 §9.3; part
 * of T-SEC-82): {@code audit_logs_maintain_partitions} (V014) refuses a drop bound younger than 90
 * days even for {@code argos_audit}, the runtime account cannot execute it at all, and the daily
 * job ({@link AuditPartitionJob}, audit account {@code argos.postgres.audit.*}) creates the coming
 * months and drops whole months outside {@code argos.retention.audit} and {@code
 * argos.retention.alert-events}.
 */
@Tag("integration")
@Tag("req:SEC-82")
class AuditPartitionJobIT {

  private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

  private static ServerTestEnvironment env;
  private static ArgosTestServer server;

  @BeforeAll
  static void start() {
    env = ServerTestEnvironment.start();
    server =
        ArgosTestServer.start(
            env,
            Map.of(
                "argos.postgres.audit.password-file",
                env.passwordFile(PgAccount.AUDIT).toString()));
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

  private static List<String> partitions(String parent) throws SQLException {
    List<String> out = new ArrayList<>();
    try (Connection c = env.connect(PgAccount.OWNER);
        PreparedStatement ps =
            c.prepareStatement(
                "SELECT child.relname FROM pg_inherits i JOIN pg_class child ON child.oid ="
                    + " i.inhrelid WHERE i.inhparent = ?::regclass ORDER BY 1")) {
      ps.setString(1, "argos." + parent);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          out.add(rs.getString(1));
        }
      }
    }
    return out;
  }

  private static void createOldPartition(String parent, String month, String from, String to)
      throws SQLException {
    try (Connection c = env.connect(PgAccount.OWNER);
        Statement st = c.createStatement()) {
      st.execute(
          "CREATE TABLE IF NOT EXISTS argos."
              + parent
              + "_p"
              + month
              + " PARTITION OF argos."
              + parent
              + " FOR VALUES FROM ('"
              + from
              + " 00:00:00+00') TO ('"
              + to
              + " 00:00:00+00')");
    }
  }

  private static void call(PgAccount account, String function, String bound) throws SQLException {
    try (Connection c = env.connect(account);
        Statement st = c.createStatement()) {
      st.executeQuery("SELECT * FROM argos." + function + "(2, " + bound + ")").close();
    }
  }

  @Test
  @DisplayName(
      "T-SEC-82p/C-116 argos_audit cannot drop audit partitions younger than 90 days (SQL state"
          + " 42501); argos_app cannot execute the audit function at all")
  void floorAndPrivileges() {
    assertThatThrownBy(
            () ->
                call(
                    PgAccount.AUDIT,
                    "audit_logs_maintain_partitions",
                    "now() - interval '89 days'"))
        .isInstanceOf(SQLException.class)
        .hasMessageContaining("90 days")
        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
    assertThatThrownBy(
            () ->
                call(
                    PgAccount.APP, "audit_logs_maintain_partitions", "now() - interval '400 days'"))
        .isInstanceOf(SQLException.class)
        .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo("42501"));
  }

  @Test
  @DisplayName(
      "T-SEC-82q the daily job drops whole months outside the audit (365 days) and alert history"
          + " retentions with the audit account, keeps the current month and creates two ahead")
  void jobDropsExpiredMonths() throws Exception {
    createOldPartition("audit_logs", "202401", "2024-01-01", "2024-02-01");
    createOldPartition("alert_events", "202401", "2024-01-01", "2024-02-01");
    LocalDate now = LocalDate.now(ZoneOffset.UTC);
    String current = MONTH.format(now);
    String ahead = MONTH.format(now.plusMonths(2));
    assertThat(partitions("audit_logs")).contains("audit_logs_p202401");

    Result result = server.bean(AuditPartitionJob.class).run();
    assertThat(result.auditError()).isNull();
    assertThat(result.audit())
        .extracting(PartitionAction::action, PartitionAction::partition)
        .contains(org.assertj.core.groups.Tuple.tuple("dropped", "audit_logs_p202401"));
    assertThat(result.alertEvents())
        .extracting(PartitionAction::partition)
        .contains("alert_events_p202401");
    assertThat(partitions("audit_logs"))
        .doesNotContain("audit_logs_p202401")
        .contains("audit_logs_p" + current, "audit_logs_p" + ahead);
    assertThat(partitions("alert_events"))
        .doesNotContain("alert_events_p202401")
        .contains("alert_events_p" + current, "alert_events_p" + ahead);
    // A second run changes nothing.
    Result again = server.bean(AuditPartitionJob.class).run();
    assertThat(again.audit()).isEmpty();
    assertThat(again.alertEvents()).isEmpty();
  }
}
