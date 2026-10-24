/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator.load;

import io.argos.storage.generator.report.LatencyHistogram;
import io.argos.storage.generator.rows.Table;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Insert-side counters per table (thread-safe): blocks, rows, bytes, re-sends and what the server
 * reported for them, retries, insert latency, ingest lag (replay: server receive to insert ack).
 */
public final class LoaderStats {

  private static final class PerTable {
    long blocks;
    long rows;
    long bytes;
    long writtenRows;
    long duplicateSends;
    long duplicateWrittenRows;
    long recoverySends;
    long recoveryWrittenRows;
    long retries;
    long firstSendMs = Long.MAX_VALUE;
    long lastAckMs = Long.MIN_VALUE;
    long maxQueuedRows;
    final LatencyHistogram insertMs = new LatencyHistogram();
  }

  private final PerTable[] tables = new PerTable[Table.ALL.size()];
  private final LatencyHistogram lag = new LatencyHistogram();
  private final LatencyHistogram blockLag = new LatencyHistogram();
  private LatencyHistogram[] stepLag = new LatencyHistogram[0];
  private long stepStartMs;
  private long stepMs = 1;
  private long drainedBeforeReceive;

  public LoaderStats() {
    for (int i = 0; i < tables.length; i++) {
      tables[i] = new PerTable();
    }
  }

  synchronized void inserted(Block block, long startMs, long ackMs, long written) {
    PerTable t = tables[block.table().index()];
    t.blocks++;
    t.rows += block.rows();
    t.bytes += block.data().length;
    t.writtenRows += Math.max(0, written);
    t.firstSendMs = Math.min(t.firstSendMs, startMs);
    t.lastAckMs = Math.max(t.lastAckMs, ackMs);
    t.insertMs.record(ackMs - startMs);
  }

  synchronized void resent(Block block, long written, boolean recovery) {
    PerTable t = tables[block.table().index()];
    if (recovery) {
      t.recoverySends++;
      t.recoveryWrittenRows += Math.max(0, written);
    } else {
      t.duplicateSends++;
      t.duplicateWrittenRows += Math.max(0, written);
    }
  }

  synchronized void retried(Table table) {
    tables[table.index()].retries++;
  }

  synchronized void queued(Table table, long rows) {
    PerTable t = tables[table.index()];
    t.maxQueuedRows = Math.max(t.maxQueuedRows, rows);
  }

  /**
   * Splits the lag statistics into {@code steps} TPS steps of {@code stepMs} from {@code start}.
   */
  public synchronized void configureSteps(long startMs, long stepMs, int steps) {
    this.stepStartMs = startMs;
    this.stepMs = Math.max(1, stepMs);
    this.stepLag = new LatencyHistogram[steps];
    for (int i = 0; i < steps; i++) {
      stepLag[i] = new LatencyHistogram();
    }
  }

  /** Records the ingest lag of every row of an acknowledged block (replay). */
  void lag(Block block, long ackMs) {
    long[] received = block.receivedMs();
    LatencyHistogram step = null;
    synchronized (this) {
      if (stepLag.length > 0) {
        long i = Math.floorDiv(block.minReceivedMs() - stepStartMs, stepMs);
        step = stepLag[(int) Math.max(0, Math.min(stepLag.length - 1, i))];
      }
    }
    long early = 0;
    for (long r : received) {
      if (r > ackMs) {
        early++; // a late row released by the final drain before its receive time
        continue;
      }
      lag.record(ackMs - r);
      if (step != null) {
        step.record(ackMs - r);
      }
    }
    if (early > 0) {
      synchronized (this) {
        drainedBeforeReceive += early;
      }
    }
    if (received.length > 0) {
      blockLag.record(ackMs - block.minReceivedMs());
    }
  }

  /** Ingest lag of TPS step {@code i} (replay). */
  public synchronized LatencyHistogram stepLag(int i) {
    return stepLag[i];
  }

  /** Rows inserted into a table (first sends only). */
  public synchronized long rows(Table table) {
    return tables[table.index()].rows;
  }

  /** Blocks inserted into a table (first sends only). */
  public synchronized long blocks(Table table) {
    return tables[table.index()].blocks;
  }

  /** Re-sends of already inserted blocks. */
  public synchronized long duplicateSends(Table table) {
    return tables[table.index()].duplicateSends;
  }

  /** written_rows reported for re-sent blocks (not a deduplication signal, see toMap). */
  public synchronized long duplicateWrittenRows(Table table) {
    return tables[table.index()].duplicateWrittenRows;
  }

  /** Recovery re-inserts (INTENT without COMMIT). */
  public synchronized long recoverySends(Table table) {
    return tables[table.index()].recoverySends;
  }

  /** Ingest lag histogram (replay). */
  public LatencyHistogram lag() {
    return lag;
  }

  /** Counters as a JSON-ready map; rates over {@code elapsedMs}. */
  public synchronized Map<String, Object> toMap(long elapsedMs) {
    Map<String, Object> m = new LinkedHashMap<>();
    long totalRows = 0;
    long totalBytes = 0;
    for (Table table : Table.ALL) {
      PerTable t = tables[table.index()];
      Map<String, Object> e = new LinkedHashMap<>();
      e.put("blocks", t.blocks);
      e.put("rows", t.rows);
      e.put("bodyBytes", t.bytes);
      e.put("summaryWrittenRows", t.writtenRows);
      e.put("duplicateSends", t.duplicateSends);
      // X-ClickHouse-Summary written_rows of the re-sends: counts the rows even when the server
      // drops the block as a duplicate (26.8), so it is informational only.
      e.put("duplicateSummaryWrittenRows", t.duplicateWrittenRows);
      e.put("recoverySends", t.recoverySends);
      e.put("recoverySummaryWrittenRows", t.recoveryWrittenRows);
      e.put("retries", t.retries);
      e.put("maxQueuedRows", t.maxQueuedRows);
      long active = t.lastAckMs > t.firstSendMs ? t.lastAckMs - t.firstSendMs : 0;
      e.put("rowsPerSecondOverRun", elapsedMs > 0 ? t.rows * 1000.0 / elapsedMs : 0.0);
      e.put("rowsPerSecondWhileInserting", active > 0 ? t.rows * 1000.0 / active : 0.0);
      e.put("insertMs", t.insertMs.summary());
      m.put(table.name(), e);
      totalRows += t.rows;
      totalBytes += t.bytes;
    }
    Map<String, Object> total = new LinkedHashMap<>();
    total.put("rows", totalRows);
    total.put("bodyBytes", totalBytes);
    total.put("elapsedMs", elapsedMs);
    total.put("rowsPerSecond", elapsedMs > 0 ? totalRows * 1000.0 / elapsedMs : 0.0);
    total.put(
        "bodyMiBPerSecond", elapsedMs > 0 ? totalBytes / 1048576.0 * 1000.0 / elapsedMs : 0.0);
    m.put("total", total);
    if (lag.count() > 0) {
      m.put("ingestLagMs", lag.summary());
      m.put("blockLagMs", blockLag.summary());
      java.util.List<Object> steps = new java.util.ArrayList<>();
      for (LatencyHistogram h : stepLag) {
        steps.add(h.summary());
      }
      m.put("ingestLagMsPerStep", steps);
      m.put("rowsDrainedBeforeTheirReceiveTime", drainedBeforeReceive);
    }
    return m;
  }
}
