/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.queries;

/**
 * One execution of a statement, as the client saw it.
 *
 * @param queryId query id assigned by the runner (system.query_log key)
 * @param label {@code Q01/1h} or {@code Q14/accuracy_day}
 * @param phase {@code cold}, {@code warm1}..{@code warmN}, {@code once}
 * @param account ClickHouse user
 * @param wallMs client wall time from request to the last byte of the response
 * @param rows result rows (TabSeparated lines; 0 for statements)
 * @param bytes response bytes
 * @param errorName ClickHouse error name, or null when the statement succeeded
 * @param errorMessage error message (first line), or null
 * @param startNanos {@link System#nanoTime()} when the request was sent
 * @param endNanos {@link System#nanoTime()} when the response ended; the machine CPU load of the
 *     interval is computed when the report is written ({@link MachineLoad#mean}), once the sample
 *     after the end exists
 * @param body response body when kept (consistency checks, steps), else null
 */
public record Measurement(
    String queryId,
    String label,
    String phase,
    String account,
    double wallMs,
    long rows,
    long bytes,
    String errorName,
    String errorMessage,
    long startNanos,
    long endNanos,
    String body) {

  /** True when the statement failed. */
  public boolean failed() {
    return errorName != null;
  }

  /** Mean machine CPU load in [0, 1] while it ran; NaN without a sampler or samples. */
  public double cpuLoad(MachineLoad load) {
    return load == null ? Double.NaN : load.mean(startNanos, endNanos);
  }
}
