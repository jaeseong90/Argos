/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import io.argos.server.storage.api.BlockIntent;
import io.argos.server.storage.api.BlockToken;
import io.argos.server.storage.api.EncodedRows;
import io.argos.server.storage.api.Table;
import io.argos.server.wal.FileWal;
import io.argos.server.wal.WalDirectory;
import io.argos.server.wal.WalFormat;
import io.argos.server.wal.WalRecord;
import io.argos.server.wal.WalRecovery;
import io.argos.server.wal.WalSegmentReader;
import io.argos.server.wal.api.WalPosition;
import io.argos.server.wal.api.WalRecordType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The batch writer's block protocol (12 §3.8, ADR-0007 C-58, C-61) with a fake ClickHouse. */
@Tag("req:D-13")
@Tag("req:C-61")
class ClickHouseBatchWriterTest {

  @TempDir Path dir;

  private FileWal wal;
  private SimpleMeterRegistry registry;
  private ClickHouseBatchWriter writer;

  /** One insert as ClickHouse would see it. */
  record Insert(Table table, BlockToken token, ByteString data) {}

  @BeforeEach
  void setUp() throws Exception {
    registry = new SimpleMeterRegistry();
    wal = new FileWal(dir, 1 << 20, 1 << 26, 1, 0.8, registry, Clock.systemUTC());
    WalDirectory directory = WalDirectory.open(dir);
    wal.start(directory, WalRecovery.scan(directory, r -> {}).segments());
    writer = new ClickHouseBatchWriter(wal, 60_000, 4, 10, null, registry, Clock.systemUTC());
    writer.setBackoffMinMs(1);
  }

  @AfterEach
  void tearDown() {
    writer.close();
    wal.close();
  }

  private WalPosition data(int seq) throws Exception {
    return wal.append(WalRecordType.TX, 1, 1, seq, System.currentTimeMillis(), 0, new byte[] {1});
  }

  private static EncodedRows rows(int n, String marker) {
    return new EncodedRows(n, ByteString.copyFrom(marker.repeat(n), StandardCharsets.UTF_8));
  }

  private List<WalRecord> walRecords() throws Exception {
    List<WalRecord> all = new ArrayList<>();
    WalDirectory d = wal.directory();
    for (int id : d.segmentIds()) {
      try (WalSegmentReader r = WalSegmentReader.open(d.segmentFile(id), id)) {
        r.scan(WalFormat.SEGMENT_HEADER_BYTES, Long.MAX_VALUE, all::add);
      }
    }
    return all;
  }

