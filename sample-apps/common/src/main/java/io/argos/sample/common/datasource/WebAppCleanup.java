/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.datasource;

import io.argos.sample.common.db.DbKind;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shutdown cleanup of the WAR apps (ADR-0010 C-162 (1), WO-50): what the web application itself
 * leaves behind at undeploy, so that the standalone Tomcat's leak report of a run without an agent
 * is empty and every SEVERE line of an agent run is the agent's.
 *
 * <ul>
 *   <li>{@link #deregisterDrivers(ClassLoader)}: the JDBC drivers of {@code WEB-INF/lib} that the
 *       app registered in the JVM-wide {@link DriverManager} (Tomcat: "registered the JDBC driver
 *       ... but failed to unregister it"; the registration keeps the web app class loader alive),
 *       and MySQL Connector/J's abandoned connection cleanup thread when its driver was one of
 *       them.
 *   <li>{@link #clearNettyThreadLocals()}: Netty's thread-local map ({@code
 *       io.netty.util.internal.InternalThreadLocalMap}) of the calling thread. Reactor Netty
 *       (WebClient of shop-war-jakarta) puts it on Tomcat's request threads; a filter of the WAR
 *       app clears it at the end of every request.
 * </ul>
 *
 * Uses {@code java.util.logging} (the container's log) and reflection for the optional libraries:
 * common does not depend on MySQL Connector/J or Netty.
 */
public final class WebAppCleanup {

  private static final Logger LOG = Logger.getLogger(WebAppCleanup.class.getName());

  /** MySQL Connector/J's driver and its cleanup thread (8.0.22 and later: checkedShutdown()). */
  static final String MYSQL_DRIVER = "com.mysql.cj.jdbc.Driver";

  static final String MYSQL_CLEANUP_THREAD = "com.mysql.cj.jdbc.AbandonedConnectionCleanupThread";

  static final String NETTY_FAST_THREAD_LOCAL = "io.netty.util.concurrent.FastThreadLocal";

  private static volatile MethodHandle nettyRemoveAll;
  private static volatile boolean nettyResolved;

  private WebAppCleanup() {}

  /**
   * Deregisters every driver in {@link DriverManager} whose class {@code loader} defined (the web
   * app's), then stops MySQL's cleanup thread when its driver was among them. Returns the class
   * names of the deregistered drivers.
   */
  @SuppressWarnings("ReferenceEquality") // the very class loader, not an equal one
  public static List<String> deregisterDrivers(ClassLoader loader) {
    List<String> names = new ArrayList<String>();
    Enumeration<Driver> drivers = DriverManager.getDrivers();
    for (Driver driver : Collections.list(drivers)) {
      if (driver.getClass().getClassLoader() != loader) {
        continue;
      }
      try {
        DriverManager.deregisterDriver(driver);
        names.add(driver.getClass().getName());
      } catch (SQLException | SecurityException e) {
        LOG.log(Level.WARNING, "Deregistering " + driver.getClass().getName() + " failed", e);
      }
    }
    if (names.contains(MYSQL_DRIVER)) {
      stopMysqlCleanupThread(loader);
    }
    if (!names.isEmpty()) {
      LOG.info("Deregistered the JDBC drivers of the web application: " + names);
    }
    return names;
  }

  private static void stopMysqlCleanupThread(ClassLoader loader) {
    try {
      Class<?> type = Class.forName(MYSQL_CLEANUP_THREAD, true, loader);
      type.getMethod("checkedShutdown").invoke(null);
    } catch (ReflectiveOperationException | LinkageError e) {
      LOG.log(Level.WARNING, "Stopping MySQL's abandoned connection cleanup thread failed", e);
    }
  }

  /**
   * SHUTDOWN of the in-memory H2 database (SAMPLE_DB=h2 with a {@code jdbc:h2:mem:} URL) on a
   * pooled connection: H2 keeps an open database and its JVM shutdown hook, both classes of the web
   * app class loader, after an undeploy. Returns whether SHUTDOWN ran (false for other databases).
   * shop-boot2 has the same step as a bean ({@code EmbeddedDatabaseShutdown}).
   */
  public static boolean shutdownInMemoryH2(SampleDataSource dataSource) {
    if (dataSource.db() != DbKind.H2) {
      return false;
    }
    Connection connection;
    try {
      connection = dataSource.pool().getConnection();
      if (!connection.getMetaData().getURL().startsWith("jdbc:h2:mem:")) {
        connection.close();
        return false;
      }
    } catch (SQLException e) {
      LOG.log(Level.WARNING, "No connection for H2 SHUTDOWN at context close", e);
      return false;
    }
    boolean done = false;
    try (Statement statement = connection.createStatement()) {
      statement.execute("SHUTDOWN");
      done = true;
    } catch (SQLException e) {
      LOG.log(Level.WARNING, "H2 SHUTDOWN at context close failed", e);
    } finally {
      try {
        // After SHUTDOWN the pool's reset fails and the pool discards the connection: expected.
        connection.close();
      } catch (SQLException e) {
        LOG.log(done ? Level.FINE : Level.WARNING, "Returning the H2 connection failed", e);
      }
    }
    return done;
  }

  /**
   * Removes Netty's thread-local map of the calling thread ({@code FastThreadLocal.removeAll()});
   * nothing when Netty is not on the class path. Cheap: the method is looked up once.
   */
  public static void clearNettyThreadLocals() {
    MethodHandle removeAll = nettyRemoveAll();
    if (removeAll == null) {
      return;
    }
    try {
      removeAll.invokeExact();
    } catch (Throwable e) { // invokeExact declares Throwable; removeAll() throws nothing checked
      LOG.log(Level.FINE, "FastThreadLocal.removeAll() failed", e);
    }
  }

  private static MethodHandle nettyRemoveAll() {
    if (!nettyResolved) {
      MethodHandle handle = null;
      try {
        Class<?> type =
            Class.forName(NETTY_FAST_THREAD_LOCAL, false, WebAppCleanup.class.getClassLoader());
        handle =
            MethodHandles.publicLookup()
                .findStatic(type, "removeAll", MethodType.methodType(void.class));
      } catch (ReflectiveOperationException | LinkageError e) {
        handle = null;
      }
      nettyRemoveAll = handle;
      nettyResolved = true;
    }
    return nettyRemoveAll;
  }
}
