/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.batch;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.CounterDelta;
import io.argos.proto.v1.ExtStat;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.MetricSample;
import io.argos.proto.v1.ProfileBatch;
import io.argos.proto.v1.ProfileStep;
import io.argos.proto.v1.SqlStat;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.TransactionProfile;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.collector.limits.InstanceQuotas;
import io.argos.server.config.ArgosProperties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-SV-013 unit part: per-instance token buckets (rate = quota, capacity = twice the period) and
 * trimming from the end of the batch (DSN-12-04, ADR-0010 C-130 (1) and (4)). The bucket clock is
 * stepped by hand, so no wall-clock time is involved (C-146 (2)).
 */
@Tag("req:SEC-12")
@Tag("req:DSN-12-04")
class QuotaTrimmerTest {

  private static final ArgosProperties.Collector.Quota QUOTA =
      new ArgosProperties.Collector.Quota(2_000, 20_000, 1_000, 5_000);

  private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
  private final QuotaTrimmer trimmer = new QuotaTrimmer(new InstanceQuotas(QUOTA, nanos::get));

  private static TransactionBatch tx(int n) {
    TransactionBatch.Builder b = TransactionBatch.newBuilder();
    for (int i = 1; i <= n; i++) {
      b.addRecords(TransactionRecord.newBuilder().setTxId(i));
    }
    return b.build();
  }

  private void advanceSeconds(double seconds) {
    nanos.addAndGet((long) (seconds * TimeUnit.SECONDS.toNanos(1)));
  }

  @Test
  @DisplayName(
      "T-SV-013 transactions: a full bucket holds 4,000; items beyond it are removed from the end")
  void transactionsFromTheEnd() {
    Shaped<TransactionBatch> first = trimmer.trim(1, tx(3_000));
    assertThat(first.droppedItems()).isZero();
    assertThat(first.batch().getRecordsCount()).isEqualTo(3_000);

    Shaped<TransactionBatch> second = trimmer.trim(1, tx(3_000));
    assertThat(second.droppedItems()).isEqualTo(2_000);
    assertThat(second.batch().getRecordsList())
        .extracting(TransactionRecord::getTxId)
        .as("the first 1,000 records are kept, the last 2,000 dropped")
        .containsExactlyElementsOf(
            java.util.stream.LongStream.rangeClosed(1, 1_000).boxed().toList());

    Shaped<TransactionBatch> empty = trimmer.trim(1, tx(10));
    assertThat(empty.droppedItems()).isEqualTo(10);
    assertThat(empty.batch().getRecordsCount()).isZero();

    advanceSeconds(1);
    Shaped<TransactionBatch> refilled = trimmer.trim(1, tx(2_500));
    assertThat(refilled.droppedItems()).as("one second refills 2,000").isEqualTo(500);
  }

  @Test
  @DisplayName("T-SV-013 one instance's quota never affects another instance")
  void instancesAreIndependent() {
    assertThat(trimmer.trim(1, tx(5_000)).droppedItems()).isEqualTo(1_000);
    assertThat(trimmer.trim(1, tx(100)).droppedItems()).isEqualTo(100);
    assertThat(trimmer.trim(2, tx(4_000)).droppedItems()).isZero();
  }

  @Test
  @DisplayName("T-SV-013 the bucket never holds more than twice the quota")
  void capacityCap() {
    advanceSeconds(3_600);
    assertThat(trimmer.trim(3, tx(4_001)).droppedItems()).isEqualTo(1);
  }

  @Test
  @DisplayName("T-SV-013 profiles: whole profiles are removed from the end, each costing its steps")
  void profiles() {
    ProfileBatch.Builder b = ProfileBatch.newBuilder();
    for (int p = 1; p <= 5; p++) {
      TransactionProfile.Builder profile = TransactionProfile.newBuilder().setTxId(p);
      for (int s = 0; s < 10_000; s++) {
        profile.addSteps(ProfileStep.newBuilder().setSeq(s + 1));
      }
      b.addProfiles(profile);
    }
    // capacity 40,000 steps: profiles 1-4 fit, profile 5 is dropped whole
    Shaped<ProfileBatch> shaped = trimmer.trim(4, b.build());
    assertThat(shaped.droppedItems()).isEqualTo(1);
    assertThat(shaped.batch().getProfilesList())
        .extracting(TransactionProfile::getTxId)
        .containsExactly(1L, 2L, 3L, 4L);
    assertThat(shaped.batch().getProfiles(3).getStepsCount()).isEqualTo(10_000);
  }

