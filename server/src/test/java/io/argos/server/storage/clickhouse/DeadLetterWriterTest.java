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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The writer's dead-letter path and insert pause (WO-28; ADR-0010 C-158 (3), F-S23) with a fake
 * ClickHouse: a block refused three times with a content code goes to the area and its intent is
 * committed; a transient failure is retried without end; a full area discards and still counts; a
 * paused writer takes no block until it resumes.
 */
@Tag("req:D-06")
@Tag("req:F-S23")
class DeadLetterWriterTest {

  @TempDir Path dir;
  @TempDir Path letters;

  private FileWal wal;
  private SimpleMeterRegistry registry;
  private ClickHouseBatchWriter writer;

  @BeforeEach
  void setUp() throws Exception {
    registry = new SimpleMeterRegistry();
    wal = new FileWal(dir, 1 << 20, 1 << 26, 1, 0.8, registry, Clock.systemUTC());
    WalDirectory directory = WalDirectory.open(dir);
    wal.start(directory, WalRecovery.scan(directory, r -> {}).segments());
    writer = new ClickHouseBatchWriter(wal, 60_000, 2, 10, null, registry, Clock.systemUTC());
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

  private double dropped() {
    var c = registry.find("argos.collector.dropped").tag("reason", "dead_letter").counter();
    return c == null ? 0 : c.count();
  }

  private List<Path> files() throws Exception {
    Path d = letters.resolve("dl");
    if (!Files.isDirectory(d)) {
      return List.of();
    }
    try (Stream<Path> s = Files.list(d)) {
      return s.filter(p -> p.toString().endsWith(DeadLetterStore.SUFFIX)).toList();
    }
  }

  @Test
  @DisplayName(
      "T-SV-046e a block refused three times with a parse code goes to the dead-letter area (0600),"
          + " its intent is committed, coverage moves on, rows are counted; other tables load")
  void deterministicRefusalGoesToDeadLetter() throws Exception {
    DeadLetterStore store =
        new DeadLetterStore(letters.resolve("dl"), 1 << 20, registry, Clock.systemUTC());
    writer.setDeadLetter(store);
    Map<Table, AtomicInteger> attempts = new ConcurrentHashMap<>();
    List<Table> inserted = new CopyOnWriteArrayList<>();
    writer.start(
        (table, token, data) -> {
          attempts.computeIfAbsent(table, t -> new AtomicInteger()).incrementAndGet();
          if (table == Table.EXT_STAT_RAW) {
            throw new IllegalStateException(
                "Code: 33. DB::Exception: Cannot read all data. (CANNOT_READ_ALL_DATA)");
          }
          inserted.add(table);
        });
    WalPosition p1 = data(1);
    assertThat(writer.offer(Table.EXT_STAT_RAW, p1, rows(2, "x"), 0, 1000)).isTrue();
    assertThat(writer.offer(Table.XLOG, p1, rows(2, "a"), 0, 1000)).isTrue();
    assertThat(writer.awaitIdle(10_000)).isTrue();

    assertThat(attempts.get(Table.EXT_STAT_RAW).get())
        .isEqualTo(ClickHouseBatchWriter.DEAD_LETTER_ATTEMPTS);
    assertThat(inserted).containsExactly(Table.XLOG);
    assertThat(writer.durableThrough(Table.EXT_STAT_RAW)).isEqualTo(p1.packed());
    BlockToken token = BlockToken.of(wal.identity(), p1, p1);
    List<WalRecord> records = walRecords();
    assertThat(records)
        .filteredOn(r -> r.type() == WalRecordType.BLOCK_INTENT)
        .extracting(r -> BlockIntent.decode(r.payload()).token())
        .contains(token);
    assertThat(records.stream().filter(r -> r.type() == WalRecordType.BLOCK_COMMIT).count())
        .isEqualTo(2);
    assertThat(writer.pendingIntents()).isEmpty();

    List<Path> files = files();
    assertThat(files).hasSize(1);
    Path file = files.get(0);
    assertThat(file.getFileName().toString()).contains("ext_stat_raw").contains("code33");
    assertThat(Files.readAllBytes(file))
        .isEqualTo(
            RowBinaryBuffer.header(Table.EXT_STAT_RAW)
                .concat(ByteString.copyFromUtf8("xx"))
                .toByteArray());
    if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
      assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
          .isEqualTo("rw-------");
      assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())))
          .isEqualTo("rwx------");
    }
    assertThat(dropped()).isEqualTo(2);
    assertThat(store.blocks()).isEqualTo(1);
    assertThat(store.bytes()).isEqualTo(Files.size(file));
    assertThat(registry.find("argos.writer.dead.letter.blocks").gauge().value()).isEqualTo(1);
    store.refresh();
    assertThat(store.blocks()).isEqualTo(1);
  }

  @Test
  @DisplayName(
      "T-SV-046e a transient failure (memory limit, connection) is retried, never dead-lettered")
  void transientFailureIsRetried() throws Exception {
    DeadLetterStore store =
        new DeadLetterStore(letters.resolve("dl"), 1 << 20, registry, Clock.systemUTC());
    writer.setDeadLetter(store);
    AtomicInteger failures = new AtomicInteger(6);
    AtomicInteger attempts = new AtomicInteger();
    writer.start(
        (table, token, data) -> {
          attempts.incrementAndGet();
          int left = failures.getAndDecrement();
          if (left > 3) {
            throw new IllegalStateException(
                "Code: 241. DB::Exception: Memory limit exceeded. (MEMORY_LIMIT_EXCEEDED)");
          }
          if (left > 0) {
            throw new java.net.ConnectException("Connection refused");
          }
        });
    WalPosition p1 = data(1);
    assertThat(writer.offer(Table.XLOG, p1, rows(2, "a"), 0, 1000)).isTrue();
    assertThat(writer.awaitIdle(20_000)).isTrue();
    assertThat(attempts.get()).isEqualTo(7);
    assertThat(files()).isEmpty();
    assertThat(dropped()).isZero();
    assertThat(writer.durableThrough(Table.XLOG)).isEqualTo(p1.packed());
  }

  @Test
  @DisplayName("a writer without a dead-letter area keeps retrying a refused block (as before)")
  void withoutStoreRetriesForever() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    writer.start(
        (table, token, data) -> {
          if (attempts.incrementAndGet() <= 5) {
            throw new IllegalStateException("Code: 53. DB::Exception: Type mismatch");
          }
        });
    WalPosition p1 = data(1);
    assertThat(writer.offer(Table.XLOG, p1, rows(2, "a"), 0, 1000)).isTrue();
    assertThat(writer.awaitIdle(20_000)).isTrue();
    assertThat(attempts.get()).isEqualTo(6);
    assertThat(dropped()).isZero();
  }

  @Test
  @DisplayName(
      "a full dead-letter area discards the block, counts its rows and still commits its intent")
  void fullAreaDiscards() throws Exception {
    DeadLetterStore store =
        new DeadLetterStore(letters.resolve("dl"), 4, registry, Clock.systemUTC());
    writer.setDeadLetter(store);
    writer.start(
        (table, token, data) -> {
          throw new IllegalStateException("Code: 27. DB::Exception: Cannot parse input");
        });
    WalPosition p1 = data(1);
    assertThat(writer.offer(Table.METRIC, p1, rows(2, "m"), 0, 1000)).isTrue();
    assertThat(writer.awaitIdle(10_000)).isTrue();
    assertThat(files()).isEmpty();
    assertThat(dropped()).isEqualTo(2);
    assertThat(writer.durableThrough(Table.METRIC)).isEqualTo(p1.packed());
    assertThat(store.blocks()).isZero();
  }

  @Test
  @DisplayName(
      "T-SV-046d paused inserts take no block (rows stay queued, coverage does not move) until"
          + " resumed")
  void pauseAndResume() throws Exception {
    List<Table> inserted = new CopyOnWriteArrayList<>();
    writer.start((table, token, data) -> inserted.add(table));
    writer.pauseInserts();
    assertThat(writer.insertsPaused()).isTrue();
    WalPosition p1 = data(1);
    assertThat(writer.offer(Table.XLOG, p1, rows(2, "a"), 0, 1000)).isTrue();
    writer.flushNow();
    assertThat(writer.awaitIdle(300)).isFalse();
    assertThat(inserted).isEmpty();
    assertThat(writer.durableThrough(Table.XLOG)).isEqualTo(-1);
    writer.resumeInserts();
    assertThat(writer.awaitIdle(10_000)).isTrue();
    assertThat(inserted).containsExactly(Table.XLOG);
    assertThat(writer.durableThrough(Table.XLOG)).isEqualTo(p1.packed());
  }
}
