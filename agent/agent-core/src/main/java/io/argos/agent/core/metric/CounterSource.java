/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric;

import java.util.Map;

/**
 * More agent self metrics for {@link MetricCollector} (for example the statistics aggregator's
 * {@code argos_agent_stats_overflow_total{kind}}): values by OpenMetrics series name. A name ending
 * in {@code _total} is a counter (cumulative value), any other a gauge.
 */
public interface CounterSource {
  /** The current values; called on the metrics thread, must not block, a bounded map. */
  Map<String, Long> counters();
}
