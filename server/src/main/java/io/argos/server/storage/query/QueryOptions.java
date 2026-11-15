/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.query;

/**
 * Per-statement limits sent in the SQL {@code SETTINGS} clause (13 §6.3, 14 §3, ADR-0007 C-64).
 * Zero means "the default": the timeout of the endpoint's {@code @ArgosLimits} (or 10 s), and the
 * {@code argos_reader} profile values for the others. Every value is clamped to the reader profile
 * (13 §7.3: a query may only lower the profile limits).
 *
 * @param timeoutSeconds {@code max_execution_time}, 1 to 10
 * @param maxRowsToRead {@code max_rows_to_read}
 * @param maxResultRows {@code max_result_rows}: a safety net only, the exact row cap is the {@code
 *     LIMIT} of the SQL (C-64), so this must be at least that {@code LIMIT}
 * @param maxMemoryBytes {@code max_memory_usage}
 */
public record QueryOptions(
    int timeoutSeconds, long maxRowsToRead, long maxResultRows, long maxMemoryBytes) {

  /** The default and largest {@code max_execution_time} (14 §3, reader profile). */
  public static final int MAX_TIMEOUT_SECONDS = 10;

  private static final QueryOptions DEFAULTS = new QueryOptions(0, 0, 0, 0);

  public QueryOptions {
    if (timeoutSeconds < 0 || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
      throw new IllegalArgumentException("timeoutSeconds must be 0 to 10: " + timeoutSeconds);
    }
    if (maxRowsToRead < 0 || maxResultRows < 0 || maxMemoryBytes < 0) {
      throw new IllegalArgumentException("Limits must not be negative");
    }
  }

  /** Every value at its default. */
  public static QueryOptions defaults() {
    return DEFAULTS;
  }

  /** With {@code max_execution_time}. */
  public QueryOptions withTimeoutSeconds(int seconds) {
    return new QueryOptions(seconds, maxRowsToRead, maxResultRows, maxMemoryBytes);
  }

  /** With {@code max_rows_to_read}. */
  public QueryOptions withMaxRowsToRead(long rows) {
    return new QueryOptions(timeoutSeconds, rows, maxResultRows, maxMemoryBytes);
  }

  /** With {@code max_result_rows}. */
  public QueryOptions withMaxResultRows(long rows) {
    return new QueryOptions(timeoutSeconds, maxRowsToRead, rows, maxMemoryBytes);
  }

  /** With {@code max_memory_usage}. */
  public QueryOptions withMaxMemoryBytes(long bytes) {
    return new QueryOptions(timeoutSeconds, maxRowsToRead, maxResultRows, bytes);
  }
}
