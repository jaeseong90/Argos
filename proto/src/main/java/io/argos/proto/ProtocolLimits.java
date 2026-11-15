/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.proto;

import io.argos.proto.v1.Limits;

/**
 * Caps of the argos.v1 protocol, shared by the agent (batch assembly, string cutting) and the
 * server (second check, SEC-11) so that both sides use the same numbers.
 *
 * <ul>
 *   <li>{@code DEFAULT_*}: defaults of the {@link Limits} message the server sends in {@code
 *       ServerHello.limits} (11 §4.2, DSN-11-07). The agent applies the values it received; the
 *       defaults apply until then. Changing a value is not a protocol change (11 §12).
 *   <li>{@code MAX_*}: fixed repeat and string caps of 11 §4.4 and the field comments of 11 §4.
 *   <li>{@code TARGET_BATCH_BYTES} and {@code GZIP_MIN_BYTES}: the agent's batch size target and
 *       compression threshold (11 §2).
 * </ul>
 *
 * <p>Byte caps count UTF-8 bytes of the serialized value.
 */
public final class ProtocolLimits {

  // --- 11 §4.2 Limits defaults -----------------------------------------------------------------

  /** {@code Limits.max_message_bytes}: 4 MiB after decompression (server maxInboundMessageSize). */
  public static final int DEFAULT_MAX_MESSAGE_BYTES = 4 * 1024 * 1024;

  /** {@code Limits.max_transactions_per_batch}. */
  public static final int DEFAULT_MAX_TRANSACTIONS_PER_BATCH = 5_000;

  /** {@code Limits.max_steps_per_batch} (total steps of all profiles in one ProfileBatch). */
  public static final int DEFAULT_MAX_STEPS_PER_BATCH = 50_000;

  /** {@code Limits.max_detail_bytes} ({@code ProfileStep.detail}). */
  public static final int DEFAULT_MAX_DETAIL_BYTES = 4_096;

  /** {@code Limits.max_string_bytes}: strings without a field-specific cap. */
  public static final int DEFAULT_MAX_STRING_BYTES = 2_048;

  /**
   * {@code Limits.max_frames}: the larger of the two frame caps; ERROR steps keep {@link
   * #MAX_ERROR_FRAMES}, STACK steps {@link #MAX_STACK_FRAMES}.
   */
  public static final int DEFAULT_MAX_FRAMES = 64;

  /** {@code Limits.max_active_items} ({@code ActiveTransactionSnapshot.items}). */
  public static final int DEFAULT_MAX_ACTIVE_ITEMS = 500;

  /** {@code Limits.max_series_per_instance} (D-16). */
  public static final int DEFAULT_MAX_SERIES_PER_INSTANCE = 500;

  /** {@code Limits.max_dictionary_entries_per_hour}: new entries per instance (SEC-13). */
  public static final int DEFAULT_MAX_DICTIONARY_ENTRIES_PER_HOUR = 10_000;

  // --- 11 §4.4 repeat caps ---------------------------------------------------------------------

  /** {@code TransactionBatch.records}. */
  public static final int MAX_RECORDS_PER_BATCH = 5_000;

  /** Total {@code ProfileStep}s of all {@code ProfileBatch.profiles}. */
  public static final int MAX_PROFILE_STEPS_PER_BATCH = 50_000;

  /** {@code TransactionProfile.steps}: more steps set {@code truncated} (11 §4.3). */
  public static final int MAX_STEPS_PER_PROFILE = 1_000;

  /** {@code MetricBatch.series}. */
  public static final int MAX_SERIES_PER_BATCH = 500;

  /** {@code StatBatch.sql}. */
  public static final int MAX_SQL_STATS_PER_BATCH = 5_000;

  /** {@code StatBatch.ext}. */
  public static final int MAX_EXT_STATS_PER_BATCH = 1_000;

  /** {@code ActiveTransactionSnapshot.items}. */
  public static final int MAX_ACTIVE_ITEMS = 500;

  /** {@code TransactionRecord.headers}. */
  public static final int MAX_HEADERS = 10;

  /** {@code TransactionRecord.sql_ids} (A-10, distinct). */
  public static final int MAX_SQL_IDS = 64;

  /** {@code ProfileStep.frame_ids} of an ERROR step. */
  public static final int MAX_ERROR_FRAMES = 30;

