/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.wal;

import io.argos.server.wal.api.WalPosition;
import io.argos.server.wal.api.WalRecordType;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WAL segment reclamation (12 §3.4 "회수"): the {@code argos-wal-gc} thread runs every minute and
 * deletes the oldest closed segments whose records are all covered ({@link WalCoverage}), stopping
 * at the first one that is not. Deleting only a prefix keeps the recovery invariant: an intent
 * comes after its data records, so a remaining data record never depends on an intent in a deleted
 * segment. The active segment is never deleted.
 */
public final class WalGc implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(WalGc.class);

  private static final WalRecordType[] DATA_TYPES = {
    WalRecordType.TX, WalRecordType.PROFILE, WalRecordType.METRIC, WalRecordType.STAT
  };

  private final FileWal wal;
  private final WalCoverage coverage;
  private ScheduledExecutorService executor;

  /**
   * @param wal the started WAL
   * @param coverage what ClickHouse has made durable
   */
  public WalGc(FileWal wal, WalCoverage coverage) {
    this.wal = Objects.requireNonNull(wal, "wal");
    this.coverage = Objects.requireNonNull(coverage, "coverage");
  }

  /** Starts the {@code argos-wal-gc} thread with the given period. */
  public synchronized void start(long periodMs) {
    if (executor != null) {
      return;
    }
    executor =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "argos-wal-gc");
              t.setDaemon(true);
              return t;
            });
    var unused =
        executor.scheduleWithFixedDelay(this::runSafely, periodMs, periodMs, TimeUnit.MILLISECONDS);
  }

  private void runSafely() {
    try {
      collect();
    } catch (IOException | RuntimeException e) {
      LOG.warn("WAL segment reclamation failed, retried next run: {}", e.toString());
    }
  }

  /**
   * Deletes every deletable segment now.
   *
   * @return the number of deleted segments
   */
  public synchronized int collect() throws IOException {
    int deleted = 0;
    long freed = 0;
    List<SegmentInfo> segments = wal.segments();
    Collection<WalPosition> pending = coverage.pendingIntents();
    long[] through = new long[DATA_TYPES.length];
    for (int i = 0; i < DATA_TYPES.length; i++) {
      through[i] = coverage.durableThroughPacked(DATA_TYPES[i]);
    }
    for (SegmentInfo segment : segments) {
      if (!segment.isClosed() || segment.segmentId() >= wal.activeSegmentId()) {
        break;
      }
      if (!deletable(segment, pending, through)) {
        break;
      }
      wal.deleteSegment(segment.segmentId());
      deleted++;
      freed += segment.size();
    }
    if (deleted > 0) {
      LOG.info("WAL reclamation deleted {} segments ({} bytes)", deleted, freed);
    }
    return deleted;
  }

  private static boolean deletable(
      SegmentInfo segment, Collection<WalPosition> pending, long[] through) {
    for (WalPosition intent : pending) {
      if (intent.segmentId() <= segment.segmentId()) {
        return false;
      }
    }
    for (int i = 0; i < DATA_TYPES.length; i++) {
      long last = segment.lastDataPacked(DATA_TYPES[i]);
      if (last >= 0 && (through[i] < 0 || Long.compareUnsigned(last, through[i]) > 0)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public synchronized void close() {
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
  }
}
