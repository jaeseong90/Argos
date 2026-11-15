/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.limits;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.config.ArgosProperties;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * The per-instance token buckets of {@link InstanceQuotas}: giving back the tokens of a batch that
 * was not written (T-SV-013a, WO-21 follow-up N-1) and the statistics quota default of ADR-0010
 * C-157 (3). The bucket clock is a stepped counter, no wall-clock time is involved (C-146 (2)).
 */
@Tag("req:DSN-12-04")
@Tag("req:D-06")
class InstanceQuotasTest {

  private static final ArgosProperties.Collector.Quota QUOTA =
      new ArgosProperties.Collector.Quota(2_000, 20_000, 1_000, 6_000);

  private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
  private final InstanceQuotas quotas = new InstanceQuotas(QUOTA, nanos::get);

  private void advanceSeconds(double seconds) {
    nanos.addAndGet((long) (seconds * TimeUnit.SECONDS.toNanos(1)));
  }

  @Test
  @DisplayName(
      "T-SV-013a giveBack restores taken tokens, never above the bucket capacity, without a refill"
          + " step")
  void giveBackIsCappedAtCapacity() {
    assertThat(quotas.take(1, QuotaKind.TRANSACTIONS, 3_000)).isEqualTo(3_000);
    assertThat(quotas.tokensForTest(1, QuotaKind.TRANSACTIONS)).isEqualTo(1_000);
    quotas.giveBack(1, QuotaKind.TRANSACTIONS, 2_500);
    assertThat(quotas.tokensForTest(1, QuotaKind.TRANSACTIONS)).isEqualTo(3_500);
    quotas.giveBack(1, QuotaKind.TRANSACTIONS, 2_500);
    assertThat(quotas.tokensForTest(1, QuotaKind.TRANSACTIONS))
        .as("capacity = twice the per-second quota")
        .isEqualTo(4_000);
    assertThat(quotas.take(1, QuotaKind.TRANSACTIONS, 4_001)).isEqualTo(4_000);
    // Zero, negative and NaN amounts change nothing.
    quotas.giveBack(1, QuotaKind.TRANSACTIONS, 0);
    quotas.giveBack(1, QuotaKind.TRANSACTIONS, -10);
    quotas.giveBack(1, QuotaKind.TRANSACTIONS, Double.NaN);
    assertThat(quotas.tokensForTest(1, QuotaKind.TRANSACTIONS)).isZero();
    // The refill time is unchanged by a give-back: one second later exactly one second refills.
    quotas.giveBack(1, QuotaKind.TRANSACTIONS, 100);
    advanceSeconds(1);
    assertThat(quotas.take(1, QuotaKind.TRANSACTIONS, 2_200)).isEqualTo(2_100);
  }

  @Test
  @DisplayName(
      "T-SV-013a giveBack to a bucket that is not held (never created, or evicted) creates none;"
          + " the bucket later starts full, not above its capacity")
  void giveBackWithoutBucket() {
    quotas.giveBack(7, QuotaKind.STEPS, 10_000);
    assertThat(quotas.tokensForTest(7, QuotaKind.STEPS)).as("no bucket created").isEqualTo(-1);
    assertThat(quotas.take(7, QuotaKind.STEPS, 50_000)).isEqualTo(40_000);
    quotas.giveBack(8, QuotaKind.STEPS, 1_000);
    assertThat(quotas.tokensForTest(7, QuotaKind.STEPS))
        .as("another instance's give-back does not reach this bucket")
        .isZero();
  }

  @Test
  @DisplayName("T-SV-013a giveBack reaches only its own kind")
  void giveBackPerKind() {
    assertThat(quotas.take(2, QuotaKind.METRIC_SAMPLES, 2_000)).isEqualTo(2_000);
    assertThat(quotas.take(2, QuotaKind.STAT_ROWS, 12_000)).isEqualTo(12_000);
    quotas.giveBack(2, QuotaKind.METRIC_SAMPLES, 500);
    assertThat(quotas.tokensForTest(2, QuotaKind.METRIC_SAMPLES)).isEqualTo(500);
    assertThat(quotas.tokensForTest(2, QuotaKind.STAT_ROWS)).isZero();
  }

  @Test
  @Tag("req:C-157")
  @DisplayName(
      "C-157 (3) argos.collector.quota.stats-per-10s defaults to 6,000 rows (bucket 12,000)")
  void statsQuotaDefault() {
    ArgosProperties.Collector.Quota defaults =
        new Binder(new MapConfigurationPropertySource(Map.of()))
            .bindOrCreate("argos", ArgosProperties.class)
            .collector()
            .quota();
    assertThat(defaults.statsPer10s()).isEqualTo(6_000);
    assertThat(QuotaKind.STAT_ROWS.ratePerSecond(defaults)).isEqualTo(600);
    assertThat(QuotaKind.STAT_ROWS.capacity(defaults)).isEqualTo(12_000);
  }

  @Test
  @Tag("req:C-157")
  @DisplayName(
      "C-157 (3) with the default statistics quota, the largest legal StatBatch (5,000 SQL + 1,000"
          + " outgoing-call rows) every 10 s window is never trimmed")
  void largestStatBatchEveryWindowFits() {
    InstanceQuotas defaults =
        new InstanceQuotas(
            new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("argos", ArgosProperties.class)
                .collector()
                .quota(),
            nanos::get);
    int rows =
        io.argos.proto.ProtocolLimits.MAX_SQL_STATS_PER_BATCH
            + io.argos.proto.ProtocolLimits.MAX_EXT_STATS_PER_BATCH;
    assertThat(rows).isEqualTo(6_000);
    for (int window = 0; window < 100; window++) {
      assertThat(defaults.take(3, QuotaKind.STAT_ROWS, rows))
          .as("window %d", window)
          .isEqualTo(rows);
      advanceSeconds(10);
    }
  }
}
