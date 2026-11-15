/* SPDX-License-Identifier: Apache-2.0 */
package example.jdbcwrapper;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Iterator;
import java.util.Properties;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * SAMPLE_DRIVER_WRAPPER=true (ADR-0004 C-40): a {@link java.sql.Driver} that wraps the real driver
 * by delegation, the fifth level of the JDBC nesting fixture (D-24) next to the four of
 * SAMPLE_ROUTING_DS: {@code AbstractRoutingDataSource -> TransactionAwareDataSourceProxy -> pool ->
 * WrappingDriver -> driver}. The pool is configured with this class and the URL {@value #PREFIX}
 * {@code <real JDBC URL>}; every connection and statement is a delegating wrapper ({@link
 * WrappedConnection}, {@link WrappedStatement}, {@link WrappedPreparedStatement}, {@link
 * WrappedCallableStatement}), as P6Spy or log4jdbc do it.
 *
 * <p>The package lies outside {@code io.argos} on purpose: the agent never instruments {@code
 * io.argos.*} classes (10 §4.5), while a third-party driver wrapper is instrumented like any JDBC
 * implementation, which is what the fixture must show. The real driver is the first {@link
 * java.sql.Driver} service of this class loader that accepts the real URL (JDBC 4 service
 * registration of H2, PostgreSQL, MySQL, MariaDB, SQL Server and Oracle).
 *
 * <p>{@link #connections()} counts the connections opened through the wrapper, so that tests and
 * GET /api/runtime can tell that the level is really in the chain.
 */
public final class WrappingDriver implements Driver {
  /** URL prefix of the wrapper; the real JDBC URL follows. */
  public static final String PREFIX = "jdbc:argos-wrapper:";

  private static final AtomicLong CONNECTIONS = new AtomicLong();

  private volatile Driver target;

  /** The wrapper URL of {@code realUrl}. */
  public static String wrap(String realUrl) {
    return PREFIX + realUrl;
  }

  /** Connections opened through any wrapper of this class loader. */
  public static long connections() {
    return CONNECTIONS.get();
  }

  @Override
  public Connection connect(String url, Properties info) throws SQLException {
    if (!acceptsURL(url)) {
      return null; // java.sql.Driver contract: another driver's URL
    }
    String real = url.substring(PREFIX.length());
    Connection connection = driverFor(real).connect(real, info);
    if (connection == null) {
      throw new SQLException("No driver accepted the wrapped URL (scheme " + scheme(real) + ")");
    }
    CONNECTIONS.incrementAndGet();
    return new WrappedConnection(connection);
  }

  @Override
  public boolean acceptsURL(String url) {
    return url != null && url.startsWith(PREFIX);
  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
    if (!acceptsURL(url)) {
      return new DriverPropertyInfo[0];
    }
    String real = url.substring(PREFIX.length());
    return driverFor(real).getPropertyInfo(real, info);
  }

  @Override
  public int getMajorVersion() {
    return 1;
  }

  @Override
  public int getMinorVersion() {
    return 0;
  }

  @Override
  public boolean jdbcCompliant() {
    return false;
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    throw new SQLFeatureNotSupportedException("WrappingDriver has no java.util.logging logger");
  }

  private Driver driverFor(String realUrl) throws SQLException {
    Driver known = target;
    if (known != null && known.acceptsURL(realUrl)) {
      return known;
    }
    Iterator<Driver> drivers =
        ServiceLoader.load(Driver.class, WrappingDriver.class.getClassLoader()).iterator();
    // Bounded: a broken service file must not loop forever.
    for (int attempt = 0; attempt < 64; attempt++) {
      Driver candidate;
      try {
        if (!drivers.hasNext()) {
          break;
        }
        candidate = drivers.next();
      } catch (ServiceConfigurationError e) {
        continue; // a driver jar whose class cannot be loaded here
      }
      if (!(candidate instanceof WrappingDriver) && candidate.acceptsURL(realUrl)) {
        target = candidate;
        return candidate;
      }
    }
    throw new SQLException("No JDBC driver on the class path accepts " + scheme(realUrl) + " URLs");
  }

  /** {@code jdbc:<vendor>} of a URL, never its host, account or parameters. */
  private static String scheme(String url) {
    int second = url.indexOf(':', 5);
    return second > 0 ? url.substring(0, second) : "jdbc";
  }
}
