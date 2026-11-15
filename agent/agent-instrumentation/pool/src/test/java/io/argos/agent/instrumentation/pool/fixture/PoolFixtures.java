/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.pool.fixture;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.apache.commons.dbcp2.BasicDataSource;
import org.apache.tomcat.jdbc.pool.PoolProperties;

/**
 * The application side of the pool advice tests: creates and uses pools of the three libraries over
 * in-memory H2 the way applications do. Loaded by the test's {@code AdvisingLoader} together with
 * the pool libraries (child first), so the pool classes it touches are the advised ones; the test
 * calls it reflectively and gets plain JDK types back.
 */
public final class PoolFixtures {
  private PoolFixtures() {}

  private static String url(String database) {
    return "jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1";
  }

  /** A HikariCP pool ({@code name} null: Hikari's own {@code HikariPool-<n>}), created eagerly. */
  public static DataSource hikari(String name, String database, int max) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(url(database));
    config.setUsername("sa");
    config.setPassword("");
    config.setMaximumPoolSize(max);
    config.setMinimumIdle(1);
    if (name != null) {
      config.setPoolName(name);
    }
    return new HikariDataSource(config);
  }

  /** A HikariCP data source whose pool start fails at once (no driver accepts the URL). */
  public static DataSource failingHikari() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl("jdbc:argos-no-such-driver:x");
    config.setDriverClassName("org.h2.Driver");
    config.setPoolName("failing");
    config.setInitializationFailTimeout(1);
    return new HikariDataSource(config);
  }

  /** A Tomcat JDBC data source; its pool exists after the first connection. */
  public static DataSource tomcat(String name, String database, int max) throws SQLException {
    PoolProperties properties = new PoolProperties();
    properties.setUrl(url(database));
    properties.setDriverClassName("org.h2.Driver");
    properties.setUsername("sa");
    properties.setPassword("");
    properties.setMaxActive(max);
    properties.setMaxIdle(max);
    properties.setInitialSize(0);
    properties.setMinIdle(0);
    if (name != null) {
      properties.setName(name);
    }
    org.apache.tomcat.jdbc.pool.DataSource dataSource =
        new org.apache.tomcat.jdbc.pool.DataSource(properties);
    use(dataSource);
    return dataSource;
  }

  /** A DBCP2 data source; its pool exists after the first connection. */
  public static DataSource dbcp2(String database, int max) throws SQLException {
    BasicDataSource dataSource = new BasicDataSource();
    dataSource.setUrl(url(database));
    dataSource.setDriverClassName("org.h2.Driver");
    dataSource.setUsername("sa");
    dataSource.setPassword("");
    dataSource.setMaxTotal(max);
    dataSource.setMaxIdle(max);
    use(dataSource);
    return dataSource;
  }

  /** One connection and one statement. */
  public static void use(DataSource dataSource) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("SELECT 1");
    }
  }

  /** A connection kept open (active), for the getter values. */
  public static Connection hold(DataSource dataSource) throws SQLException {
    return dataSource.getConnection();
  }

  /** Closes a pool's data source. */
  public static void close(DataSource dataSource) throws Exception {
    if (dataSource instanceof AutoCloseable) {
      ((AutoCloseable) dataSource).close();
    } else if (dataSource instanceof org.apache.tomcat.jdbc.pool.DataSource) {
      ((org.apache.tomcat.jdbc.pool.DataSource) dataSource).close(true);
    }
  }
}
