/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.postgres;

import com.zaxxer.hikari.HikariDataSource;
import io.argos.server.bootstrap.PhaseOrder;
import io.argos.server.bootstrap.StartupPhase;
import io.argos.server.bootstrap.StartupPhaseException;
import io.argos.server.config.ArgosProperties;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Last step of the {@code POSTGRES_MIGRATION} phase (after {@code postgres-flyway}): gives the
 * runtime pool of {@code argos_app} its password and opens its first connection, so a wrong runtime
 * account or password refuses the start before anything else uses the database (C-11, SEC-76). The
 * check also confirms that the account is not the schema owner and can read the schema.
 */
@Component
public class PostgresRuntimePoolPhase implements StartupPhase, Ordered {

  /** Runs after the Flyway step of the same phase. */
  static final int ORDER = 100;

  private final HikariDataSource dataSource;
  private final ArgosProperties properties;

  public PostgresRuntimePoolPhase(HikariDataSource dataSource, ArgosProperties properties) {
    this.dataSource = dataSource;
    this.properties = properties;
  }

  @Override
  public PhaseOrder order() {
    return PhaseOrder.POSTGRES_MIGRATION;
  }

  @Override
  public String name() {
    return "postgres-runtime-pool";
  }

  @Override
  public int getOrder() {
    return ORDER;
  }

  @Override
  public void run() throws StartupPhaseException {
    ArgosProperties.Postgres postgres = properties.postgres();
    String user = postgres.app().user();
    if (user.equals(postgres.owner().user())) {
      throw new StartupPhaseException(
          "argos.postgres.user must be the runtime account (argos_app), not the schema owner "
              + user
              + " (C-11, SEC-76)");
    }
    try {
      PostgresDataSources.requireUrl(postgres);
      dataSource.setPassword(PostgresDataSources.password("argos.postgres", postgres.app()));
    } catch (IllegalStateException e) {
      throw new StartupPhaseException(e.getMessage(), e);
    }
    try (Connection c = dataSource.getConnection();
        Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT current_user, has_table_privilege('argos.settings', 'SELECT')")) {
      if (!rs.next() || !rs.getBoolean(2)) {
        throw new StartupPhaseException(
            "PostgreSQL runtime account " + user + " cannot read the argos schema (C-11 grants)");
      }
    } catch (SQLException e) {
      throw new StartupPhaseException(
          "PostgreSQL runtime account " + user + " cannot connect: " + oneLine(e.getMessage()), e);
    } catch (RuntimeException e) {
      throw new StartupPhaseException(
          "PostgreSQL runtime pool of " + user + " cannot start: " + oneLine(e.getMessage()), e);
    }
  }

  private static String oneLine(String message) {
    return message == null ? "" : message.replaceAll("\\s+", " ").strip();
  }
}
