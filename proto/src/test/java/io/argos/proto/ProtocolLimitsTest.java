/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.proto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.proto.v1.ExtStat;
import io.argos.proto.v1.Limits;
import io.argos.proto.v1.SqlStat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Shared protocol constants: 11 §4.2 Limits defaults, 11 §4.4 caps, 13 §2 latency buckets. */
class ProtocolLimitsTest {

  @Test
  @Tag("req:SEC-11")
  @Tag("req:DSN-11-07")
  @DisplayName("ProtocolLimits.defaults() is the 11 §4.2 Limits table")
  void limitsDefaults() {
    Limits limits = ProtocolLimits.defaults();
    assertThat(limits.getMaxMessageBytes()).isEqualTo(4_194_304);
    assertThat(limits.getMaxTransactionsPerBatch()).isEqualTo(5_000);
    assertThat(limits.getMaxStepsPerBatch()).isEqualTo(50_000);
    assertThat(limits.getMaxDetailBytes()).isEqualTo(4_096);
    assertThat(limits.getMaxStringBytes()).isEqualTo(2_048);
    assertThat(limits.getMaxFrames()).isEqualTo(64);
    assertThat(limits.getMaxActiveItems()).isEqualTo(500);
    assertThat(limits.getMaxSeriesPerInstance()).isEqualTo(500);
    assertThat(limits.getMaxDictionaryEntriesPerHour()).isEqualTo(10_000);
    // Every Limits field has a default (a new field without one fails here).
    assertThat(limits.getAllFields()).hasSize(Limits.getDescriptor().getFields().size());
  }

  @Test
  @Tag("req:SEC-11")
  @DisplayName(
      "ProtocolLimits repeat caps are the 11 §4.4 table and fit inside the Limits defaults")
  void repeatCaps() {
    assertThat(ProtocolLimits.MAX_RECORDS_PER_BATCH).isEqualTo(5_000);
    assertThat(ProtocolLimits.MAX_PROFILE_STEPS_PER_BATCH).isEqualTo(50_000);
    assertThat(ProtocolLimits.MAX_SERIES_PER_BATCH).isEqualTo(500);
    assertThat(ProtocolLimits.MAX_SQL_STATS_PER_BATCH).isEqualTo(5_000);
    assertThat(ProtocolLimits.MAX_EXT_STATS_PER_BATCH).isEqualTo(1_000);
    assertThat(ProtocolLimits.MAX_ACTIVE_ITEMS).isEqualTo(500);
    assertThat(ProtocolLimits.MAX_HEADERS).isEqualTo(10);
    assertThat(ProtocolLimits.MAX_SQL_IDS).isEqualTo(64);
    assertThat(ProtocolLimits.MAX_ERROR_FRAMES).isEqualTo(30);
    assertThat(ProtocolLimits.MAX_STACK_FRAMES).isEqualTo(64);
    assertThat(ProtocolLimits.MAX_DICTIONARY_ENTRIES_PER_MESSAGE).isEqualTo(1_000);
    assertThat(ProtocolLimits.MAX_STEPS_PER_PROFILE).isEqualTo(1_000);
    assertThat(ProtocolLimits.MAX_RECORDS_PER_BATCH)
        .isEqualTo(ProtocolLimits.DEFAULT_MAX_TRANSACTIONS_PER_BATCH);
    assertThat(ProtocolLimits.MAX_PROFILE_STEPS_PER_BATCH)
        .isEqualTo(ProtocolLimits.DEFAULT_MAX_STEPS_PER_BATCH);
    assertThat(ProtocolLimits.MAX_STACK_FRAMES).isEqualTo(ProtocolLimits.DEFAULT_MAX_FRAMES);
    assertThat(ProtocolLimits.TARGET_BATCH_BYTES)
        .isLessThan(ProtocolLimits.DEFAULT_MAX_MESSAGE_BYTES);
    assertThat(ProtocolLimits.GZIP_MIN_BYTES).isEqualTo(1_024);
  }

  @Test
  @Tag("req:DSN-13-03")
  @DisplayName("LatencyBuckets: 15 buckets with the inclusive 13 §2 upper bounds")
  void latencyBuckets() {
    long[] bounds = new long[LatencyBuckets.COUNT];
    for (int i = 0; i < bounds.length; i++) {
      bounds[i] = LatencyBuckets.upperBoundMs(i);
    }
    assertThat(bounds)
        .containsExactly(
            1,
            2,
            5,
            10,
            20,
            50,
            100,
            200,
            500,
            1_000,
            2_000,
            5_000,
            10_000,
            30_000,
            Long.MAX_VALUE);
    assertThat(LatencyBuckets.indexOfMs(0)).isZero();
    assertThat(LatencyBuckets.indexOfMs(-5)).isZero();
    assertThat(LatencyBuckets.indexOfMs(1)).isZero();
    assertThat(LatencyBuckets.indexOfMs(2)).isEqualTo(1);
    assertThat(LatencyBuckets.indexOfMs(3)).isEqualTo(2);
    assertThat(LatencyBuckets.indexOfMs(30_000)).isEqualTo(13);
    assertThat(LatencyBuckets.indexOfMs(30_001)).isEqualTo(14);
    assertThat(LatencyBuckets.indexOfMs(Long.MAX_VALUE)).isEqualTo(14);
    for (int i = 0; i < LatencyBuckets.COUNT - 1; i++) {
      long bound = LatencyBuckets.upperBoundMs(i);
      assertThat(LatencyBuckets.indexOfMs(bound)).as("bound %s", bound).isEqualTo(i);
      assertThat(LatencyBuckets.indexOfMs(bound + 1)).as("above %s", bound).isEqualTo(i + 1);
    }
    // Microseconds round half up to the stored milliseconds (11 §4.4).
    assertThat(LatencyBuckets.indexOfMicros(1_499)).isZero();
    assertThat(LatencyBuckets.indexOfMicros(1_500)).isEqualTo(1);
    assertThat(LatencyBuckets.indexOfMicros(-1)).isZero();
    assertThatThrownBy(() -> LatencyBuckets.upperBoundMs(15))
        .isInstanceOf(IndexOutOfBoundsException.class);
    assertThatThrownBy(() -> LatencyBuckets.upperBoundMs(-1))
        .isInstanceOf(IndexOutOfBoundsException.class);
  }

  @Test
  @Tag("req:DSN-13-03")
  @DisplayName("SqlStat and ExtStat carry the 15 bucket counts and the exec/fetch sums (13 §18)")
  void statisticsCarryBuckets() throws Exception {
    SqlStat.Builder sql = SqlStat.newBuilder().setSqlId(7).setDbId(9).setCount(3);
    ExtStat.Builder ext = ExtStat.newBuilder().setHostId(5).setCount(3);
    for (int i = 0; i < LatencyBuckets.COUNT; i++) {
      sql.addBuckets(i == 4 ? 3 : 0);
      ext.addBuckets(i == 14 ? 3 : 0);
    }
    sql.setSumUs(45_000).setSumExecUs(40_000).setSumFetchUs(5_000);
    SqlStat parsedSql = SqlStat.parseFrom(sql.build().toByteArray());
    ExtStat parsedExt = ExtStat.parseFrom(ext.build().toByteArray());
    assertThat(parsedSql.getBucketsList()).hasSize(LatencyBuckets.COUNT).element(4).isEqualTo(3);
    assertThat(parsedSql.getSumExecUs() + parsedSql.getSumFetchUs())
        .isEqualTo(parsedSql.getSumUs());
    assertThat(parsedExt.getBucketsList()).hasSize(LatencyBuckets.COUNT).element(14).isEqualTo(3);
  }
}
