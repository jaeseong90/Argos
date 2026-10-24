/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.boot2.config;

import io.argos.sample.common.SampleConfig;
import io.argos.sample.common.datasource.SampleDataSource;
import io.argos.sample.common.db.DbKind;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shuts the in-memory H2 database down when the Spring context closes. H2 registers a JVM shutdown
 * hook for an open database (DB_CLOSE_ON_EXIT); in a standalone Tomcat the hook is a class of the
 * web app class loader and would keep that class loader alive after an undeploy. shop-war-javax is
 * the target of the WAR redeploy leak tests (18 §6.1, T-MX-080..087), which must see the agent's
 * leaks only. SHUTDOWN closes the database and removes the hook. Other databases: nothing to do.
 */
public final class EmbeddedDatabaseShutdown implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(EmbeddedDatabaseShutdown.class);

  private final SampleDataSource dataSource;
  private final boolean inMemoryH2;

  public EmbeddedDatabaseShutdown(SampleDataSource dataSource, SampleConfig config) {
    this.dataSource = dataSource;
    this.inMemoryH2 = config.db() == DbKind.H2 && config.dbUrl().startsWith("jdbc:h2:mem:");
  }

  /** Whether {@link #close()} shuts a database down (SAMPLE_DB=h2 with an in-memory URL). */
  public boolean applies() {
    return inMemoryH2;
  }

  @Override
  public void close() {
    if (inMemoryH2 && !shutdownDatabase()) {
      log.warn("The in-memory H2 database was not shut down at context close");
    }
  }

  /**
   * Runs SHUTDOWN on a pooled connection (the pool itself, not the routing chain: no Spring
   * transaction is bound at shutdown). Returns whether SHUTDOWN ran; false when nothing applies.
   */
  boolean shutdownDatabase() {
    if (!inMemoryH2) {
      return false;
    }
    Connection connection;
    try {
      connection = dataSource.pool().getConnection();
    } catch (SQLException e) {
      log.warn("No connection for H2 SHUTDOWN at context close", e);
      return false;
    }
    boolean done = false;
    try (Statement statement = connection.createStatement()) {
      statement.execute("SHUTDOWN");
      done = true;
    } catch (SQLException e) {
      log.warn("H2 SHUTDOWN at context close failed", e);
    } finally {
      returnToPool(connection, done);
    }
    return done;
  }

  /**
   * Gives the connection back. After a SHUTDOWN its H2 session is closed, so the pool's reset
   * (Hikari calls clearWarnings()) fails with H2 error 90121 and the pool discards the connection:
   * expected, not a failure.
   */
  private static void returnToPool(Connection connection, boolean afterShutdown) {
    try {
      connection.close();
    } catch (SQLException e) {
      if (afterShutdown) {
        log.debug("Connection closed by SHUTDOWN discarded by the pool: {}", e.toString());
      } else {
        log.warn("Returning the connection to the pool failed", e);
      }
    }
  }
}
