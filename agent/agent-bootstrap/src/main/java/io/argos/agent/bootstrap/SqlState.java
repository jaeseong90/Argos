/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

/**
 * The JDBC state of one statement object (10 §6.3 ContextStore {@code Statement -> SqlInfo}). It
 * replaces the WO-02 {@code long[] {sqlId, dbId}} so that the store value stays a bootstrap type
 * holding strings and numbers only, never an application object (10 §5.3 A-9, T-AG-004):
 *
 * <ul>
 *   <li>{@link #rawSql}: the text given to {@code prepareStatement}/{@code prepareCall}, kept by
 *       reference only until the core normalizes it ({@link Tracer#dict(int, String)} with {@link
 *       DictKind#SQL}; 10 §9.4, SEC-35, DSN-10-17). Helpers never log, cut, copy or send it, and
 *       may drop it once {@link #sqlId} is known.
 *   <li>{@link #sqlId}, {@link #dbId}: dictionary ids, 0 while unknown.
 *   <li>bind parameters (SEC-3A, 16 §6.7): values the core already rendered for the current {@code
 *       capture.sql-parameters} mode; nothing is stored in the default {@code masked} mode (the
 *       {@code set*} advice returns at once). Bounded: indexes 1 to {@value #MAX_PARAMS}, each
 *       value cut to {@value #MAX_VALUE_CHARS} characters, at most {@value #MAX_PARAM_CHARS}
 *       characters together (the step detail cap of 10 §3), and only the first parameter set of a
 *       batch ({@link #addBatch()} freezes it until {@link #endBatch()}).
 *   <li>{@link #batchCount}, {@link #execStartNanos}, {@link #seq}: the current execution; {@link
 *       #execSqlId}, {@link #txId}: its SQL id (a plain {@code Statement} runs a different text
 *       each time) and the transaction its step {@link #seq} belongs to.
 *   <li>{@link #batchSql}: the first {@code addBatch(String)} text of a plain statement's batch, by
 *       reference until its {@code executeBatch} (the step name of the batch).
 *   <li>{@link #fetch}: the fetch state of the last result set of this statement, reported at its
 *       {@code close()} or at the next execution (10 §6.3).
 *   <li>{@link #paramWhitelist}: positions (bit {@code index - 1}) whose column is in {@code
 *       capture.sql-parameters.whitelist-columns} ({@code partial} mode, 16 §6.7), computed by the
 *       core from the SQL text once per statement.
 * </ul>
 *
 * <p>Plain fields, no synchronization: a JDBC statement is used by one thread at a time, and the
 * advice path takes no lock (N-05). A racing misuse can lose a value but never the memory bounds.
 *
 * <p>Addition to 10 §3 (WO-13).
 */
public final class SqlState {
  /** Highest parameter index kept (16 §6.7 "인덱스 ≤ 64"). */
  public static final int MAX_PARAMS = 64;

  /** Characters kept of one rendered value (16 §6.7 "값 ≤ 256자"). */
  public static final int MAX_VALUE_CHARS = 256;

  /** Characters kept of all values together: the step detail cap (10 §3 {@code stepDetail}). */
  public static final int MAX_PARAM_CHARS = 4096;

  /** Raw SQL of the prepared statement, by reference until normalized; null when not seen. */
  public String rawSql;

  /** Dictionary id of the normalized SQL ({@link DictKind#SQL}); 0 until known. */
  public long sqlId;

  /** Dictionary id of the JDBC target ({@link DictKind#DB_TARGET}); 0 when unknown. */
  public long dbId;

  /** {@code addBatch} calls since the last {@link #endBatch()}. */
  public int batchCount;

  /** {@code System.nanoTime()} at the start of the outermost execution in progress. */
  public long execStartNanos;

  /** Sequence number of the SQL step of the last execution, -1 when none was recorded. */
  public int seq = -1;

  /** SQL id of the execution in progress or the last one ({@link DictKind#SQL}). */
  public long execSqlId;

  /** {@code tx_id} of the transaction that recorded the step {@link #seq}; 0 when none. */
  public long txId;

  /** First SQL text of a plain statement's batch ({@code addBatch(String)}), by reference. */
  public String batchSql;

  /** Fetch state of the last result set of this statement; null when reported or none. */
  public FetchState fetch;

  /** Whitelisted parameter positions of the {@code partial} mode (bit {@code index - 1}). */
  public long paramWhitelist;

  /** True once {@link #paramWhitelist} was computed. */
  public boolean paramWhitelistKnown;

