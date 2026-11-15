/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.datasource;

import io.argos.sample.common.db.DbKind;
import io.argos.sample.common.db.PoolKind;
import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;
import javax.sql.DataSource;

/**
 * The app's data source as built by {@link SampleDataSources}: the outermost {@link DataSource}
 * (what the app uses), the pool inside it, and the wrapper chain for GET /api/runtime. Closing it
 * closes the pool (and the second pool of SAMPLE_SECOND_POOL).
 */
public final class SampleDataSource implements Closeable {

  private final DataSource dataSource;
  private final DataSource pool;
  private final PoolKind poolKind;
  private final int maxPoolSize;
  private final DbKind db;
  private final boolean routing;
  private final List<String> chain;
  private final LongSupplier routingLookups;
  private final boolean driverWrapper;
  private final LongSupplier wrapperConnections;
  private final boolean secondPool;
  private final Runnable closer;

  SampleDataSource(
      DataSource dataSource,
      DataSource pool,
      PoolKind poolKind,
      int maxPoolSize,
      DbKind db,
      boolean routing,
      List<String> chain,
      LongSupplier routingLookups,
      boolean driverWrapper,
      LongSupplier wrapperConnections,
      boolean secondPool,
      Runnable closer) {
    this.dataSource = dataSource;
    this.pool = pool;
    this.poolKind = poolKind;
    this.maxPoolSize = maxPoolSize;
    this.db = db;
    this.routing = routing;
    this.chain = Collections.unmodifiableList(new ArrayList<>(chain));
    this.routingLookups = routingLookups;
    this.driverWrapper = driverWrapper;
    this.wrapperConnections = wrapperConnections;
    this.secondPool = secondPool;
    this.closer = closer;
  }

  /** The DataSource the app uses (the routing data source when SAMPLE_ROUTING_DS=true). */
  public DataSource dataSource() {
    return dataSource;
  }

  /** The connection pool itself. */
  public DataSource pool() {
    return pool;
  }

  public PoolKind poolKind() {
    return poolKind;
  }

  public int maxPoolSize() {
    return maxPoolSize;
  }

  public DbKind db() {
    return db;
  }

  /** Whether SAMPLE_ROUTING_DS built the four-level chain. */
  public boolean routing() {
    return routing;
  }

  /** Outermost to innermost, e.g. SampleRoutingDataSource, TransactionAwareDataSourceProxy, .. */
  public List<String> chain() {
    return chain;
  }

  /**
   * How many connections the routing data source of {@link #dataSource()} has routed so far; 0 when
   * the app's DataSource is not the routing data source.
   */
  public long routingLookups() {
    return routingLookups.getAsLong();
  }

  /** Whether SAMPLE_DRIVER_WRAPPER put the driver wrapper between the pool and the driver. */
  public boolean driverWrapper() {
    return driverWrapper;
  }

  /**
   * Connections opened through the driver wrapper so far (&gt; 0 shows that the pool really
   * connects through it); 0 without the wrapper.
   */
  public long driverWrapperConnections() {
    return wrapperConnections.getAsLong();
  }

  /**
   * Whether SAMPLE_SECOND_POOL keeps a second, restarted pool next to {@link #pool()} (closed with
   * it).
   */
  public boolean secondPool() {
    return secondPool;
  }

  @Override
  public void close() {
    closer.run();
  }
}
