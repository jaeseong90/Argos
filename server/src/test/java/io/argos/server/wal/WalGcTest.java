/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.wal;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.wal.api.WalPosition;
import io.argos.server.wal.api.WalRecordType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** WAL segment reclamation (12 §3.4 "회수"). */
@Tag("req:D-13")
@Tag("req:DSN-12-02")
class WalGcTest {

  @TempDir Path dir;

  /** Coverage set by the test. */
  private static final class Coverage implements WalCoverage {
    final Map<WalRecordType, Long> through = new EnumMap<>(WalRecordType.class);
    final List<WalPosition> pending = new ArrayList<>();

    @Override
    public long durableThroughPacked(WalRecordType dataType) {
      return through.getOrDefault(dataType, -1L);
    }

    @Override
    public Collection<WalPosition> pendingIntents() {
      return pending;
    }
  }

  @Test
  @DisplayName(
      "T-SV-010i GC deletes only a prefix of closed segments whose records are all covered")
  void prefixOfCoveredSegments() throws Exception {
    FileWal wal = WalTestSupport.started(dir, 2048, 1 << 22, new SimpleMeterRegistry());
    List<WalPosition> tx = new ArrayList<>();
    for (int i = 1; i <= 24; i++) {
      tx.add(WalTestSupport.appendTx(wal, 1, i, "g".repeat(300)));
    }
    WalPosition stat =
        wal.append(WalRecordType.STAT, 1, 77, 1, System.currentTimeMillis(), 0, new byte[100]);
    for (int i = 25; i <= 36; i++) {
      tx.add(WalTestSupport.appendTx(wal, 1, i, "g".repeat(300)));
    }
    assertThat(tx.get(35).segmentId()).isGreaterThan(stat.segmentId());
    int segmentsBefore = wal.segments().size();
    assertThat(segmentsBefore).isGreaterThan(4);
    Coverage coverage = new Coverage();
    WalGc gc = new WalGc(wal, coverage);

    // Nothing covered: nothing deleted.
    assertThat(gc.collect()).isZero();

    // TX covered up to the 10th record: the segments holding only records 1..10 go.
    coverage.through.put(WalRecordType.TX, tx.get(9).packed());
    int deleted = gc.collect();
    assertThat(deleted).isPositive();
    int firstLeft = wal.segments().get(0).segmentId();
    assertThat(firstLeft).isLessThanOrEqualTo(tx.get(10).segmentId());
    assertThat(firstLeft).isGreaterThan(tx.get(0).segmentId());

    // A pending intent in a later segment holds back that segment and everything after it.
    coverage.through.put(WalRecordType.TX, tx.get(35).packed());
    coverage.pending.add(new WalPosition(tx.get(15).segmentId(), 16, 50));
    gc.collect();
    assertThat(wal.segments().get(0).segmentId()).isEqualTo(tx.get(15).segmentId());

    // The STAT record is not covered: its segment stays even when everything else is.
    coverage.pending.clear();
    gc.collect();
    assertThat(wal.segments().get(0).segmentId()).isEqualTo(stat.segmentId());
    coverage.through.put(WalRecordType.STAT, stat.packed());
    gc.collect();
    // The active segment is never deleted.
    assertThat(wal.segments()).hasSize(1);
    assertThat(wal.segments().get(0).segmentId()).isEqualTo(wal.activeSegmentId());
    long remaining = wal.segments().get(0).size();
    assertThat(wal.bytesInUse()).isEqualTo(remaining);
    wal.close();
  }
}