  /** {@code ProfileStep.frame_ids} of a STACK step. */
  public static final int MAX_STACK_FRAMES = 64;

  /** {@code DictionaryEntries.entries} and {@code DictionaryRequest.refs} per message. */
  public static final int MAX_DICTIONARY_ENTRIES_PER_MESSAGE = 1_000;

  /** {@code Command.args} entries. */
  public static final int MAX_COMMAND_ARGS = 8;

  // --- 11 §4 field caps (bytes) ----------------------------------------------------------------

  /** {@code KeyValue.key}. */
  public static final int MAX_KEY_BYTES = 128;

  /** {@code KeyValue.value} (one whitelisted header value). */
  public static final int MAX_VALUE_BYTES = 1_024;

  /** {@code AgentHello.app_name}. */
  public static final int MAX_APP_NAME_BYTES = 128;

  /** {@code AgentHello.env}. */
  public static final int MAX_ENV_BYTES = 32;

  /** {@code TransactionRecord.http_method}. */
  public static final int MAX_HTTP_METHOD_BYTES = 16;

  /** {@code TransactionRecord.url_path}. */
  public static final int MAX_URL_PATH_BYTES = 2_048;

  /** {@code TransactionRecord.error_message} (masked first, SEC-36). */
  public static final int MAX_ERROR_MESSAGE_BYTES = 1_024;

  /** {@code TransactionRecord.user_id} and {@code ActiveTransaction.user_id}. */
  public static final int MAX_USER_ID_BYTES = 128;

  /** {@code TransactionRecord.request_id}. */
  public static final int MAX_REQUEST_ID_BYTES = 128;

  /** {@code DictionaryEntry.text} of {@code SQL_TEXT}. */
  public static final int MAX_SQL_TEXT_BYTES = 64 * 1024;

  /** {@code DictionaryEntry.text} of every other kind. */
  public static final int MAX_DICTIONARY_TEXT_BYTES = 8 * 1024;

  /** {@code MetricSeries.name} and {@code CounterDelta.name}. */
  public static final int MAX_METRIC_NAME_BYTES = 256;

  /** {@code AgentEvent.detail}. */
  public static final int MAX_EVENT_DETAIL_BYTES = 1_024;

  /** {@code Command.args} values. */
  public static final int MAX_COMMAND_ARG_VALUE_BYTES = 256;

  /** {@code CommandResult.payload} of one chunk. */
  public static final int MAX_COMMAND_CHUNK_BYTES = 256 * 1024;

  /** {@code CommandResult.payload} of all chunks; more sets {@code truncated}. */
  public static final int MAX_COMMAND_RESULT_BYTES = 1024 * 1024;

  /** {@code TransactionRecord.trace_id} length. */
  public static final int TRACE_ID_BYTES = 16;

  // --- 11 §2 agent batching --------------------------------------------------------------------

  /** Serialized batch size target of the agent; larger batches are split (11 §2). */
  public static final int TARGET_BATCH_BYTES = 1024 * 1024;

  /** The agent gzips only batches whose serialized size is above this (11 §2, N-02). */
  public static final int GZIP_MIN_BYTES = 1_024;

  private ProtocolLimits() {}

  /**
   * A {@link Limits} message with the 11 §4.2 defaults (what the server sends unless configured).
   */
  public static Limits defaults() {
    return Limits.newBuilder()
        .setMaxMessageBytes(DEFAULT_MAX_MESSAGE_BYTES)
        .setMaxTransactionsPerBatch(DEFAULT_MAX_TRANSACTIONS_PER_BATCH)
        .setMaxStepsPerBatch(DEFAULT_MAX_STEPS_PER_BATCH)
        .setMaxDetailBytes(DEFAULT_MAX_DETAIL_BYTES)
        .setMaxStringBytes(DEFAULT_MAX_STRING_BYTES)
        .setMaxFrames(DEFAULT_MAX_FRAMES)
        .setMaxActiveItems(DEFAULT_MAX_ACTIVE_ITEMS)
        .setMaxSeriesPerInstance(DEFAULT_MAX_SERIES_PER_INSTANCE)
        .setMaxDictionaryEntriesPerHour(DEFAULT_MAX_DICTIONARY_ENTRIES_PER_HOUR)
        .build();
  }
}
