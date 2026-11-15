/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.export;

import java.util.Set;

/**
 * Where collected transactions come from and which fields that source can carry at all. The
 * assertion DSL uses it to tell "the agent did not record a value" (missing, 18 §5.5) from "this
 * source never has the field" (unsupported: the assertion fails with that reason instead of
 * passing).
 *
 * @param name description for assertion messages
 * @param transactionFields field names a transaction of this source can have
 * @param stepFields field names a step of this source can have
 */
public record RecordSource(String name, Set<String> transactionFields, Set<String> stepFields) {

  public RecordSource {
    transactionFields = Set.copyOf(transactionFields);
    stepFields = Set.copyOf(stepFields);
  }

  /**
   * The JSONL exporter ({@code -Dargos.test.exporter=file:<dir>}, field reference in {@code
   * JsonlFileExporter.write}). From WO-10 on it carries {@code env}, {@code version} (when set),
   * CPU time and allocated bytes (absent when not measured, D-23), the C-69 flags, the normalized
   * thread name, the sampler decision ({@code profile_captured}), {@code truncated}/{@code
   * total_steps}, and per step the recording thread and {@code repeat_count}. From WO-12 on also
   * {@code dispatch_count}, {@code request_id} and {@code headers} (a list of {@code "name: value"}
   * strings; {@link TransactionRecord#headers()} reads both this and the collector's map) and the
   * {@code timed_out} flag. Not carried (they arrive with later work orders and fail as
   * unsupported): connection wait, SQL ids, statistics, metrics, dictionary and counters.
   */
  public static final RecordSource JSONL =
      new RecordSource(
          "JSONL exporter (argos.test.exporter=file)",
          Set.of(
              "tx_id",
              "trace_id",
              "span_id",
              "parent_span_id",
              "kind",
              "service",
              "service_fixed",
              "start_epoch_ms",
              "elapsed_ms",
              "status",
              "error",
              "error_class",
              "error_message",
              "http_method",
              "path",
              "remote_ip",
              "user_id",
              "thread",
              "thread_name",
              "thread_name_id",
              "virtual_thread",
              "flags",
              "xlog_flags",
              "dispatch_count",
              "request_id",
              "headers",
              "env",
              "version",
              "cpu_time_ms",
              "alloc_bytes",
              "profile_captured",
              "truncated",
              "total_steps",
              "steps",
              "summary",
              "summary_overflow"),
          Set.of(
              "seq",
              "parent",
              "type",
              "name_id",
              "name",
              "target_id",
              "target",
              "start_offset_ms",
              "elapsed_ms",
              "status",
              "error",
              "rows",
              "fetch_ms",
              "detail",
              "stack",
              "thread",
              "repeat_count"));

  public boolean supportsTransactionField(String field) {
    return transactionFields.contains(field);
  }

  public boolean supportsStepField(String field) {
    return stepFields.contains(field);
  }
}
