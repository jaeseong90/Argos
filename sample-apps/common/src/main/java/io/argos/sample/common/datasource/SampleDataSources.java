/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.datasource;

import example.jdbcwrapper.WrappingDriver;
import io.argos.sample.common.SampleConfig;
import io.argos.sample.common.db.ShopSchema;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import javax.sql.DataSource;

/**
 * Builds the data source of SAMPLE_DB / SAMPLE_POOL / SAMPLE_POOL_MAX / SAMPLE_ROUTING_DS (18 §4.1,
 * §4.4), creates the schema and data, and describes the wrapper chain.
 *
 * <p>With SAMPLE_DRIVER_WRAPPER=true the pool connects through {@link WrappingDriver}, a delegating
 * {@code java.sql.Driver}, {@code Connection} and {@code Statement} wrapper (the fifth level of
 * ADR-0004 C-40; combinable with SAMPLE_ROUTING_DS).
 *
 * <p>With SAMPLE_ROUTING_DS=true the app gets the four-level chain of the JDBC nesting fixture
 * (D-24): {@code SampleRoutingDataSource (AbstractRoutingDataSource) ->
 * TransactionAwareDataSourceProxy -> pool -> driver}. The pool classes and Spring JDBC must be on
 * the app's class path; a missing one fails the start with the artifact to add.
 *
 * <p>With SAMPLE_SECOND_POOL=true a second pool of the same kind on the same database (the
 * multi-pool application of the agent's pool registry, 10 §6.4) sets up the schema and data, is
 * closed and is created again under the same name with another size (a pool restart); the app's own
 * pool and the restarted second pool then live side by side. Hikari and Tomcat JDBC pools are named
 * {@value #PRIMARY_POOL_NAME} and {@value #SECONDARY_POOL_NAME}; DBCP2 pools have no name.
 *
 * <p>The described chain follows the objects the app gets (outermost DataSource inwards), not the
 * switches, so GET /api/runtime shows what was really built.
 */
public final class SampleDataSources {

  /** Name of the app's pool with SAMPLE_SECOND_POOL=true (Hikari, Tomcat JDBC). */
  public static final String PRIMARY_POOL_NAME = "shop-primary";

  /** Name of the second pool of SAMPLE_SECOND_POOL=true (Hikari, Tomcat JDBC). */
  public static final String SECONDARY_POOL_NAME = "shop-secondary";

  /** Size of the second pool that sets up the schema (before its restart). */
  public static final int SECONDARY_SETUP_MAX_SIZE = 2;

  /** Size of the restarted second pool (differs from the first so the restart shows). */
  public static final int SECONDARY_MAX_SIZE = 3;

  private SampleDataSources() {}

  public static SampleDataSource create(SampleConfig config) {
    PoolHandle secondary = config.secondPool() ? secondaryPool(config) : null;
    PoolHandle pool =
        createPool(config, secondary == null ? null : PRIMARY_POOL_NAME, config.poolMaxSize());
    if (secondary == null) {
      ShopSchema.initialize(pool.dataSource, config.db());
    }
    Runnable closer = pool.closer;
    if (secondary != null) {
      closer =
          () -> {
            try {
              pool.closer.run();
            } finally {
              secondary.closer.run();
            }
          };
    }
    DataSource outer = pool.dataSource;
    if (config.routingDataSource()) {
      outer = routing(pool.dataSource);
    }
    List<String> chain = new ArrayList<>();
    DataSource inner = outer;
    LongSupplier routingLookups = () -> 0L;
    if (config.routingDataSource()) {
      // Spring JDBC is present (routing() checked it): walk the wrappers the app really gets.
      inner = RoutingChain.describe(outer, chain);
      routingLookups = RoutingChain.lookupCounter(outer);
    }
    // Simple class names, except Tomcat JDBC's ambiguous "DataSource".
    Class<?> poolClass = inner.getClass();
    chain.add(
        poolClass.getSimpleName().equals("DataSource")
            ? poolClass.getName()
            : poolClass.getSimpleName());
    LongSupplier wrapperConnections = () -> 0L;
    if (config.driverWrapper()) {
      // The pool reports the wrapper class; the real driver sits behind it (ADR-0004 C-40).
      chain.add("WrappingDriver (java.sql.Driver wrapper)");
      chain.add(config.db().driverClass());
      wrapperConnections = WrappingDriver::connections;
    } else {
      chain.add(pool.driverClass);
    }
    return new SampleDataSource(
        outer,
        pool.dataSource,
        config.pool(),
        config.poolMaxSize(),
        config.db(),
        config.routingDataSource(),
        chain,
        routingLookups,
        config.driverWrapper(),
        wrapperConnections,
        secondary != null,
        closer);
  }

  /**
   * SAMPLE_SECOND_POOL: sets up the schema and data through a second pool, closes it and creates it
   * again under the same name with {@link #SECONDARY_MAX_SIZE} connections; one connection is taken
   * and returned, so that pools created on first use (Tomcat JDBC, DBCP2) exist too.
   */
  private static PoolHandle secondaryPool(SampleConfig config) {
    PoolHandle setup = createPool(config, SECONDARY_POOL_NAME, SECONDARY_SETUP_MAX_SIZE);
    try {
      ShopSchema.initialize(setup.dataSource, config.db());
    } finally {
      setup.closer.run();
    }
    PoolHandle restarted = createPool(config, SECONDARY_POOL_NAME, SECONDARY_MAX_SIZE);
    try (Connection connection = restarted.dataSource.getConnection()) {
      connection.isValid(5);
    } catch (SQLException e) {
      restarted.closer.run();
      throw new IllegalStateException("The restarted second pool cannot connect", e);
    }
    return restarted;
  }

  private static PoolHandle createPool(SampleConfig config, String name, int maxSize) {
    switch (config.pool()) {
      case HIKARI:
        return guard("com.zaxxer:HikariCP", () -> HikariPools.create(config, name, maxSize));
      case TOMCAT:
        return guard(
            "org.apache.tomcat:tomcat-jdbc", () -> TomcatPools.create(config, name, maxSize));
      case DBCP2:
        return guard("org.apache.commons:commons-dbcp2", () -> Dbcp2Pools.create(config, maxSize));
    }
    throw new AssertionError(config.pool());
  }

  private static DataSource routing(DataSource pool) {
    try {
      return RoutingChain.wrap(pool);
    } catch (NoClassDefFoundError e) {
      throw new IllegalStateException(
          "SAMPLE_ROUTING_DS=true needs org.springframework:spring-jdbc on the class path", e);
    }
  }

  private interface PoolFactory {
    PoolHandle create();
  }

  private static PoolHandle guard(String artifact, PoolFactory factory) {
    try {
      return factory.create();
    } catch (NoClassDefFoundError e) {
      throw new IllegalStateException("SAMPLE_POOL needs " + artifact + " on the class path", e);
    }
  }

  /** A pool, the driver class it was configured with, and how to close it. */
  static final class PoolHandle {
    final DataSource dataSource;
    final String driverClass;
    final Runnable closer;

    PoolHandle(DataSource dataSource, String driverClass, Runnable closer) {
      this.dataSource = dataSource;
      this.driverClass = driverClass;
      this.closer = closer;
    }
  }
}
