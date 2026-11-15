/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.limits;

import io.argos.server.config.ArgosProperties;

/**
 * The per-instance throughput quotas (12 §3.3, 16 §4.3, SEC-12; ADR-0010 C-130 (4)) and the item
 * each one counts.
 */
public enum QuotaKind {
  /** {@code TransactionRecord}s, {@code argos.collector.quota.tx-per-second}. */
  TRANSACTIONS,
  /** {@code ProfileStep}s, {@code argos.collector.quota.steps-per-second}. */
  STEPS,
  /**
   * {@code MetricSample}s and the {@code CounterDelta}s of statistics batches (both become {@code
   * metric} rows, 12 §3.6), {@code argos.collector.quota.metrics-per-5s}.
   */
  METRIC_SAMPLES,
  /** {@code SqlStat} + {@code ExtStat} rows, {@code argos.collector.quota.stats-per-10s}. */
  STAT_ROWS;

  /** Items per second of this quota under {@code quota}. */
  public double ratePerSecond(ArgosProperties.Collector.Quota quota) {
    return switch (this) {
      case TRANSACTIONS -> quota.txPerSecond();
      case STEPS -> quota.stepsPerSecond();
      case METRIC_SAMPLES -> quota.metricsPer5s() / 5.0;
      case STAT_ROWS -> quota.statsPer10s() / 10.0;
    };
  }

  /**
   * Bucket capacity: twice the amount of one quota period (16 §4.3 "용량 = 2× 초당 한도", C-130 (4) "버킷
   * 용량은 2배").
   */
  public double capacity(ArgosProperties.Collector.Quota quota) {
    return switch (this) {
      case TRANSACTIONS -> 2.0 * quota.txPerSecond();
      case STEPS -> 2.0 * quota.stepsPerSecond();
      case METRIC_SAMPLES -> 2.0 * quota.metricsPer5s();
      case STAT_ROWS -> 2.0 * quota.statsPer10s();
    };
  }
}
