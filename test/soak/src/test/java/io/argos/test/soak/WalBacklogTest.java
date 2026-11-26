/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.soak;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.storage.api.BlockIntent;
import io.argos.server.storage.api.BlockToken;
import io.argos.server.storage.api.Table;
import io.argos.server.wal.WalFormat;
import io.argos.server.wal.api.WalPosition;
import io.argos.server.wal.api.WalRecordType;
import io.argos.test.soak.WalBacklog.Entry;
import io.argos.test.soak.WalBacklog.Segment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The unloaded WAL backlog of the soak verdict (ADR-0010 C-259 (4)). */
@Tag("req:D-13")
class WalBacklogTest {

  private static final int H = WalFormat.SEGMENT_HEADER_BYTES;

  private static WalPosition pos(int segment, long offset) {
    return new WalPosition(segment, offset, 100);
  }

  private static Entry data(WalRecordType type, int segment, long offset) {
    return new Entry(type, pos(segment, offset), null, null);
  }

  private static BlockIntent intent(Table table, WalPosition first, WalPosition last) {
    return new BlockIntent(
        table, first, last, new BlockToken("wal-test", 1, first.packed(), last.packed()));
  }

  private static Entry intentEntry(BlockIntent intent, int segment, long offset) {
    return new Entry(WalRecordType.BLOCK_INTENT, pos(segment, offset), intent, null);
  }

  private static Entry commit(BlockIntent intent, int segment, long offset) {
    return new Entry(
        WalRecordType.BLOCK_COMMIT, pos(segment, offset), null, intent.token().value());
  }

  @Test
  @DisplayName(
      "T-SOAK-001a WAL backlog: bytes from the oldest record not loaded in every table it feeds to"
          + " the write position; committed intents only; 0 when everything is loaded")
  void backlog() {
    // Segment 1: TX at H, STAT at H+100, METRIC at H+200, intents and commits after them.
    BlockIntent xlog = intent(Table.XLOG, pos(1, H), pos(1, H));
    BlockIntent sql = intent(Table.SQL_STAT_RAW, pos(1, H + 100), pos(1, H + 100));
    BlockIntent ext = intent(Table.EXT_STAT_RAW, pos(1, H + 100), pos(1, H + 100));
    BlockIntent metric = intent(Table.METRIC, pos(1, H + 100), pos(1, H + 200));
    List<Entry> seg1 = new ArrayList<>();
    seg1.add(data(WalRecordType.TX, 1, H));
    seg1.add(data(WalRecordType.STAT, 1, H + 100));
    seg1.add(data(WalRecordType.METRIC, 1, H + 200));
    seg1.add(intentEntry(xlog, 1, H + 300));
    seg1.add(commit(xlog, 1, H + 400));
    seg1.add(intentEntry(sql, 1, H + 500));
    seg1.add(commit(sql, 1, H + 600));
    seg1.add(intentEntry(ext, 1, H + 700));
    seg1.add(commit(ext, 1, H + 800));
    seg1.add(intentEntry(metric, 1, H + 900));
    long end1 = H + 1000;

    // Everything committed: no backlog.
    List<Entry> withMetricCommit = new ArrayList<>(seg1);
    withMetricCommit.add(commit(metric, 1, H + 1000));
    assertThat(WalBacklog.backlog(List.of(new Segment(1, H + 1100, withMetricCommit)))).isZero();

    // The metric intent has no commit: the STAT record at H+100 (it feeds metric too) is the
    // oldest one not loaded; the backlog runs from it to the end of segment 2.
    List<Entry> seg2 = List.of(data(WalRecordType.TX, 2, H));
    long backlog =
        WalBacklog.backlog(List.of(new Segment(1, end1, seg1), new Segment(2, H + 100, seg2)));
    assertThat(backlog).isEqualTo((end1 - (H + 100)) + 100);

    // A table without any committed intent leaves its records unloaded.
    List<Entry> profile = new ArrayList<>(withMetricCommit);
    profile.add(data(WalRecordType.PROFILE, 1, H + 1100));
    assertThat(WalBacklog.backlog(List.of(new Segment(1, H + 1200, profile)))).isEqualTo(100);
  }

  @Test
  @DisplayName("T-SOAK-001a WAL backlog scan: no segment files is no backlog")
  void emptyDirectory(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("wal-identity.properties"), "x");
    assertThat(WalBacklog.scan(dir)).isZero();
  }
}
