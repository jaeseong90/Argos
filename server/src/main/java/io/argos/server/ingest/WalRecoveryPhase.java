/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import io.argos.server.bootstrap.PhaseOrder;
import io.argos.server.bootstrap.StartupPhase;
import io.argos.server.bootstrap.StartupPhaseException;
import io.argos.server.collector.api.DedupKey;
import io.argos.server.collector.api.DedupWindow;
import io.argos.server.storage.clickhouse.ClickHouseBatchWriter;
import io.argos.server.wal.FileWal;
import io.argos.server.wal.WalDirectory;
import io.argos.server.wal.WalRecovery;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The {@code WAL_RECOVERY} step of the start sequence (DSN-12-15, 12 §3.4, D-13): before any
 * listener opens,
 *
 * <ol>
 *   <li>opens {@code argos.wal.dir} (mode 0700) and its identity (ADR-0007 C-61);
 *   <li>scans every segment, cuts torn tails and restores the deduplication window from every data
 *       record of an agent (instance, epoch, kind, seq; C-3), not of the server's gauge;
 *   <li>starts the WAL writer on a new segment, the ClickHouse writer ({@code argos_writer}), the
 *       dictionary writer, the error group writer, the Apdex cache and the representative stack
 *       filler of the error groups ({@link ErrorGroupStackFiller}, WO-27);
 *   <li>re-inserts every intent without a commit with its recorded token, then loads the records
 *       after the last intent of each table, and waits until all of them are committed (no time
 *       limit, progress logged).
 * </ol>
 *
 * <p>The phase, and with it readiness ({@code StartupSequence.isComplete()}), completes only after
 * the replay. ClickHouse must accept inserts for that; the migration phase before it has already
 * needed ClickHouse.
 */
@Component
public class WalRecoveryPhase implements StartupPhase {

  private static final Logger LOG = LoggerFactory.getLogger(WalRecoveryPhase.class);

  private final FileWal wal;
  private final ClickHouseBatchWriter writer;
  private final IngestService ingest;
  private final DictionaryResolver dictionary;
  private final ErrorGroupRecorder errorGroups;
  private final ApdexStamper apdex;
  private final ErrorGroupStackFiller errorStacks;
  private final ObjectProvider<DedupWindow> dedupWindows;

  public WalRecoveryPhase(
      FileWal wal,
      ClickHouseBatchWriter writer,
      IngestService ingest,
      DictionaryResolver dictionary,
      ErrorGroupRecorder errorGroups,
      ApdexStamper apdex,
      ErrorGroupStackFiller errorStacks,
      ObjectProvider<DedupWindow> dedupWindows) {
    this.wal = wal;
    this.writer = writer;
    this.ingest = ingest;
    this.dictionary = dictionary;
    this.errorGroups = errorGroups;
    this.apdex = apdex;
    this.errorStacks = errorStacks;
    this.dedupWindows = dedupWindows;
  }

  @Override
  public PhaseOrder order() {
    return PhaseOrder.WAL_RECOVERY;
  }

  @Override
  public String name() {
    return "wal-recovery";
  }

  @Override
  public void run() throws StartupPhaseException, InterruptedException {
    WalDirectory dir;
    try {
      dir = WalDirectory.open(wal.configuredDirectory());
    } catch (IOException e) {
      throw new StartupPhaseException(
          "argos.wal.dir " + wal.configuredDirectory() + " cannot be opened: " + e.getMessage(), e);
    }
    DedupWindow dedup = dedupWindows.getIfAvailable();
    if (dedup == null) {
      LOG.warn("No deduplication window bean: the WAL scan restores nothing (test context?)");
    }
    WalRecovery.Scan scan;
    try {
      scan =
          WalRecovery.scan(
              dir,
              record -> {
                // Records the server wrote itself (the active gauge, C-178 (1)) never passed the
                // window and must not enter it: their seqs are not the agent's.
                if (dedup != null && !ActiveGauge.isServerRecord(record.seq())) {
                  dedup.restore(
                      new DedupKey(
                          record.instanceId(), record.epoch(), record.type(), record.seq()));
                }
              });
      wal.start(dir, scan.segments());
    } catch (IOException e) {
      throw new StartupPhaseException("WAL recovery failed: " + e.getMessage(), e);
    }
    try {
      writer.start();
    } catch (IllegalStateException e) {
      throw new StartupPhaseException(e.getMessage(), e);
    }
    dictionary.start();
    errorGroups.start();
    apdex.start();
    errorStacks.start();
    try {
      ingest.recover(scan);
    } catch (IOException e) {
      throw new StartupPhaseException("WAL replay failed: " + e.getMessage(), e);
    }
  }
}
