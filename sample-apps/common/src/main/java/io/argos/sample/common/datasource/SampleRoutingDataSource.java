/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.datasource;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The outermost level of the SAMPLE_ROUTING_DS chain: routes read-only transactions to "replica"
 * and everything else to "primary". Both keys point to the same TransactionAwareDataSourceProxy, so
 * the routing logic runs on every connection without a second database. Counts its lookups so that
 * GET /api/runtime shows whether the app's connections really went through it.
 */
public final class SampleRoutingDataSource extends AbstractRoutingDataSource {

  public static final String PRIMARY = "primary";
  public static final String REPLICA = "replica";

  private final AtomicLong lookups = new AtomicLong();

  @Override
  protected Object determineCurrentLookupKey() {
    lookups.incrementAndGet();
    return TransactionSynchronizationManager.isCurrentTransactionReadOnly() ? REPLICA : PRIMARY;
  }

  /** How often a connection was routed (one lookup per {@code getConnection()}). */
  public long lookups() {
    return lookups.get();
  }
}
