/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.stats;

import io.argos.agent.bootstrap.FailureKind;
import io.argos.agent.core.metric.CounterSource;
import io.argos.proto.LatencyBuckets;
import io.argos.proto.ProtocolLimits;
import io.argos.proto.v1.ExtStat;
import io.argos.proto.v1.SqlStat;
import io.argos.proto.v1.StatBatch;
import io.argos.proto.v1.TransactionKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * The full statistics of SQL executions and outgoing calls (10 §9.3, F-A22, D-12): every execution
 * inside a transaction, sampled or not, summed per key in wall-clock windows of {@code
 * stats.window-seconds} (10 s, {@code window_start = floor(epoch / len) * len}).
 *
 * <ul>
 *   <li>SQL key ({@code sql_id}, {@code db_id}, transaction kind): count, errors, execute time,
 *       fetch time and rows (C-185 (2): the fetch arrives later through {@link #recordSqlFetch} and
 *       does not count an execution), the largest execute time and the 15 latency buckets of {@link
 *       LatencyBuckets} over the execute time;
 *   <li>EXT key ({@code host_id}, transaction kind): count, errors ({@link FailureKind#HTTP_5XX}
 *       and {@link FailureKind#OTHER}), timeouts ({@link FailureKind#TIMEOUT}), connection failures
 *       ({@link FailureKind#CONNECT_FAIL}), total and largest time, 15 buckets.
 * </ul>
 *
 * <p><b>Application threads</b> ({@link #recordSql}, {@link #recordSqlFetch}, {@link #recordExt})
 * take no lock and never wait: one wall-clock read, the window's writer count ({@link LongAdder},
 * up and down), a {@link ConcurrentHashMap} lookup and atomic additions to the key's {@link
 * AtomicLongArray} cell ({@code getAndAdd} is a single atomic instruction, no retry loop; only a
 * new maximum retries a CAS). The first record of a new window swaps the current window with one
 * CAS.
 *
 * <p><b>Bounds</b> (10 §14): at most {@code stats.max-keys} (5,000) SQL keys and {@value
 * #MAX_EXT_KEYS} EXT keys per window; a record of a key beyond the cap is added to the overflow key
 * of its kind (id 0) and counted in {@code argos_agent_stats_overflow_total{kind="sql|ext"}}
 * ({@link #counters()}). At most three windows are alive at once: the current one and the ones
 * waiting for their flush.
 *
 * <p><b>Flush</b> ({@code argos-metrics}, {@link #drain}): a window is detached {@value
 * #DETACH_GRACE_MS} ms after its end (a record that read the clock before the end still finds it
 * until then) and read out as soon as no record is in progress on it (its writer count is 0), so no
 * addition is lost. A record that comes after the detach finds no window of its time and is counted
 * in the current window: the totals stay exact, only its window moves. Empty windows are not sent.
 * {@link #drainAll()} (shutdown, {@code AgentBye}) reads every window at once, the open one
 * included.
 *
 * <p>One window becomes one {@link StatBatch} ({@code window_start_ms}, {@code window_len_s}),
 * split into several batches of the same window when the rows exceed {@value
 * ProtocolLimits#MAX_SQL_STATS_PER_BATCH} SQL or {@value ProtocolLimits#MAX_EXT_STATS_PER_BATCH}
 * EXT rows (the overflow rows can add one per kind); the server adds rows of the same key. {@code
 * StatBatch.counters} stays empty: the agent's own counters travel in the {@code MetricBatch} (10
 * §11, WO-16 PM question 1).
 */
public final class StatsAggregator implements CounterSource {
  /** {@code argos_agent_stats_overflow_total{kind}} of {@link #counters()}. */
  public static final String OVERFLOW_METRIC = "argos_agent_stats_overflow_total";

  /**
   * {@code argos_agent_stats_windows_dropped_total} of {@link #counters()}: windows let go by the
   * bound of {@value #MAX_ATTACHED_WINDOWS} linked windows (ADR-0010 C-229 (7)).
   */
  public static final String WINDOWS_DROPPED_METRIC = "argos_agent_stats_windows_dropped_total";

  /** EXT keys per window (proto {@code StatBatch.ext}: at most 1,000). */
  public static final int MAX_EXT_KEYS = ProtocolLimits.MAX_EXT_STATS_PER_BATCH;

  /** Default {@code stats.max-keys}. */
  public static final int DEFAULT_MAX_SQL_KEYS = 5000;

  /** Default {@code stats.window-seconds}. */
  public static final int DEFAULT_WINDOW_SECONDS = 10;

  /** Time after a window's end before it is detached and sent (10 §9.3: window end + 1 s). */
  static final long DETACH_GRACE_MS = 1000;

  /**
   * How long a detached window waits for its writers before it is read anyway (real time; a writer
   * holds it for microseconds unless its thread is suspended).
   */
  static final long MAX_PENDING_NANOS = 30_000_000_000L;

  /** How long {@link #drainAll()} waits for the writers of the windows it reads. */
  static final long DRAIN_ALL_WAIT_NANOS = 50_000_000L;

  /** Windows kept linked when no flush unlinks them ({@link #trim}). */
  static final int MAX_ATTACHED_WINDOWS = 8;

  // SQL cell layout.
  static final int SQL_COUNT = 0;
  static final int SQL_ERRORS = 1;
  static final int SQL_EXEC_NANOS = 2;
  static final int SQL_FETCH_NANOS = 3;
  static final int SQL_MAX_NANOS = 4;
  static final int SQL_ROWS = 5;
  static final int SQL_BUCKET0 = 6;

  // EXT cell layout.
  static final int EXT_COUNT = 0;
  static final int EXT_ERRORS = 1;
  static final int EXT_TIMEOUTS = 2;
  static final int EXT_CONNECT_FAILS = 3;
  static final int EXT_NANOS = 4;
  static final int EXT_MAX_NANOS = 5;
  static final int EXT_BUCKET0 = 6;

  static final int CELL_LENGTH = 6 + LatencyBuckets.COUNT;

  /** Wall clock of the windows (tests inject one). */
  public interface Clock {
    long currentTimeMillis();
  }

  /** {@link System#currentTimeMillis()}. */
  public static final Clock SYSTEM =
      new Clock() {
        @Override
        public long currentTimeMillis() {
          return System.currentTimeMillis();
        }
      };

  private final long windowMs;
  private final int windowSeconds;
  private final int maxSqlKeys;
  private final int maxExtKeys;
  private final Clock clock;
  private final AtomicReference<Window> current;
  private final LongAdder sqlOverflow = new LongAdder();
  private final LongAdder extOverflow = new LongAdder();
  private final LongAdder windowsDropped = new LongAdder();

  /** Detached windows that still had writers at the last {@link #drain} (flush thread only). */
  private final List<Window> detached = new ArrayList<Window>(4);

  /**
   * @param windowSeconds {@code stats.window-seconds} (at least 1)
   * @param maxSqlKeys {@code stats.max-keys} (at least 1)
   * @param maxExtKeys EXT keys per window (at least 1)
   * @param clock the wall clock
   */
  public StatsAggregator(int windowSeconds, int maxSqlKeys, int maxExtKeys, Clock clock) {
    this.windowSeconds = Math.max(1, windowSeconds);
    this.windowMs = this.windowSeconds * 1000L;
    this.maxSqlKeys = Math.max(1, maxSqlKeys);
    this.maxExtKeys = Math.max(1, maxExtKeys);
    this.clock = clock == null ? SYSTEM : clock;
    this.current = new AtomicReference<Window>(new Window(start(this.clock.currentTimeMillis())));
  }

  /** The defaults: 10 s windows, 5,000 SQL and 1,000 EXT keys, the system clock. */
  public StatsAggregator() {
    this(DEFAULT_WINDOW_SECONDS, DEFAULT_MAX_SQL_KEYS, MAX_EXT_KEYS, SYSTEM);
  }

  public int windowSeconds() {
    return windowSeconds;
  }

  /** The start of the window that holds {@code epochMs}. */
  long start(long epochMs) {
    return Math.floorDiv(epochMs, windowMs) * windowMs;
  }

  // --- application threads ---------------------------------------------------------------------

  /**
   * One SQL execution of a transaction of {@code kind} ({@link io.argos.agent.bootstrap.Kind}):
   * counts it, its error flag, its execute time and bucket. Never throws, never blocks.
   */
  public void recordSql(int kind, long sqlId, long dbId, long execNanos, boolean error) {
    try {
      Window w = enter();
      try {
        AtomicLongArray cell = sqlCell(w, kind(kind), sqlId, dbId, true);
        long nanos = Math.max(0L, execNanos);
        cell.getAndIncrement(SQL_COUNT);
        if (error) {
          cell.getAndIncrement(SQL_ERRORS);
        }
        cell.getAndAdd(SQL_EXEC_NANOS, nanos);
        max(cell, SQL_MAX_NANOS, nanos);
        cell.getAndIncrement(SQL_BUCKET0 + LatencyBuckets.indexOfMicros(nanos / 1000));
      } finally {
        w.writers.decrement();
      }
    } catch (Throwable ignored) {
      // Statistics never disturb the application call.
    }
  }

  /**
   * The rows and fetch time of a result set of an execution of the key (C-185 (2)): added to the
   * key's {@code fetch_rows} and fetch time, not to its count.
   */
  public void recordSqlFetch(int kind, long sqlId, long dbId, long fetchNanos, long rows) {
    try {
      if (fetchNanos <= 0 && rows <= 0) {
        return;
      }
      Window w = enter();
      try {
        // The execution was counted once (C-229 (6)): the fetch does not count an overflow again.
        AtomicLongArray cell = sqlCell(w, kind(kind), sqlId, dbId, false);
        if (fetchNanos > 0) {
          cell.getAndAdd(SQL_FETCH_NANOS, fetchNanos);
        }
        if (rows > 0) {
          cell.getAndAdd(SQL_ROWS, rows);
        }
      } finally {
        w.writers.decrement();
      }
    } catch (Throwable ignored) {
      // Statistics never disturb the application call.
    }
  }

  /** One outgoing call of a transaction of {@code kind} with its {@link FailureKind}. */
  public void recordExt(int kind, long hostId, long elapsedNanos, int failureKind) {
    try {
      Window w = enter();
      try {
        AtomicLongArray cell = extCell(w, kind(kind), hostId);
        long nanos = Math.max(0L, elapsedNanos);
        cell.getAndIncrement(EXT_COUNT);
        switch (failureKind) {
          case FailureKind.TIMEOUT:
            cell.getAndIncrement(EXT_TIMEOUTS);
            break;
          case FailureKind.CONNECT_FAIL:
            cell.getAndIncrement(EXT_CONNECT_FAILS);
            break;
          case FailureKind.HTTP_5XX:
          case FailureKind.OTHER:
            cell.getAndIncrement(EXT_ERRORS);
            break;
          default:
            break;
        }
        cell.getAndAdd(EXT_NANOS, nanos);
        max(cell, EXT_MAX_NANOS, nanos);
        cell.getAndIncrement(EXT_BUCKET0 + LatencyBuckets.indexOfMicros(nanos / 1000));
      } finally {
        w.writers.decrement();
      }
    } catch (Throwable ignored) {
      // Statistics never disturb the application call.
    }
  }

  /**
   * The window to record into, registered as a writer ({@link Window#writers}; the caller
   * decrements it when done). A window the flush thread detached meanwhile is left again and the
   * next one taken: the flush reads a detached window only once it has no writer, so no addition is
   * lost (a Dekker pairing of the writer count and the volatile {@link Window#detached} flag).
   */
  private Window enter() {
    while (true) {
      Window w = window();
      w.writers.increment();
      if (!w.detached) {
        return w;
      }
      w.writers.decrement();
    }
  }

  /** The window of now: the current one, a new one (one CAS), or a late record's own window. */
  private Window window() {
    long start = start(clock.currentTimeMillis());
    while (true) {
      Window head = current.get();
      if (head.start == start) {
        return head;
      }
      if (start > head.start) {
        Window next = new Window(start);
        next.previous = head;
        if (current.compareAndSet(head, next)) {
          trim(next);
          return next;
        }
        continue;
      }
      // A clock read from before the current window: its window if it is still attached.
      Window w = head.previous;
      while (w != null) {
        if (w.start == start) {
          return w;
        }
        if (w.start < start) {
          break;
        }
        w = w.previous;
      }
      return head;
    }
  }

  /**
   * Keeps at most {@value #MAX_ATTACHED_WINDOWS} windows linked from {@code head}. The flush of
   * {@code argos-metrics} unlinks ended windows every second, so this cut only matters when no
   * flush runs (the thread did not start): the windows beyond the bound are then let go instead of
   * growing without bound (10 §14). Each window let go counts once in {@code
   * argos_agent_stats_windows_dropped_total} (C-229 (7)); a window the flush collected before is
   * not counted ({@link Window#claimed}). Only in a race with a flush that is collecting the same
   * chain can a window be counted and still be sent; no record is lost for the count.
   */
  private void trim(Window head) {
    Window w = head;
    for (int depth = 1; w != null; depth++) {
      if (depth >= MAX_ATTACHED_WINDOWS) {
        Window cut = w.previous;
        w.previous = null;
        for (Window d = cut; d != null; d = d.previous) {
          if (d.claimed.compareAndSet(false, true)) {
            windowsDropped.increment();
          }
        }
        return;
      }
      w = w.previous;
    }
  }

  /**
   * The cell of a SQL key in {@code w}, or the overflow key of its kind when the window holds its
   * maximum of keys. {@code countOverflow}: an execution counts in {@code
   * argos_agent_stats_overflow_total{kind="sql"}}, the fetch record of the same execution does not
   * (one count per execution, ADR-0010 C-229 (6)).
   */
  private AtomicLongArray sqlCell(
      Window w, int kind, long sqlId, long dbId, boolean countOverflow) {
    Key key = new Key(sqlId, dbId, kind);
    AtomicLongArray cell = w.sql.get(key);
    if (cell != null) {
      return cell;
    }
    if (sqlId == 0 && dbId == 0) {
      return admit(w.sql, key);
    }
    if (w.sqlKeys.incrementAndGet() > maxSqlKeys) {
      w.sqlKeys.decrementAndGet();
      if (countOverflow) {
        sqlOverflow.increment();
      }
      return admit(w.sql, new Key(0, 0, kind));
    }
    AtomicLongArray created = new AtomicLongArray(CELL_LENGTH);
    AtomicLongArray raced = w.sql.putIfAbsent(key, created);
    if (raced != null) {
      w.sqlKeys.decrementAndGet();
      return raced;
    }
    return created;
  }

  private AtomicLongArray extCell(Window w, int kind, long hostId) {
    Key key = new Key(hostId, 0, kind);
    AtomicLongArray cell = w.ext.get(key);
    if (cell != null) {
      return cell;
    }
    if (hostId == 0) {
      return admit(w.ext, key);
    }
    if (w.extKeys.incrementAndGet() > maxExtKeys) {
      w.extKeys.decrementAndGet();
      extOverflow.increment();
      return admit(w.ext, new Key(0, 0, kind));
    }
    AtomicLongArray created = new AtomicLongArray(CELL_LENGTH);
    AtomicLongArray raced = w.ext.putIfAbsent(key, created);
    if (raced != null) {
      w.extKeys.decrementAndGet();
      return raced;
    }
    return created;
  }

  /** The cell of an id-0 key (overflow or unknown id): at most one per kind, outside the cap. */
  private static AtomicLongArray admit(ConcurrentHashMap<Key, AtomicLongArray> map, Key key) {
    AtomicLongArray cell = map.get(key);
    if (cell != null) {
      return cell;
    }
    AtomicLongArray created = new AtomicLongArray(CELL_LENGTH);
    AtomicLongArray raced = map.putIfAbsent(key, created);
    return raced != null ? raced : created;
  }

  private static void max(AtomicLongArray cell, int index, long value) {
    long seen = cell.get(index);
    while (value > seen) {
      if (cell.compareAndSet(index, seen, value)) {
        return;
      }
      seen = cell.get(index);
    }
  }

  /** Transaction kinds 1..3 as given; anything else is {@code TRANSACTION_KIND_UNSPECIFIED}. */
  private static int kind(int kind) {
    return kind >= 1 && kind <= 3 ? kind : 0;
  }

  // --- flush thread ----------------------------------------------------------------------------

  /**
   * The flush of {@code argos-metrics} at {@code nowMs}: detaches every window that ended at least
   * {@value #DETACH_GRACE_MS} ms ago (the current one too, after a CAS that installs the window of
   * now), then reads out every detached window that has no writer left (a window still written to
   * waits for the next call, at most 30 s). Returns the batches of the windows read out, oldest
   * first, without empty windows. One thread at a time.
   */
  public List<StatBatch> drain(long nowMs) {
    List<StatBatch> out = new ArrayList<StatBatch>(2);
    detachEnded(nowMs);
    for (int i = 0; i < detached.size(); ) {
      Window w = detached.get(i);
      if (w.writers.sum() == 0 || System.nanoTime() - w.detachedNanos > MAX_PENDING_NANOS) {
        build(w, out);
        detached.remove(i);
      } else {
        i++;
      }
    }
    return out;
  }

  /** Detaches the windows that ended {@value #DETACH_GRACE_MS} ms before {@code nowMs}. */
  private void detachEnded(long nowMs) {
    long nowStart = start(nowMs);
    Window head = current.get();
    if (head.start < nowStart && nowMs >= head.start + windowMs + DETACH_GRACE_MS) {
      Window next = new Window(nowStart);
      next.previous = head;
      if (!current.compareAndSet(head, next)) {
        head = current.get();
      } else {
        head = next;
      }
    }
    Window successor = head;
    Window w = head.previous;
    while (w != null) {
      if (nowMs >= w.start + windowMs + DETACH_GRACE_MS) {
        successor.previous = null;
        collect(w, detached);
        break;
      }
      successor = w;
      w = w.previous;
    }
  }

  /**
   * Every window read out now, the open one included (shutdown: the open window is sent once before
   * {@code AgentBye}, 10 §10.2). The current window is replaced by an empty one, so that a record
   * arriving meanwhile is not lost for a later call.
   */
  public List<StatBatch> drainAll() {
    List<StatBatch> out = new ArrayList<StatBatch>(4);
    Window head = current.get();
    Window next = new Window(head.start);
    while (!current.compareAndSet(head, next)) {
      head = current.get();
      next = new Window(head.start);
    }
    collect(head, detached);
    long deadline = System.nanoTime() + DRAIN_ALL_WAIT_NANOS;
    for (Window w : detached) {
      // Writers finish within microseconds; the wait is bounded (shutdown hook).
      while (w.writers.sum() != 0 && System.nanoTime() < deadline) {
        LockSupport.parkNanos(100_000L);
      }
      build(w, out);
    }
    detached.clear();
    return out;
  }

  /**
   * Appends {@code w} and every older window linked from it to {@code into}, oldest first, cuts
   * their links and marks them detached (in that order: a writer that finds one before the cut
   * registers before the flag, see {@link #enter()}).
   */
  private static void collect(Window w, List<Window> into) {
    List<Window> chain = new ArrayList<Window>(4);
    while (w != null) {
      // Claimed so that the bound (trim) does not count it as dropped; read in any case: a flush
      // that races with the bound still sends the window (the count may then be one too high).
      w.claimed.set(true);
      chain.add(w);
      Window older = w.previous;
      w.previous = null;
      w = older;
    }
    for (int i = chain.size() - 1; i >= 0; i--) {
      Window d = chain.get(i);
      d.detachedNanos = System.nanoTime();
      d.detached = true;
      into.add(d);
    }
  }

  /** The batches of one window (none when it is empty). */
  private void build(Window w, List<StatBatch> out) {
    List<SqlStat> sql = new ArrayList<SqlStat>(w.sql.size());
    for (Map.Entry<Key, AtomicLongArray> e : w.sql.entrySet()) {
      SqlStat row = sqlRow(e.getKey(), e.getValue());
      if (row != null) {
        sql.add(row);
      }
    }
    List<ExtStat> ext = new ArrayList<ExtStat>(w.ext.size());
    for (Map.Entry<Key, AtomicLongArray> e : w.ext.entrySet()) {
      ExtStat row = extRow(e.getKey(), e.getValue());
      if (row != null) {
        ext.add(row);
      }
    }
    if (sql.isEmpty() && ext.isEmpty()) {
      return;
    }
    int si = 0;
    int ei = 0;
    while (si < sql.size() || ei < ext.size()) {
      StatBatch.Builder batch =
          StatBatch.newBuilder().setWindowStartMs(w.start).setWindowLenS(windowSeconds);
      int sqlEnd = Math.min(sql.size(), si + ProtocolLimits.MAX_SQL_STATS_PER_BATCH);
      batch.addAllSql(sql.subList(si, sqlEnd));
      si = sqlEnd;
      int extEnd = Math.min(ext.size(), ei + ProtocolLimits.MAX_EXT_STATS_PER_BATCH);
      batch.addAllExt(ext.subList(ei, extEnd));
      ei = extEnd;
      out.add(batch.build());
    }
  }

  private static SqlStat sqlRow(Key key, AtomicLongArray cell) {
    long count = cell.get(SQL_COUNT);
    long exec = cell.get(SQL_EXEC_NANOS);
    long fetch = cell.get(SQL_FETCH_NANOS);
    long rows = cell.get(SQL_ROWS);
    if (count == 0 && fetch == 0 && rows == 0) {
      return null;
    }
    long execUs = exec / 1000;
    long fetchUs = fetch / 1000;
    SqlStat.Builder row =
        SqlStat.newBuilder()
            .setSqlId(key.a)
            .setDbId(key.b)
            .setKind(kindOf(key.kind))
            .setCount(u32(count))
            .setErrorCount(u32(cell.get(SQL_ERRORS)))
            .setSumUs(execUs + fetchUs)
            .setMaxUs(u32(cell.get(SQL_MAX_NANOS) / 1000))
            .setFetchRows(rows)
            .setSumExecUs(execUs)
            .setSumFetchUs(fetchUs);
    for (int i = 0; i < LatencyBuckets.COUNT; i++) {
      row.addBuckets(u32(cell.get(SQL_BUCKET0 + i)));
    }
    return row.build();
  }

  private static ExtStat extRow(Key key, AtomicLongArray cell) {
    long count = cell.get(EXT_COUNT);
    if (count == 0) {
      return null;
    }
    ExtStat.Builder row =
        ExtStat.newBuilder()
            .setHostId(key.a)
            .setKind(kindOf(key.kind))
            .setCount(u32(count))
            .setErrorCount(u32(cell.get(EXT_ERRORS)))
            .setTimeoutCount(u32(cell.get(EXT_TIMEOUTS)))
            .setConnectFailCount(u32(cell.get(EXT_CONNECT_FAILS)))
            .setSumUs(cell.get(EXT_NANOS) / 1000)
            .setMaxUs(u32(cell.get(EXT_MAX_NANOS) / 1000));
    for (int i = 0; i < LatencyBuckets.COUNT; i++) {
      row.addBuckets(u32(cell.get(EXT_BUCKET0 + i)));
    }
    return row.build();
  }

  private static TransactionKind kindOf(int kind) {
    TransactionKind k = TransactionKind.forNumber(kind);
    return k == null ? TransactionKind.TRANSACTION_KIND_UNSPECIFIED : k;
  }

  /** A uint32 field value: negative or above 2^32-1 is clamped. */
  private static int u32(long value) {
    if (value <= 0) {
      return 0;
    }
    return value >= 0xFFFF_FFFFL ? -1 : (int) value;
  }

  // --- self metrics ----------------------------------------------------------------------------

  /**
   * {@code argos_agent_stats_overflow_total{kind="sql"}} and {@code {kind="ext"}}: executions and
   * calls added to an overflow key because their window held its maximum of keys (one per
   * execution); {@code argos_agent_stats_windows_dropped_total}: windows let go by the bound of
   * linked windows.
   */
  @Override
  public Map<String, Long> counters() {
    Map<String, Long> out = new LinkedHashMap<String, Long>(4);
    out.put(OVERFLOW_METRIC + "{kind=\"sql\"}", sqlOverflow.sum());
    out.put(OVERFLOW_METRIC + "{kind=\"ext\"}", extOverflow.sum());
    out.put(WINDOWS_DROPPED_METRIC, windowsDropped.sum());
    return Collections.unmodifiableMap(out);
  }

  public long sqlOverflow() {
    return sqlOverflow.sum();
  }

  public long extOverflow() {
    return extOverflow.sum();
  }

  /** Windows let go by the bound of linked windows (C-229 (7)). */
  public long windowsDropped() {
    return windowsDropped.sum();
  }

  /** Estimated heap bytes of the windows alive (self-diagnostics, N-02). */
  public long estimatedBytes() {
    long cells = 0;
    Window w = current.get();
    while (w != null) {
      cells += w.sql.size() + w.ext.size();
      w = w.previous;
    }
    for (Window d : detached) {
      cells += d.sql.size() + d.ext.size();
    }
    return cells * estimatedCellBytes();
  }

  /** Estimated heap bytes of one key's cell: key, array header, counters, map entry. */
  public static long estimatedCellBytes() {
    return 32L + 16L + 8L * CELL_LENGTH + 48L;
  }

  /** A statistics key: ({@code sql_id}, {@code db_id}, kind) or ({@code host_id}, 0, kind). */
  static final class Key {
    final long a;
    final long b;
    final int kind;

    Key(long a, long b, int kind) {
      this.a = a;
      this.b = b;
      this.kind = kind;
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof Key)) {
        return false;
      }
      Key k = (Key) other;
      return a == k.a && b == k.b && kind == k.kind;
    }

    @Override
    public int hashCode() {
      long h = a * 0x9E3779B97F4A7C15L + b * 0xC2B2AE3D27D4EB4FL + kind;
      return (int) (h ^ (h >>> 32));
    }
  }

  /** One window: the cells of its keys; {@link #previous} links the older windows still alive. */
  static final class Window {
    final long start;
    final ConcurrentHashMap<Key, AtomicLongArray> sql =
        new ConcurrentHashMap<Key, AtomicLongArray>();
    final ConcurrentHashMap<Key, AtomicLongArray> ext =
        new ConcurrentHashMap<Key, AtomicLongArray>();
    final AtomicInteger sqlKeys = new AtomicInteger();
    final AtomicInteger extKeys = new AtomicInteger();

    /** Records in progress on this window ({@link #enter()}). */
    final LongAdder writers = new LongAdder();

    /** Set by the flush thread once the window is unlinked; a writer then moves on. */
    volatile boolean detached;

    /** {@code System.nanoTime()} of the detach (flush thread only). */
    long detachedNanos;

    volatile Window previous;

    /**
     * Set by the flush that reads the window ({@link #collect}) and by the bound that lets it go
     * ({@link #trim}); the bound counts a window only if it set it first.
     */
    final AtomicBoolean claimed = new AtomicBoolean();

    Window(long start) {
      this.start = start;
    }
  }
}