  /** Rendered values by index - 1; null until the first one. */
  private String[] params;

  /** Characters of the values kept in {@link #params}. */
  private int paramChars;

  /** True from the first {@code addBatch} until {@link #endBatch()}. */
  private boolean frozen;

  public SqlState() {}

  /**
   * The state of a statement prepared with {@code rawSql} on a connection of target {@code dbId}.
   */
  public SqlState(String rawSql, long dbId) {
    this.rawSql = rawSql;
    this.dbId = dbId;
  }

  /**
   * Keeps the rendered value of parameter {@code index} (1-based), replacing an earlier one; null
   * removes it. Ignored for an index outside 1 to {@value #MAX_PARAMS}, while a batch froze the
   * first parameter set, and when the value would pass {@value #MAX_PARAM_CHARS} characters
   * together. The value is cut to {@value #MAX_VALUE_CHARS} characters.
   */
  public void param(int index, String rendered) {
    if (index < 1 || index > MAX_PARAMS || frozen) {
      return;
    }
    String value =
        rendered != null && rendered.length() > MAX_VALUE_CHARS
            ? rendered.substring(0, MAX_VALUE_CHARS)
            : rendered;
    String[] values = params;
    String old = values != null && index <= values.length ? values[index - 1] : null;
    int chars =
        paramChars - (old == null ? 0 : old.length()) + (value == null ? 0 : value.length());
    if (chars > MAX_PARAM_CHARS) {
      return;
    }
    if (value != null && (values == null || index > values.length)) {
      String[] grown =
          new String[Math.min(MAX_PARAMS, Math.max(index, values == null ? 8 : 2 * values.length))];
      if (values != null) {
        System.arraycopy(values, 0, grown, 0, values.length);
      }
      values = grown;
      params = grown;
    }
    if (values != null && index <= values.length) {
      values[index - 1] = value;
      paramChars = chars;
    }
  }

  /** The kept value of parameter {@code index} (1-based), or null. */
  public String param(int index) {
    String[] values = params;
    return values != null && index >= 1 && index <= values.length ? values[index - 1] : null;
  }

  /** Highest index with a kept value, 0 when none. */
  public int paramCount() {
    String[] values = params;
    if (values == null) {
      return 0;
    }
    for (int i = values.length; i > 0; i--) {
      if (values[i - 1] != null) {
        return i;
      }
    }
    return 0;
  }

  /** Characters of all kept values together (at most {@value #MAX_PARAM_CHARS}). */
  public int paramChars() {
    return paramChars;
  }

  /**
   * The step detail of the kept values (02 §5.2 "SQL 바인드 파라미터", 16 §6.7): {@code <index>=<value>}
   * in index order, separated by {@code ", "}, indexes without a value left out; null when no value
   * is kept. The values were rendered by the core for the mode in force, so nothing here reads an
   * application object. At most {@value #MAX_PARAM_CHARS} characters of values plus the separators
   * (the core cuts the detail to 4 KiB, 10 §3).
   */
  public String paramDetail() {
    String[] values = params;
    if (values == null || paramChars == 0) {
      return null;
    }
    StringBuilder out = new StringBuilder(paramChars + 8 * values.length);
    for (int i = 0; i < values.length; i++) {
      String value = values[i];
      if (value == null) {
        continue;
      }
      if (out.length() > 0) {
        out.append(", ");
      }
      out.append(i + 1).append('=').append(value);
    }
    return out.length() == 0 ? null : out.toString();
  }

  /** {@code clearParameters()}: drops the kept values, unless a batch froze the first set. */
  public void clearParams() {
    if (!frozen) {
      params = null;
      paramChars = 0;
    }
  }

  /**
   * One {@code addBatch} call: counts it and freezes the parameter set, so that only the first set
   * of the batch is kept (16 §6.7). Returns the new count.
   */
  public int addBatch() {
    frozen = true;
    if (batchCount < Integer.MAX_VALUE) {
      batchCount++;
    }
    return batchCount;
  }

  /** True while a batch froze the parameter set ({@link #addBatch()}). */
  public boolean paramsFrozen() {
    return frozen;
  }

  /**
   * The end of {@code executeBatch} or {@code clearBatch}: returns the counted items and resets the
   * count; the parameter set is no longer frozen and the batch text of a plain statement is
   * dropped.
   */
  public int endBatch() {
    int count = batchCount;
    batchCount = 0;
    frozen = false;
    batchSql = null;
    return count;
  }
}
