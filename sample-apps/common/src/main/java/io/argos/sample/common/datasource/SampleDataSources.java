/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.datasource;

import io.argos.sample.common.SampleConfig;
import io.argos.sample.common.db.ShopSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import javax.sql.DataSource;

/**
 * Builds the data source of SAMPLE_DB / SAMPLE_POOL / SAMPLE_POOL_MAX / SAMPLE_ROUTING_DS (18 §4.1,
 * §4.4), creates the schema and data, and describes the wrapper chain.
 *
 * <p>With SAMPLE_ROUTING_DS=true the app gets the four-level chain of the JDBC nesting fixture
 * (D-24): {@code SampleRoutingDataSource (AbstractRoutingDataSource) ->
 * TransactionAwareDataSourceProxy -> pool -> driver}. The pool classes and Spring JDBC must be on
 * the app's class path; a missing one fails the start with the artifact to add.
 *
 * <p>The described chain follows the objects the app gets (outermost DataSource inwards), not the
 * switches, so GET /api/runtime shows what was really built.
 */
public final class SampleDataSources {

  private SampleDataSources() {}

  public static SampleDataSource create(SampleConfig config) {
    PoolHandle pool = createPool(config);
    ShopSchema.initialize(pool.dataSource, config.db());
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
    chain.add(pool.driverClass);
    return new SampleDataSource(
        outer,
        pool.dataSource,
        config.pool(),
        config.poolMaxSize(),
        config.db(),
        config.routingDataSource(),
        chain,
        routingLookups,
        pool.closer);
  }

  private static PoolHandle createPool(SampleConfig config) {
    switch (config.pool()) {
      case HIKARI:
        return guard("com.zaxxer:HikariCP", () -> HikariPools.create(config));
      case TOMCAT:
        return guard("org.apache.tomcat:tomcat-jdbc", () -> TomcatPools.create(config));
      case DBCP2:
        return guard("org.apache.commons:commons-dbcp2", () -> Dbcp2Pools.create(config));
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
