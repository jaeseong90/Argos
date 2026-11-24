/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres.ops;

import com.zaxxer.hikari.HikariDataSource;
import io.argos.server.config.ArgosProperties;
import io.argos.server.config.SecretValues;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Repository;

/**
 * The monthly partitions of {@code audit_logs} and {@code alert_events} (V001, V014; 13 §12, 17
 * §9.3): the two SECURITY DEFINER functions create the coming months and drop whole expired months.
 *
 * <ul>
 *   <li>{@code audit_logs_maintain_partitions} may be executed by {@code argos_audit} only (SEC-82:
 *       the runtime account cannot remove audit rows) and refuses a drop bound younger than 90 days
 *       (V014, ADR-0010 C-116). This repository opens a pool of at most one connection of that
 *       account ({@code argos.postgres.audit.*}) when it is first used and closes it with the
 *       context; without a password for it the audit side is off ({@link #auditConfigured()}).
 *   <li>{@code alert_events_maintain_partitions} runs with the runtime account {@code argos_app}.
 * </ul>
 *
 * Messages of {@link PartitionException} are one line with the SQL state, no secret value.
 */
@Repository
public class AuditPartitionRepository implements DisposableBean {

  /** Pool name and {@code application_name} of the audit retention connection. */
  public static final String AUDIT_POOL = "argos-server-audit";

  private final DataSource runtime;
  private final ArgosProperties.Postgres postgres;
  private final Object lock = new Object();
  private HikariDataSource audit; // guarded by lock

  public AuditPartitionRepository(DataSource runtime, ArgosProperties properties) {
    this.runtime = runtime;
    this.postgres = properties.postgres();
  }

  /**
   * One row of a maintenance function.
   *
   * @param action {@code created} or {@code dropped}
   * @param partition the partition table name
   */
  public record PartitionAction(String action, String partition) {}

  /** A failed maintenance call; the message is one line without secret values. */
  public static final class PartitionException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String sqlState;

    PartitionException(String message, String sqlState, Throwable cause) {
      super(message, cause);
      this.sqlState = sqlState;
    }

    /** The SQL state, or null. */
    public String sqlState() {
      return sqlState;
    }
  }

  /**
   * Whether a password of the audit retention account is configured ({@code
   * argos.postgres.audit.password-file} or {@code .password}). A configured file that cannot be
   * read counts as configured (the run reports it).
   */
  public boolean auditConfigured() {
    ArgosProperties.Credential c = postgres.audit();
    return c.passwordFile() != null || c.password() != null;
  }

  /** The audit retention account name. */
  public String auditUser() {
    return postgres.audit().user();
  }

  /**
   * {@code audit_logs_maintain_partitions(monthsAhead, dropBefore)} as {@code argos_audit}.
   *
   * @throws PartitionException when the account is not configured, cannot connect, or the function
   *     refuses (SQL state {@code 42501} for the 90-day floor or a missing privilege)
   */
  public List<PartitionAction> maintainAudit(int monthsAhead, Instant dropBefore)
      throws PartitionException {
    DataSource ds = auditDataSource();
    return call(ds, "audit_logs_maintain_partitions", monthsAhead, dropBefore);
  }

  /** {@code alert_events_maintain_partitions(monthsAhead, dropBefore)} as the runtime account. */
  public List<PartitionAction> maintainAlertEvents(int monthsAhead, Instant dropBefore)
      throws PartitionException {
    return call(runtime, "alert_events_maintain_partitions", monthsAhead, dropBefore);
  }

  private static List<PartitionAction> call(
      DataSource ds, String function, int monthsAhead, Instant dropBefore)
      throws PartitionException {
    List<PartitionAction> out = new ArrayList<>();
    try (Connection c = ds.getConnection();
        PreparedStatement st =
            c.prepareStatement("SELECT action, partition_name FROM argos." + function + "(?, ?)")) {
      st.setQueryTimeout(300);
      st.setInt(1, monthsAhead);
      st.setTimestamp(2, dropBefore == null ? null : Timestamp.from(dropBefore));
      try (ResultSet rs = st.executeQuery()) {
        while (rs.next()) {
          out.add(new PartitionAction(rs.getString(1), rs.getString(2)));
        }
      }
    } catch (SQLException e) {
      throw new PartitionException(
          function + " failed (SQL state " + e.getSQLState() + "): " + oneLine(e),
          e.getSQLState(),
          e);
    }
    return out;
  }

  private DataSource auditDataSource() throws PartitionException {
    synchronized (lock) {
      if (audit != null) {
        return audit;
      }
      ArgosProperties.Credential c = postgres.audit();
      String password;
      try {
        Optional<String> value = SecretValues.password("argos.postgres.audit", c);
        if (value.isEmpty()) {
          throw new PartitionException(
              "No password for argos.postgres.audit (user " + c.user() + ")", null, null);
        }
        password = value.get();
      } catch (SecretValues.SecretException e) {
        throw new PartitionException(e.getMessage(), null, e);
      }
      if (postgres.url() == null) {
        throw new PartitionException("argos.postgres.url is required", null, null);
      }
      HikariDataSource ds = new HikariDataSource();
      ds.setPoolName(AUDIT_POOL);
      ds.setJdbcUrl(postgres.url());
      ds.setUsername(c.user());
      ds.setPassword(password);
      ds.setMaximumPoolSize(1);
      ds.setMinimumIdle(0);
      ds.setIdleTimeout(60_000);
      ds.setConnectionTimeout(10_000);
      ds.setInitializationFailTimeout(-1);
      ds.addDataSourceProperty("ApplicationName", AUDIT_POOL);
      audit = ds;
      return ds;
    }
  }

  @Override
  public void destroy() {
    synchronized (lock) {
      if (audit != null) {
        audit.close();
        audit = null;
      }
    }
  }

  private static String oneLine(SQLException e) {
    String message = e.getMessage();
    if (message == null) {
      return e.getClass().getSimpleName();
    }
    String text = message.replaceAll("\\s+", " ").strip();
    return text.length() > 300 ? text.substring(0, 297) + "..." : text;
  }
}
