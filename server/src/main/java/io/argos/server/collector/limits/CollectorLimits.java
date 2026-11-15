/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.limits;

import io.argos.proto.ProtocolLimits;
import io.argos.proto.v1.Limits;
import io.argos.server.config.ArgosProperties;

/**
 * The configurable caps of the 11 §4.2 {@code Limits} message ({@code argos.collector.limits.*} and
 * {@code argos.collector.max-message-bytes}, defaults {@link ProtocolLimits}): sent to agents in
 * {@code ServerHello.limits} (DSN-11-07) and checked again by the server (SEC-11). The fixed repeat
 * and field caps of 11 §4.4 are {@link ProtocolLimits} constants.
 *
 * @param maxMessageBytes decompressed message size
 * @param maxTransactionsPerBatch {@code TransactionBatch.records}
 * @param maxStepsPerBatch steps of all profiles of one {@code ProfileBatch}
 * @param maxDetailBytes {@code ProfileStep.detail}
 * @param maxStringBytes strings without a field-specific cap
 * @param maxErrorFrames {@code frame_ids} of an ERROR step
 * @param maxStackFrames {@code frame_ids} of any other step (STACK)
 * @param maxActiveItems {@code ActiveTransactionSnapshot.items}
 * @param maxSeriesPerBatch {@code MetricBatch.series} (the {@code max_series_per_instance} value)
 * @param maxDictionaryEntriesPerHour new dictionary entries per instance and hour (applied by the
 *     dictionary resolver, 16 §4.4; only sent here)
 */
public record CollectorLimits(
    int maxMessageBytes,
    int maxTransactionsPerBatch,
    int maxStepsPerBatch,
    int maxDetailBytes,
    int maxStringBytes,
    int maxErrorFrames,
    int maxStackFrames,
    int maxActiveItems,
    int maxSeriesPerBatch,
    int maxDictionaryEntriesPerHour) {

  /** Rejects caps below 1. */
  public CollectorLimits {
    for (int value :
        new int[] {
          maxMessageBytes,
          maxTransactionsPerBatch,
          maxStepsPerBatch,
          maxDetailBytes,
          maxStringBytes,
          maxErrorFrames,
          maxStackFrames,
          maxActiveItems,
          maxSeriesPerBatch,
          maxDictionaryEntriesPerHour
        }) {
      if (value < 1) {
        throw new IllegalArgumentException("A collector limit is below 1: " + value);
      }
    }
  }

  /** The 11 §4.2 defaults ({@link ProtocolLimits#defaults()}). */
  public static CollectorLimits defaults() {
    return new CollectorLimits(
        ProtocolLimits.DEFAULT_MAX_MESSAGE_BYTES,
        ProtocolLimits.DEFAULT_MAX_TRANSACTIONS_PER_BATCH,
        ProtocolLimits.DEFAULT_MAX_STEPS_PER_BATCH,
        ProtocolLimits.DEFAULT_MAX_DETAIL_BYTES,
        ProtocolLimits.DEFAULT_MAX_STRING_BYTES,
        ProtocolLimits.MAX_ERROR_FRAMES,
        ProtocolLimits.MAX_STACK_FRAMES,
        ProtocolLimits.DEFAULT_MAX_ACTIVE_ITEMS,
        ProtocolLimits.DEFAULT_MAX_SERIES_PER_INSTANCE,
        ProtocolLimits.DEFAULT_MAX_DICTIONARY_ENTRIES_PER_HOUR);
  }

  /** The configured caps ({@code argos.collector.*}). */
  public static CollectorLimits of(ArgosProperties.Collector collector) {
    ArgosProperties.Collector.Limits l = collector.limits();
    return new CollectorLimits(
        collector.maxMessageBytes(),
        l.maxTransactionsPerBatch(),
        l.maxStepsPerBatch(),
        l.maxDetailBytes(),
        l.maxStringBytes(),
        l.maxErrorFrames(),
        l.maxStackFrames(),
        l.maxActiveItems(),
        l.maxSeriesPerInstance(),
        l.maxDictionaryEntriesPerHour());
  }

  /**
   * {@code ServerHello.limits}: the message has one {@code max_frames} field, which carries the
   * larger of the two frame caps (11 §4.2 "30 (ERROR) / 64 (STACK)"; the agent keeps 30 for ERROR
   * steps itself).
   */
  public Limits toProto() {
    return Limits.newBuilder()
        .setMaxMessageBytes(maxMessageBytes)
        .setMaxTransactionsPerBatch(maxTransactionsPerBatch)
        .setMaxStepsPerBatch(maxStepsPerBatch)
        .setMaxDetailBytes(maxDetailBytes)
        .setMaxStringBytes(maxStringBytes)
        .setMaxFrames(Math.max(maxErrorFrames, maxStackFrames))
        .setMaxActiveItems(maxActiveItems)
        .setMaxSeriesPerInstance(maxSeriesPerBatch)
        .setMaxDictionaryEntriesPerHour(maxDictionaryEntriesPerHour)
        .build();
  }
}
