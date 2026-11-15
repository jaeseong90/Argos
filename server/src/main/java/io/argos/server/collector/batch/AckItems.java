/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.batch;

import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.ProfileBatch;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.TransactionBatch;

/**
 * The item unit of {@code Ack.dropped_items} per batch kind, the unit the agent counts its batches
 * in ({@code Sender.itemCount}): transaction records, profiles (whole transactions), metric
 * samples, statistics rows ({@code SqlStat} + {@code ExtStat}) and window counters ({@code
 * CounterDelta}, metric rows that the quota trims too). Used for quota drops, WAL-full drops (every
 * item) and items with an unknown required enum.
 *
 * <p>One exception: a {@code ProfileStep} removed for an unknown step type counts as one item (the
 * profile itself is kept).
 */
public final class AckItems {

  private AckItems() {}

  /** Records. */
  public static int of(TransactionBatch batch) {
    return batch.getRecordsCount();
  }

  /** Profiles. */
  public static int of(ProfileBatch batch) {
    return batch.getProfilesCount();
  }

  /** Samples. */
  public static int of(MetricBatch batch) {
    return batch.getSamplesCount();
  }

  /** SQL and outgoing-call statistics rows and window counters. */
  public static int of(StatBatch batch) {
    return batch.getSqlCount() + batch.getExtCount() + batch.getCountersCount();
  }
}
