/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.batch;

import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.ProfileBatch;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.TransactionBatch;
import io.argos.server.collector.limits.InstanceQuotas;
import io.argos.server.collector.limits.QuotaKind;
import java.util.Objects;

/**
 * Applies the instance quotas to a batch (12 §3.3, DSN-12-04, ADR-0010 C-130 (1)): the leading
 * items the instance's bucket covers are kept and the rest is removed <b>from the end of the
 * batch</b>; the batch is never refused. The removed count goes into {@code Ack.dropped_items} with
 * {@code ACK_PARTIAL_DROP} and {@code argos_collector_dropped_total{reason="quota"}}.
 *
 * <ul>
 *   <li>transactions: records (quota {@link QuotaKind#TRANSACTIONS});
 *   <li>profiles: whole profiles from the end, a profile costing its steps (quota {@link
 *       QuotaKind#STEPS}; a profile is never cut in the middle);
 *   <li>metrics: samples (quota {@link QuotaKind#METRIC_SAMPLES}); series stay;
 *   <li>statistics: rows in wire order, SQL rows then outgoing-call rows, so outgoing-call rows go
 *       first (quota {@link QuotaKind#STAT_ROWS}); the window counters ({@code CounterDelta}),
 *       which become {@code metric} rows (12 §3.6), are charged to the instance's metric bucket
 *       (quota {@link QuotaKind#METRIC_SAMPLES}, SEC-12) and trimmed from their end the same way.
 *       Both removed counts add up in {@code dropped_items}.
 * </ul>
 *
 * <p>The {@code refund} methods give back the tokens a trimmed batch took when that batch is then
 * not written to the WAL (WO-21 follow-up N-1): the amounts are computed again from the kept batch
 * (records, the steps of the kept profiles, samples, SQL + outgoing-call rows and counters), which
 * is exactly what {@code trim} charged for it.
 */
public final class QuotaTrimmer {

  private final InstanceQuotas quotas;

  public QuotaTrimmer(InstanceQuotas quotas) {
    this.quotas = Objects.requireNonNull(quotas, "quotas");
  }

  /** {@code SendTransactions}. */
  public Shaped<TransactionBatch> trim(int instanceId, TransactionBatch batch) {
    int count = batch.getRecordsCount();
    int kept = quotas.take(instanceId, QuotaKind.TRANSACTIONS, count);
    if (kept == count) {
      return Shaped.unchanged(batch);
    }
    TransactionBatch out =
        batch.toBuilder()
            .clearRecords()
            .addAllRecords(batch.getRecordsList().subList(0, kept))
            .build();
    return new Shaped<>(out, count - kept);
  }

  /** {@code SendProfiles}. */
  public Shaped<ProfileBatch> trim(int instanceId, ProfileBatch batch) {
    int count = batch.getProfilesCount();
    int kept =
        quotas.takePrefix(
            instanceId, QuotaKind.STEPS, i -> batch.getProfiles(i).getStepsCount(), count);
    if (kept == count) {
      return Shaped.unchanged(batch);
    }
    ProfileBatch out =
        batch.toBuilder()
            .clearProfiles()
            .addAllProfiles(batch.getProfilesList().subList(0, kept))
            .build();
    return new Shaped<>(out, count - kept);
  }

  /** {@code SendMetrics}. */
  public Shaped<MetricBatch> trim(int instanceId, MetricBatch batch) {
    int count = batch.getSamplesCount();
    int kept = quotas.take(instanceId, QuotaKind.METRIC_SAMPLES, count);
    if (kept == count) {
      return Shaped.unchanged(batch);
    }
    MetricBatch out =
        batch.toBuilder()
            .clearSamples()
            .addAllSamples(batch.getSamplesList().subList(0, kept))
            .build();
    return new Shaped<>(out, count - kept);
  }

  /** {@code SendStats}. */
  public Shaped<StatBatch> trim(int instanceId, StatBatch batch) {
    int sql = batch.getSqlCount();
    int count = sql + batch.getExtCount();
    int kept = quotas.take(instanceId, QuotaKind.STAT_ROWS, count);
    int counters = batch.getCountersCount();
    int keptCounters = quotas.take(instanceId, QuotaKind.METRIC_SAMPLES, counters);
    if (kept == count && keptCounters == counters) {
      return Shaped.unchanged(batch);
    }
    StatBatch.Builder out = batch.toBuilder();
    if (kept < count) {
      out.clearSql().clearExt();
      out.addAllSql(batch.getSqlList().subList(0, Math.min(sql, kept)));
      if (kept > sql) {
        out.addAllExt(batch.getExtList().subList(0, kept - sql));
      }
    }
    if (keptCounters < counters) {
      out.clearCounters().addAllCounters(batch.getCountersList().subList(0, keptCounters));
    }
    return new Shaped<>(out.build(), (count - kept) + (counters - keptCounters));
  }

  /** Gives back what {@link #trim(int, TransactionBatch)} charged for {@code kept}. */
  public void refund(int instanceId, TransactionBatch kept) {
    quotas.giveBack(instanceId, QuotaKind.TRANSACTIONS, kept.getRecordsCount());
  }

  /** Gives back what {@link #trim(int, ProfileBatch)} charged for {@code kept}. */
  public void refund(int instanceId, ProfileBatch kept) {
    double steps = 0;
    for (int i = 0; i < kept.getProfilesCount(); i++) {
      steps += kept.getProfiles(i).getStepsCount();
    }
    quotas.giveBack(instanceId, QuotaKind.STEPS, steps);
  }

  /** Gives back what {@link #trim(int, MetricBatch)} charged for {@code kept}. */
  public void refund(int instanceId, MetricBatch kept) {
    quotas.giveBack(instanceId, QuotaKind.METRIC_SAMPLES, kept.getSamplesCount());
  }

  /**
   * Gives back what {@link #trim(int, StatBatch)} charged for {@code kept}: its rows to the
   * statistics bucket and its counters to the metric bucket.
   */
  public void refund(int instanceId, StatBatch kept) {
    quotas.giveBack(instanceId, QuotaKind.STAT_ROWS, kept.getSqlCount() + kept.getExtCount());
    quotas.giveBack(instanceId, QuotaKind.METRIC_SAMPLES, kept.getCountersCount());
  }
}