  @Test
  @DisplayName(
      "T-SV-011a intent fsynced before the insert, the same token on every retry, then commit")
  void intentInsertRetryCommit() throws Exception {
    List<Insert> attempts = new CopyOnWriteArrayList<>();
    AtomicInteger failures = new AtomicInteger(3);
    writer.start(
        (table, token, data) -> {
          // The intent of this token is already in the WAL when ClickHouse sees the insert (C-61).
          boolean intentWritten =
              walRecords().stream()
                  .filter(r -> r.type() == WalRecordType.BLOCK_INTENT)
                  .map(r -> BlockIntent.decode(r.payload()).token())
                  .anyMatch(token::equals);
          assertThat(intentWritten).isTrue();
          attempts.add(new Insert(table, token, data));
          if (failures.getAndDecrement() > 0) {
            throw new IllegalStateException("ClickHouse unavailable");
          }
        });
    WalPosition p1 = data(1);
    WalPosition p2 = data(2);
    assertThat(writer.offer(Table.XLOG, p1, rows(2, "a"), 0, 1000)).isTrue();
    assertThat(writer.offer(Table.XLOG, p2, rows(2, "b"), 0, 1000)).isTrue(); // 4 rows: flush
    assertThat(writer.awaitIdle(10_000)).isTrue();

    assertThat(attempts).hasSize(4);
    assertThat(attempts)
        .extracting(Insert::token)
        .containsOnly(BlockToken.of(wal.identity(), p1, p2));
    ByteString expected =
        RowBinaryBuffer.header(Table.XLOG).concat(ByteString.copyFromUtf8("aabb"));
    assertThat(attempts).extracting(Insert::data).containsOnly(expected);
    List<WalRecord> records = walRecords();
    List<WalRecordType> types = records.stream().map(WalRecord::type).toList();
    assertThat(types)
        .containsExactly(
            WalRecordType.TX,
            WalRecordType.TX,
            WalRecordType.BLOCK_INTENT,
            WalRecordType.BLOCK_COMMIT);
    BlockIntent intent = BlockIntent.decode(records.get(2).payload());
    assertThat(intent.first()).isEqualTo(p1);
    assertThat(intent.last()).isEqualTo(p2);
    assertThat(BlockToken.fromCommitPayload(records.get(3).payload())).isEqualTo(intent.token());
    assertThat(
            registry
                .get("argos.writer.inserts")
                .tag("table", "xlog")
                .tag("result", "error")
                .counter()
                .count())
        .isEqualTo(3);
    assertThat(
            registry
                .get("argos.writer.inserts")
                .tag("table", "xlog")
                .tag("result", "ok")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(registry.get("argos.writer.rows").tag("table", "xlog").counter().count())
        .isEqualTo(4);
    assertThat(writer.durableThrough(Table.XLOG)).isEqualTo(p2.packed());
    assertThat(writer.pendingIntents()).isEmpty();
  }

  @Test
  @DisplayName(
      "T-SV-011b blocks are contiguous in WAL order; markers advance coverage without inserts")
  void contiguousBlocksAndMarkers() throws Exception {
    List<Insert> inserts = new CopyOnWriteArrayList<>();
    writer.start((table, token, data) -> inserts.add(new Insert(table, token, data)));
    List<WalPosition> positions = new ArrayList<>();
    for (int i = 1; i <= 9; i++) {
      positions.add(data(i));
    }
    // Records 1..9: rows in 1, 2, 4, 5, 8; markers (no rows for ext_stat_raw) elsewhere.
    int[] withRows = {0, 1, 3, 4, 7};
    for (int i = 0; i < 9; i++) {
      boolean rows = false;
      for (int w : withRows) {
        rows |= w == i;
      }
      assertThat(
              writer.offer(
                  Table.EXT_STAT_RAW, positions.get(i), rows ? rows(1, "r") : null, 0, 1000))
          .isTrue();
    }
    writer.flushNow();
    assertThat(writer.awaitIdle(10_000)).isTrue();
    assertThat(inserts).isNotEmpty();
    Long previousLast = null;
    int rowsSeen = 0;
    for (Insert insert : inserts) {
      if (previousLast != null) {
        assertThat(Long.compareUnsigned(insert.token().first(), previousLast)).isPositive();
      }
      previousLast = insert.token().last();
      rowsSeen += insert.data().size() - RowBinaryBuffer.header(Table.EXT_STAT_RAW).size();
    }
    assertThat(rowsSeen).isEqualTo(5);
    assertThat(writer.durableThrough(Table.EXT_STAT_RAW)).isEqualTo(positions.get(8).packed());
    // A table that only ever got markers is covered too (GC can free its segments).
    WalPosition later = data(10);
    writer.offer(Table.METRIC, later, null, 0, 1000);
    writer.flushNow();
    assertThat(writer.awaitIdle(10_000)).isTrue();
    assertThat(writer.durableThrough(Table.METRIC)).isEqualTo(later.packed());
  }

  @Test
  @DisplayName(
      "T-SV-011c a full queue makes enqueue return false at once (the rows stay in the WAL)")
  void fullQueue() throws Exception {
    // Not started: nothing drains the queue of 10 rows.
    WalPosition p1 = data(1);
    WalPosition p2 = data(2);
    WalPosition p3 = data(3);
    assertThat(writer.enqueue(Table.PROFILE_STEP, p1, rows(6, "x"))).isTrue();
    assertThat(writer.enqueue(Table.PROFILE_STEP, p2, rows(4, "y"))).isTrue();
    // enqueue waits 0 ms (ClickHouseWriter contract): a full queue answers false at once.
    assertThat(writer.enqueue(Table.PROFILE_STEP, p3, rows(1, "z"))).isFalse();
    assertThat(writer.queuedRows(Table.PROFILE_STEP)).isEqualTo(10);
    assertThat(writer.maxQueueRatio()).isEqualTo(1.0);
    assertThat(registry.get("argos.writer.queue.rows").tag("table", "profile_step").gauge().value())
        .isEqualTo(10.0);
  }

  @Test
  @DisplayName(
      "T-SV-011d recovery re-sends an intent's block with the recorded token and commits it")
  void reinsert() throws Exception {
    List<Insert> inserts = new CopyOnWriteArrayList<>();
    writer.start((table, token, data) -> inserts.add(new Insert(table, token, data)));
    WalPosition p1 = data(1);
    WalPosition p2 = data(2);
    BlockToken token = BlockToken.of(wal.identity(), p1, p2);
    BlockIntent intent = new BlockIntent(Table.SQL_STAT_RAW, p1, p2, token);
    writer.reinsert(intent, List.of(rows(1, "s"), rows(2, "t")));
    assertThat(inserts).hasSize(1);
    assertThat(inserts.get(0).token()).isEqualTo(token);
    assertThat(inserts.get(0).data())
        .isEqualTo(
            RowBinaryBuffer.header(Table.SQL_STAT_RAW).concat(ByteString.copyFromUtf8("stt")));
    assertThat(walRecords().get(walRecords().size() - 1).type())
        .isEqualTo(WalRecordType.BLOCK_COMMIT);
  }

  @Test
  @DisplayName("13 §7.4 the insert statement names every catalogue column and the three settings")
  void statementText() {
    BlockToken token = new BlockToken("abc-1", 3, 0x10L, 0x2aL);
    String target = ClickHouseBlockInserter.statementTarget("argos", "t_", Table.METRIC, token);
    assertThat(target)
        .isEqualTo(
            "argos.t_metric (app_id, env, instance_id, name, kind, ts, value, delta) SETTINGS"
                + " insert_deduplication_token = 'abc-1:3:10-2a',"
                + " deduplicate_blocks_in_dependent_materialized_views = 1, async_insert = 0");
  }
}
