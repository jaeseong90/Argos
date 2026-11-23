/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric;

import java.util.Map;

/**
 * The connection waits per pool (13 §3.5, ADR-0007 C-68: connection wait is a pool metric, not a
 * SQL statistic): the source of {@code jdbc_pool_wait_count_total{pool}} and {@code
 * jdbc_pool_wait_seconds_total{pool}}. The statistics side implements it from the DBC steps.
 */
public interface PoolWaitSource {
  /**
   * Cumulative waits by pool name since the start: {@code {count, nanos}} per pool, at most 64
   * pools. Called on the metrics thread; must not block and should not throw (a failure leaves the
   * series out of that collection).
   */
  Map<String, long[]> poolWaits();
}
