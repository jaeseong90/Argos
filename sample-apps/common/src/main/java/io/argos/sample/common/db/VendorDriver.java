/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.db;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * The vendor JDBC drivers of SAMPLE_DB=tibero, altibase, cubrid and db2 (WO-50, the nine databases
 * of 18 §6.3): their jars are not redistributable or not in the build (docs/licenses/dev-only.md
 * condition 4), so a trusted runner puts them into the folder {@code ARGOS_TEST_JDBC_DRIVER_DIR}
 * and the app loads them itself, in a class loader below its own (the Spring Boot jar launcher has
 * no external class path). The pools are then configured with this class: a {@code java.sql.Driver}
 * with a no-argument constructor that hands every call to the vendor driver, so the pool's own
 * driver loading works unchanged. It is also registered in {@link DriverManager}, for {@code
 * SAMPLE_DRIVER_WRAPPER}. Connections and statements are the vendor driver's own objects (the agent
 * instruments them as in any application; this class lives in io.argos and is not instrumented).
 *
 * <p>A vendor driver that is already on the app's class path is used directly.
 */
public final class VendorDriver implements Driver {

  private static Driver delegate;
  private static DbKind installed;

  /** For the pools: the installed vendor driver (see {@link #driverClassName}). */
  public VendorDriver() {
    if (current() == null) {
      throw new IllegalStateException("No vendor JDBC driver installed (VendorDriver)");
    }
  }

  /**
   * The driver class name the pools of {@code db} are configured with: {@code db}'s own driver when
   * the class path has it, otherwise this class after loading the vendor driver from {@code
   * driverDir} (once per process).
   *
   * @throws IllegalArgumentException when the driver is not on the class path and {@code driverDir}
   *     is unset, missing or has no jar with the driver
   */
  public static synchronized String driverClassName(DbKind db, String driverDir) {
    if (installed == db) {
      return VendorDriver.class.getName();
    }
    ClassLoader appLoader = VendorDriver.class.getClassLoader();
    if (onClassPath(db.driverClass(), appLoader)) {
      return db.driverClass();
    }
    if (installed != null) {
      throw new IllegalStateException(
          "VendorDriver holds the " + installed.id() + " driver already, not " + db.id());
    }
    if (driverDir == null) {
      throw new IllegalArgumentException(
          "SAMPLE_DB="
              + db.id()
              + " needs its JDBC driver jar ("
              + db.driverClass()
              + "): set SAMPLE_JDBC_DRIVER_DIR (or ARGOS_TEST_JDBC_DRIVER_DIR) to a folder that contains it");
    }
    URL[] jars = jars(new File(driverDir), db);
    @SuppressWarnings("resource") // the vendor classes stay loaded for the life of the process
    URLClassLoader loader = new URLClassLoader(jars, appLoader);
    try {
      Driver driver =
          Class.forName(db.driverClass(), true, loader)
              .asSubclass(Driver.class)
              .getDeclaredConstructor()
              .newInstance();
      delegate = driver;
      installed = db;
      DriverManager.registerDriver(new VendorDriver());
    } catch (ReflectiveOperationException | LinkageError | SQLException | ClassCastException e) {
      throw new IllegalArgumentException(
          "The JDBC driver folder has no usable "
              + db.driverClass()
              + " (SAMPLE_DB="
              + db.id()
              + ")",
          e);
    }
    return VendorDriver.class.getName();
  }

  private static boolean onClassPath(String className, ClassLoader loader) {
    try {
      Class.forName(className, false, loader);
      return true;
    } catch (ClassNotFoundException | LinkageError notOnClassPath) {
      return false;
    }
  }

  /** The {@code *.jar} files of {@code dir}, in name order. */
  public static URL[] jars(File dir, DbKind db) {
    File[] files = dir.listFiles((parent, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
    if (files == null || files.length == 0) {
      throw new IllegalArgumentException(
          "The JDBC driver folder ("
              + dir
              + ") has no .jar file for SAMPLE_DB="
              + db.id()
              + " ("
              + db.driverClass()
              + ")");
    }
    Arrays.sort(files);
    List<URL> urls = new ArrayList<URL>();
    for (File file : files) {
      try {
        urls.add(file.toURI().toURL());
      } catch (MalformedURLException e) {
        throw new IllegalArgumentException("Cannot use the driver jar " + file, e);
      }
    }
    return urls.toArray(new URL[0]);
  }

  private static synchronized Driver current() {
    return delegate;
  }

  private static Driver target() throws SQLException {
    Driver driver = current();
    if (driver == null) {
      throw new SQLException("No vendor JDBC driver installed (VendorDriver)");
    }
    return driver;
  }

  @Override
  public Connection connect(String url, Properties info) throws SQLException {
    return target().connect(url, info);
  }

  @Override
  public boolean acceptsURL(String url) throws SQLException {
    return target().acceptsURL(url);
  }

  @Override
  public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
    return target().getPropertyInfo(url, info);
  }

  @Override
  public int getMajorVersion() {
    Driver driver = current();
    return driver == null ? 0 : driver.getMajorVersion();
  }

  @Override
  public int getMinorVersion() {
    Driver driver = current();
    return driver == null ? 0 : driver.getMinorVersion();
  }

  @Override
  public boolean jdbcCompliant() {
    Driver driver = current();
    return driver != null && driver.jdbcCompliant();
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    Driver driver = current();
    if (driver == null) {
      throw new SQLFeatureNotSupportedException("No vendor JDBC driver installed (VendorDriver)");
    }
    return driver.getParentLogger();
  }
}