  @Test
  @DisplayName(
      "T-SV-013 metrics: 1,000 samples per 5 s, bucket 2,000; samples dropped from the end")
  void metrics() {
    MetricBatch.Builder b = MetricBatch.newBuilder();
    for (int i = 0; i < 2_500; i++) {
      b.addSamples(MetricSample.newBuilder().setTsMs(i));
    }
    Shaped<MetricBatch> shaped = trimmer.trim(5, b.build());
    assertThat(shaped.droppedItems()).isEqualTo(500);
    assertThat(shaped.batch().getSamples(1_999).getTsMs()).isEqualTo(1_999);
    advanceSeconds(5);
    assertThat(trimmer.trim(5, b.build()).droppedItems())
        .as("5 s refill 1,000 samples")
        .isEqualTo(1_500);
  }

  @Test
  @DisplayName(
      "T-SV-013 statistics: 5,000 rows per 10 s, bucket 10,000; outgoing-call rows go first")
  void stats() {
    StatBatch.Builder b = StatBatch.newBuilder();
    for (int i = 1; i <= 5_000; i++) {
      b.addSql(SqlStat.newBuilder().setSqlId(i));
    }
    for (int i = 1; i <= 1_000; i++) {
      b.addExt(ExtStat.newBuilder().setHostId(i));
    }
    StatBatch batch = b.build();
    assertThat(trimmer.trim(6, batch).droppedItems()).isZero();
    Shaped<StatBatch> second = trimmer.trim(6, batch);
    // 4,000 tokens left: 4,000 SQL rows kept, 1,000 SQL and 1,000 ext rows dropped
    assertThat(second.droppedItems()).isEqualTo(2_000);
    assertThat(second.batch().getSqlCount()).isEqualTo(4_000);
    assertThat(second.batch().getExtCount()).isZero();
    advanceSeconds(10);
    Shaped<StatBatch> third = trimmer.trim(6, batch);
    assertThat(third.batch().getSqlCount()).isEqualTo(5_000);
    assertThat(third.batch().getExtCount()).isZero();
    assertThat(third.droppedItems()).isEqualTo(1_000);
  }

  @Test
  @DisplayName(
      "T-SV-013 statistics counters (metric rows, 12 §3.6) are charged to the metric bucket and"
          + " removed from the end; the drops add up with the row drops")
  void statCounters() {
    StatBatch.Builder b = StatBatch.newBuilder();
    for (int i = 1; i <= 10; i++) {
      b.addSql(SqlStat.newBuilder().setSqlId(i));
    }
    for (int i = 0; i < 2_500; i++) {
      b.addCounters(CounterDelta.newBuilder().setName("c" + i).setDelta(1));
    }
    StatBatch batch = b.build();
    Shaped<StatBatch> first = trimmer.trim(8, batch);
    // metric bucket 2,000: counters 0..1,999 kept, the last 500 dropped; SQL rows untouched
    assertThat(first.droppedItems()).isEqualTo(500);
    assertThat(first.batch().getSqlCount()).isEqualTo(10);
    assertThat(first.batch().getCountersCount()).isEqualTo(2_000);
    assertThat(first.batch().getCounters(1_999).getName()).isEqualTo("c1999");

    // The same bucket as the metric samples: a metric batch now finds it empty.
    MetricBatch.Builder m = MetricBatch.newBuilder();
    for (int i = 0; i < 10; i++) {
      m.addSamples(MetricSample.newBuilder().setTsMs(i));
    }
    assertThat(trimmer.trim(8, m.build()).droppedItems()).isEqualTo(10);

    advanceSeconds(5);
    StatBatch.Builder both = StatBatch.newBuilder();
    for (int i = 1; i <= 5_000; i++) {
      both.addSql(SqlStat.newBuilder().setSqlId(i));
    }
    for (int i = 1; i <= 1_000; i++) {
      both.addExt(ExtStat.newBuilder().setHostId(i));
    }
    for (int i = 0; i < 1_200; i++) {
      both.addCounters(CounterDelta.newBuilder().setName("d" + i).setDelta(1));
    }
    StatBatch full = both.build();
    Shaped<StatBatch> second = trimmer.trim(8, full);
    // stat bucket (10,000) covers the 6,000 rows; metric bucket: 5 s refill 1,000 of 1,200
    assertThat(second.batch().getSqlList()).isEqualTo(full.getSqlList());
    assertThat(second.batch().getExtList()).isEqualTo(full.getExtList());
    assertThat(second.batch().getCountersCount()).isEqualTo(1_000);
    assertThat(second.batch().getCounters(999).getName()).isEqualTo("d999");
    assertThat(second.droppedItems()).isEqualTo(200);

    // Both buckets short: the row and counter drops add up.
    Shaped<StatBatch> third = trimmer.trim(8, full);
    // stat bucket: refilled to its capacity 10,000 before the second batch, 4,000 left after it
    assertThat(third.batch().getSqlCount()).isEqualTo(4_000);
    assertThat(third.batch().getExtCount()).isZero();
    assertThat(third.batch().getCountersCount()).isZero();
    assertThat(third.droppedItems()).isEqualTo(2_000 + 1_200);

    assertThat(trimmer.trim(9, batch).droppedItems())
        .as("another instance has its own metric bucket")
        .isEqualTo(500);
  }

