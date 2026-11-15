/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.batch;

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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * ADR-0010 C-143 (1): an item whose required enum ({@code TransactionRecord.kind}, {@code
 * TransactionProfile.kind}, {@code ProfileStep.type}, {@code MetricSeries.kind}, {@code
 * SqlStat.kind}, {@code ExtStat.kind}) is UNSPECIFIED or a value this server does not know is
 * removed before the WAL append; the batch itself is never rejected for an enum (11 §4.4). Each
 * removal is counted ({@link UnknownEnumSink}) and reported in the ack ({@code ACK_PARTIAL_DROP}
 * with the count, {@link AckItems} unit). The ClickHouse {@code Enum8} columns of these fields have
 * no value 0 (proto/README.md), so such an item could not be stored anyway.
 *
 * <p>A metric series with an unknown kind is removed with its samples (the samples are the items).
 * Stateless apart from the sink; thread-safe.
 */
public final class RequiredEnumFilter {

  private final UnknownEnumSink sink;

  public RequiredEnumFilter(UnknownEnumSink sink) {
    this.sink = Objects.requireNonNull(sink, "sink");
  }

  /** Records with an unknown kind removed. */
  public Shaped<TransactionBatch> filter(TransactionBatch batch) {
    int bad = 0;
    for (TransactionRecord r : batch.getRecordsList()) {
      if (!known(r.getKind())) {
        bad++;
      }
    }
    if (bad == 0) {
      return Shaped.unchanged(batch);
    }
    sink.unknownEnum(UnknownEnumSink.TRANSACTION_KIND, bad);
    List<TransactionRecord> kept = new ArrayList<>(batch.getRecordsCount() - bad);
    for (TransactionRecord r : batch.getRecordsList()) {
      if (known(r.getKind())) {
        kept.add(r);
      }
    }
    return new Shaped<>(batch.toBuilder().clearRecords().addAllRecords(kept).build(), bad);
  }

  /** Profiles with an unknown kind and steps with an unknown type removed. */
  public Shaped<ProfileBatch> filter(ProfileBatch batch) {
    int badProfiles = 0;
    int badSteps = 0;
    for (TransactionProfile p : batch.getProfilesList()) {
      if (!known(p.getKind())) {
        badProfiles++;
        continue;
      }
      for (ProfileStep s : p.getStepsList()) {
        if (!known(s.getType())) {
          badSteps++;
        }
      }
    }
    if (badProfiles == 0 && badSteps == 0) {
      return Shaped.unchanged(batch);
    }
    if (badProfiles > 0) {
      sink.unknownEnum(UnknownEnumSink.PROFILE_KIND, badProfiles);
    }
    if (badSteps > 0) {
      sink.unknownEnum(UnknownEnumSink.STEP_TYPE, badSteps);
    }
    ProfileBatch.Builder out = batch.toBuilder().clearProfiles();
    for (TransactionProfile p : batch.getProfilesList()) {
      if (!known(p.getKind())) {
        continue;
      }
      boolean stepsOk = true;
      for (ProfileStep s : p.getStepsList()) {
        if (!known(s.getType())) {
          stepsOk = false;
          break;
        }
      }
      if (stepsOk) {
        out.addProfiles(p);
        continue;
      }
      TransactionProfile.Builder kept = p.toBuilder().clearSteps();
      for (ProfileStep s : p.getStepsList()) {
        if (known(s.getType())) {
          kept.addSteps(s);
        }
      }
      out.addProfiles(kept);
    }
    return new Shaped<>(out.build(), badProfiles + badSteps);
  }

  /** Series with an unknown kind removed together with their samples. */
  public Shaped<MetricBatch> filter(MetricBatch batch) {
    Set<Integer> badSeries = new HashSet<>();
    for (MetricSeries s : batch.getSeriesList()) {
      if (!known(s.getKind())) {
        badSeries.add(s.getIndex());
      }
    }
    if (badSeries.isEmpty()) {
      return Shaped.unchanged(batch);
    }
    sink.unknownEnum(UnknownEnumSink.METRIC_KIND, badSeries.size());
    MetricBatch.Builder out = batch.toBuilder().clearSeries().clearSamples();
    for (MetricSeries s : batch.getSeriesList()) {
      if (known(s.getKind())) {
        out.addSeries(s);
      }
    }
    int droppedSamples = 0;
    for (MetricSample sample : batch.getSamplesList()) {
      if (badSeries.contains(sample.getSeriesIndex())) {
        droppedSamples++;
      } else {
        out.addSamples(sample);
      }
    }
    return new Shaped<>(out.build(), droppedSamples);
  }

  /** SQL and outgoing-call statistics with an unknown kind removed. */
  public Shaped<StatBatch> filter(StatBatch batch) {
    int badSql = 0;
    for (SqlStat s : batch.getSqlList()) {
      if (!known(s.getKind())) {
        badSql++;
      }
    }
    int badExt = 0;
    for (ExtStat s : batch.getExtList()) {
      if (!known(s.getKind())) {
        badExt++;
      }
    }
    if (badSql == 0 && badExt == 0) {
      return Shaped.unchanged(batch);
    }
    StatBatch.Builder out = batch.toBuilder();
    if (badSql > 0) {
      sink.unknownEnum(UnknownEnumSink.SQL_STAT_KIND, badSql);
      out.clearSql();
      for (SqlStat s : batch.getSqlList()) {
        if (known(s.getKind())) {
          out.addSql(s);
        }
      }
    }
    if (badExt > 0) {
      sink.unknownEnum(UnknownEnumSink.EXT_STAT_KIND, badExt);
      out.clearExt();
      for (ExtStat s : batch.getExtList()) {
        if (known(s.getKind())) {
          out.addExt(s);
        }
      }
    }
    return new Shaped<>(out.build(), badSql + badExt);
  }

  /** Whether {@code kind} is a defined transaction kind. */
  public static boolean known(TransactionKind kind) {
    return kind != TransactionKind.TRANSACTION_KIND_UNSPECIFIED
        && kind != TransactionKind.UNRECOGNIZED;
  }

  /** Whether {@code type} is a defined step type. */
  public static boolean known(StepType type) {
    return type != StepType.STEP_TYPE_UNSPECIFIED && type != StepType.UNRECOGNIZED;
  }

  /** Whether {@code kind} is a defined metric kind. */
  public static boolean known(MetricKind kind) {
    return kind != MetricKind.METRIC_KIND_UNSPECIFIED && kind != MetricKind.UNRECOGNIZED;
  }
}
