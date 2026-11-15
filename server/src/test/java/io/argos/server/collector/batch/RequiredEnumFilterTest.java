/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.batch;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.ExtStat;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.MetricKind;
import io.argos.proto.v1.MetricSample;
import io.argos.proto.v1.MetricSeries;
import io.argos.proto.v1.ProfileBatch;
import io.argos.proto.v1.ProfileStep;
import io.argos.proto.v1.SqlStat;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.StepType;
import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.TransactionKind;
import io.argos.proto.v1.TransactionProfile;
import io.argos.proto.v1.TransactionRecord;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * ADR-0010 C-143 (1): an item with an UNSPECIFIED or unknown required enum is removed and counted;
 * the rest of the batch is kept; the ack reports the count ({@link AckItems} unit).
 */
@Tag("req:C-143")
class RequiredEnumFilterTest {

  private final Map<String, Integer> counted = new HashMap<>();
  private final RequiredEnumFilter filter =
      new RequiredEnumFilter((field, n) -> counted.merge(field, n, Integer::sum));

  @Test
  @DisplayName(
      "C-143 (1) transactions with kind UNSPECIFIED or an unknown number are dropped and counted")
  void transactions() {
    TransactionBatch batch =
        TransactionBatch.newBuilder()
            .addRecords(TransactionRecord.newBuilder().setTxId(1).setKind(TransactionKind.HTTP))
            .addRecords(
                TransactionRecord.newBuilder()
                    .setTxId(2)
                    .setKind(TransactionKind.TRANSACTION_KIND_UNSPECIFIED))
            .addRecords(TransactionRecord.newBuilder().setTxId(3).setKindValue(99))
            .addRecords(TransactionRecord.newBuilder().setTxId(4).setKind(TransactionKind.BATCH))
            .build();
    Shaped<TransactionBatch> shaped = filter.filter(batch);
    assertThat(shaped.droppedItems()).isEqualTo(2);
    assertThat(shaped.batch().getRecordsList())
        .extracting(TransactionRecord::getTxId)
        .containsExactly(1L, 4L);
    assertThat(counted).containsEntry(UnknownEnumSink.TRANSACTION_KIND, 2);
  }

  @Test
  @DisplayName(
      "C-143 (1) profiles with an unknown kind are dropped whole, steps with an unknown type alone")
  void profiles() {
    ProfileBatch batch =
        ProfileBatch.newBuilder()
            .addProfiles(
                TransactionProfile.newBuilder()
                    .setTxId(1)
                    .setKind(TransactionKind.HTTP)
                    .addSteps(ProfileStep.newBuilder().setSeq(1).setType(StepType.SQL))
                    .addSteps(ProfileStep.newBuilder().setSeq(2).setTypeValue(55))
                    .addSteps(
                        ProfileStep.newBuilder().setSeq(3).setType(StepType.STEP_TYPE_UNSPECIFIED))
                    .addSteps(ProfileStep.newBuilder().setSeq(4).setType(StepType.SUMMARY)))
            .addProfiles(TransactionProfile.newBuilder().setTxId(2).setKindValue(9))
            .build();
    Shaped<ProfileBatch> shaped = filter.filter(batch);
    assertThat(shaped.droppedItems()).isEqualTo(3);
    assertThat(shaped.batch().getProfilesCount()).isEqualTo(1);
    assertThat(shaped.batch().getProfiles(0).getStepsList())
        .extracting(ProfileStep::getSeq)
        .containsExactly(1, 4);
    assertThat(counted)
        .containsEntry(UnknownEnumSink.PROFILE_KIND, 1)
        .containsEntry(UnknownEnumSink.STEP_TYPE, 2);
  }

  @Test
  @DisplayName("C-143 (1) a metric series with an unknown kind is dropped with its samples")
  void metrics() {
    MetricBatch batch =
        MetricBatch.newBuilder()
            .addSeries(MetricSeries.newBuilder().setIndex(0).setName("a").setKind(MetricKind.GAUGE))
            .addSeries(MetricSeries.newBuilder().setIndex(1).setName("b").setKindValue(7))
            .addSamples(MetricSample.newBuilder().setSeriesIndex(0).setValue(1))
            .addSamples(MetricSample.newBuilder().setSeriesIndex(1).setValue(2))
            .addSamples(MetricSample.newBuilder().setSeriesIndex(1).setValue(3))
            .build();
    Shaped<MetricBatch> shaped = filter.filter(batch);
    assertThat(shaped.droppedItems()).as("samples").isEqualTo(2);
    assertThat(shaped.batch().getSeriesList())
        .extracting(MetricSeries::getName)
        .containsExactly("a");
    assertThat(shaped.batch().getSamplesList())
        .extracting(MetricSample::getValue)
        .containsExactly(1.0);
    assertThat(counted).containsEntry(UnknownEnumSink.METRIC_KIND, 1);
  }

  @Test
  @DisplayName("C-143 (1) statistics rows with an unknown kind are dropped")
  void stats() {
    StatBatch batch =
        StatBatch.newBuilder()
            .addSql(SqlStat.newBuilder().setSqlId(1).setKind(TransactionKind.HTTP))
            .addSql(SqlStat.newBuilder().setSqlId(2))
            .addExt(ExtStat.newBuilder().setHostId(1).setKindValue(12))
            .addExt(ExtStat.newBuilder().setHostId(2).setKind(TransactionKind.MESSAGE))
            .build();
    Shaped<StatBatch> shaped = filter.filter(batch);
    assertThat(shaped.droppedItems()).isEqualTo(2);
    assertThat(shaped.batch().getSqlList()).extracting(SqlStat::getSqlId).containsExactly(1L);
    assertThat(shaped.batch().getExtList()).extracting(ExtStat::getHostId).containsExactly(2L);
    assertThat(counted)
        .containsEntry(UnknownEnumSink.SQL_STAT_KIND, 1)
        .containsEntry(UnknownEnumSink.EXT_STAT_KIND, 1);
  }

  @Test
  @DisplayName("C-143 (1) a batch without unknown enums is returned as is and nothing is counted")
  void unchanged() {
    TransactionBatch batch =
        TransactionBatch.newBuilder()
            .addRecords(TransactionRecord.newBuilder().setKind(TransactionKind.HTTP))
            .build();
    assertThat(filter.filter(batch).batch()).isSameAs(batch);
    assertThat(counted).isEmpty();
  }
}