  @Test
  @DisplayName("T-SV-013 a batch inside the quota is returned unchanged (same instance)")
  void unchanged() {
    TransactionBatch batch = tx(10);
    assertThat(trimmer.trim(7, batch).batch()).isSameAs(batch);
  }

  @Test
  @Tag("req:D-06")
  @DisplayName(
      "T-SV-013a refund gives back exactly what trim charged for the kept batch: records, the steps"
          + " of the kept profiles, samples")
  void refundPerKind() {
    Shaped<TransactionBatch> txs = trimmer.trim(10, tx(4_500));
    assertThat(txs.batch().getRecordsCount()).isEqualTo(4_000);
    trimmer.refund(10, txs.batch());
    assertThat(trimmer.trim(10, tx(4_000)).droppedItems()).as("bucket full again").isZero();

    ProfileBatch.Builder b = ProfileBatch.newBuilder();
    for (int p = 1; p <= 5; p++) {
      TransactionProfile.Builder profile = TransactionProfile.newBuilder().setTxId(p);
      for (int s = 0; s < 10_000; s++) {
        profile.addSteps(ProfileStep.newBuilder().setSeq(s + 1));
      }
      b.addProfiles(profile);
    }
    Shaped<ProfileBatch> profiles = trimmer.trim(10, b.build());
    assertThat(profiles.batch().getProfilesCount()).isEqualTo(4);
    trimmer.refund(10, profiles.batch());
    assertThat(trimmer.trim(10, b.build()).droppedItems())
        .as("the 40,000 steps of the 4 kept profiles came back")
        .isEqualTo(1);

    MetricBatch.Builder m = MetricBatch.newBuilder();
    for (int i = 0; i < 2_000; i++) {
      m.addSamples(MetricSample.newBuilder().setTsMs(i));
    }
    Shaped<MetricBatch> metrics = trimmer.trim(10, m.build());
    assertThat(trimmer.trim(10, m.build()).droppedItems()).isEqualTo(2_000);
    trimmer.refund(10, metrics.batch());
    assertThat(trimmer.trim(10, m.build()).droppedItems()).isZero();
  }

  @Test
  @Tag("req:D-06")
  @DisplayName(
      "T-SV-013a refund of a statistics batch gives its rows back to the statistics bucket and its"
          + " counters to the metric bucket")
  void refundStatsBothBuckets() {
    StatBatch.Builder b = StatBatch.newBuilder();
    for (int i = 1; i <= 5_000; i++) {
      b.addSql(SqlStat.newBuilder().setSqlId(i));
    }
    for (int i = 1; i <= 1_000; i++) {
      b.addExt(ExtStat.newBuilder().setHostId(i));
    }
    for (int i = 0; i < 2_000; i++) {
      b.addCounters(CounterDelta.newBuilder().setName("c" + i).setDelta(1));
    }
    StatBatch batch = b.build();
    Shaped<StatBatch> first = trimmer.trim(11, batch);
    assertThat(first.droppedItems()).isZero();
    // Without the refund the second batch would lose 2,000 rows and every counter.
    trimmer.refund(11, first.batch());
    Shaped<StatBatch> second = trimmer.trim(11, batch);
    assertThat(second.droppedItems()).isZero();
    assertThat(second.batch()).isSameAs(batch);
  }
}
