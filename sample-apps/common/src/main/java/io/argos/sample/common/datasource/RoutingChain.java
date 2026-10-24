/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.datasource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * Builds {@code SampleRoutingDataSource -> TransactionAwareDataSourceProxy -> pool} (the driver is
 * the fourth level) and describes such a chain by walking the objects. Loaded only when
 * SAMPLE_ROUTING_DS=true (it references Spring JDBC types).
 */
final class RoutingChain {

  /** Upper bound of wrapper levels followed by {@link #describe} (guards against cycles). */
  private static final int MAX_LEVELS = 16;

  private RoutingChain() {}

  static DataSource wrap(DataSource pool) {
    TransactionAwareDataSourceProxy proxy = new TransactionAwareDataSourceProxy(pool);
    Map<Object, Object> targets = new HashMap<>();
    targets.put(SampleRoutingDataSource.PRIMARY, proxy);
    targets.put(SampleRoutingDataSource.REPLICA, proxy);
    SampleRoutingDataSource routing = new SampleRoutingDataSource();
    routing.setTargetDataSources(targets);
    routing.setDefaultTargetDataSource(proxy);
    routing.afterPropertiesSet();
    return routing;
  }

  /**
   * Follows the Spring wrappers from {@code outer} inwards and adds one entry per wrapper to {@code
   * chain}: an {@link AbstractRoutingDataSource} (its resolved default target) as "SimpleName
   * (AbstractRoutingDataSource)", a {@link DelegatingDataSource} such as {@link
   * TransactionAwareDataSourceProxy} (its target) by its simple name. Returns the first object that
   * is neither, normally the pool.
   */
  static DataSource describe(DataSource outer, List<String> chain) {
    DataSource current = outer;
    for (int level = 0; level < MAX_LEVELS; level++) {
      DataSource next;
      if (current instanceof AbstractRoutingDataSource) {
        chain.add(current.getClass().getSimpleName() + " (AbstractRoutingDataSource)");
        next = ((AbstractRoutingDataSource) current).getResolvedDefaultDataSource();
      } else if (current instanceof DelegatingDataSource) {
        chain.add(current.getClass().getSimpleName());
        next = ((DelegatingDataSource) current).getTargetDataSource();
      } else {
        return current;
      }
      if (next == null) {
        throw new IllegalStateException(
            current.getClass().getName()
                + " has no target DataSource (chain so far: "
                + chain
                + ")");
      }
      current = next;
    }
    throw new IllegalStateException("More than " + MAX_LEVELS + " DataSource wrappers: " + chain);
  }

  /** The lookup counter of {@code outer} when it is a {@link SampleRoutingDataSource}, else 0. */
  static LongSupplier lookupCounter(DataSource outer) {
    if (outer instanceof SampleRoutingDataSource) {
      SampleRoutingDataSource routing = (SampleRoutingDataSource) outer;
      return routing::lookups;
    }
    return () -> 0L;
  }
}
