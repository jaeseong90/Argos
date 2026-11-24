/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import io.argos.server.config.ArgosProperties;
import io.argos.server.ops.migration.ClickHouseMigrationPhase;
import io.argos.server.ops.migration.MigrationSource;
import io.argos.server.ops.migration.PostgresMigrationPhase;
import io.argos.server.storage.api.EncodedRows;
import io.argos.server.storage.api.Table;
import io.argos.server.storage.clickhouse.BlockInserter;
import io.argos.server.storage.clickhouse.ClickHouseBatchWriter;
import io.argos.server.storage.clickhouse.ClickHouseBlockInserter;
import io.argos.server.storage.clickhouse.DeadLetterStore;
import io.argos.server.storage.clickhouse.RowBinaryBuffer;
import io.argos.server.storage.query.ClickHouseErrors;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.wal.FileWal;
import io.argos.server.wal.WalDirectory;
import io.argos.server.wal.WalFormat;
import io.argos.server.wal.WalRecord;
import io.argos.server.wal.WalRecovery;
import io.argos.server.wal.WalSegmentReader;
import io.argos.server.wal.api.WalPosition;
import io.argos.server.wal.api.WalRecordType;
import io.argos.storage.clickhouse.Account;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * The writer's dead-letter area against real ClickHouse (T-SV-046e; ADR-0010 C-158 (3), WO-28): a
 * block whose bytes ClickHouse cannot parse is refused three times with a deterministic code, goes
 * to the area as a 0600 file, its rows count into {@code argos_collector_dropped_total{reason=
 * "dead_letter"}}, its intent is committed and the WAL coverage moves on; the next block of the
 * same table and the blocks of another table load. A transient failure (memory limit) of a valid
 * block is retried and loads, it is never set aside.
 */
@Tag("integration")
@Tag("req:D-06")
@Tag("req:F-S23")
class DeadLetterIT {

  private static final int APP = 970;

  @TempDir static Path tmp;

  private static ServerTestEnvironment env;
  private static ArgosProperties properties;

  @BeforeAll
  static void start() throws Exception {
    env = ServerTestEnvironment.start();
    Map<String, Object> props = new HashMap<>(env.springProperties());
    properties =
        new Binder(new MapConfigurationPropertySource(props))
            .bind("argos", ArgosProperties.class)
            .get();
    new PostgresMigrationPhase(properties).run();
    new ClickHouseMigrationPhase(
            properties, MigrationSource.fromClasspath(DeadLetterIT.class.getClassLoader()))
        .run();
  }

  @AfterAll
  static void stop() {
    if (env != null) {
      env.close();
    }
  }

  private static long scalar(String sql) {
    String v = env.clickHouse(Account.READER).scalar(sql).strip();
    return v.isEmpty() ? 0 : Long.parseLong(v);
  }

  /** One valid metric row (13 §3.3 columns in the order of {@link Table#METRIC}). */
  private static EncodedRows metricRow(String name, long epochSecond) {
    RowBinaryBuffer b = new RowBinaryBuffer(64);
    b.i32(APP);
    b.string("prod");
    b.i32(1);
    b.string(name);
    b.u8(1); // gauge
    b.i32((int) epochSecond);
    b.f64(42.0);
    b.f64(0.0);
    return new EncodedRows(1, b.toByteString());
  }

  /** One valid ext_stat_raw row ({@link Table#EXT_STAT_RAW} column order). */
  private static EncodedRows extRow(long hostId, long epochSecond) {
    RowBinaryBuffer b = new RowBinaryBuffer(96);
    b.i32(APP);
    b.string("prod");
    b.i32(1);
    b.i32((int) epochSecond);
    b.u8(1); // HTTP
    b.i64(hostId);
    b.i32(3); // count
    b.i32(0); // error_count
    b.i32(0); // timeout_count
    b.i32(0); // connect_fail_count
    b.i64(30); // sum_ms
    b.i32(12); // max_ms
    b.varint(0); // buckets: empty array
    return new EncodedRows(1, b.toByteString());
  }

  private static List<WalRecord> walRecords(FileWal wal) throws Exception {
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
      "T-SV-046e a block ClickHouse cannot parse goes to the dead-letter area after three refusals"
          + " (file 0600, dropped counter, WAL coverage moves on), later blocks and other tables"
          + " load; a transient failure is retried, never dead-lettered")
  void deadLetter() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    Path walDir = Files.createDirectories(tmp.resolve("wal"));
    Path letters = tmp.resolve("dead-letter");
    FileWal wal = new FileWal(walDir, 1 << 20, 1 << 26, 1, 0.8, registry, Clock.systemUTC());
    WalDirectory directory = WalDirectory.open(walDir);
    wal.start(directory, WalRecovery.scan(directory, r -> {}).segments());
    DeadLetterStore store = new DeadLetterStore(letters, 1L << 20, registry, Clock.systemUTC());
    ClickHouseBatchWriter writer =
        new ClickHouseBatchWriter(
            wal, 100, 1, 1_000, properties.clickhouse(), registry, Clock.systemUTC());
    writer.setBackoffMinMs(50);
    writer.setDeadLetter(store);
    ClickHouseBlockInserter real = ClickHouseBlockInserter.open(properties.clickhouse());
    AtomicInteger extAttempts = new AtomicInteger();
    AtomicInteger transientLeft = new AtomicInteger(4);
    List<String> refusals = new ArrayList<>();
    BlockInserter inserter =
        (table, token, data) -> {
          if (table == Table.EXT_STAT_RAW) {
            extAttempts.incrementAndGet();
          }
          if (table == Table.METRIC && transientLeft.getAndDecrement() > 0) {
            // A transient refusal of a valid block (ClickHouse under memory pressure).
            throw new IllegalStateException(
                "Code: 241. DB::Exception: Memory limit (total) exceeded. (MEMORY_LIMIT_EXCEEDED)");
          }
          try {
            real.insert(table, token, data);
          } catch (Exception e) {
            synchronized (refusals) {
              refusals.add(table.tableName() + ":" + ClickHouseErrors.deterministicInsertCode(e));
            }
            throw e;
          }
        };
    long now = Instant.now().getEpochSecond();
    try {
      writer.start(inserter);
      WalPosition p1 =
          wal.append(WalRecordType.STAT, APP, 1, 1, System.currentTimeMillis(), 0, new byte[] {1});
      // Bytes that are not a row of ext_stat_raw: two rows announced, three bytes sent.
      EncodedRows broken =
          new EncodedRows(2, ByteString.copyFrom(new byte[] {(byte) 0xC9, 0x03, 0x00}));
      assertThat(writer.offer(Table.EXT_STAT_RAW, p1, broken, 0, 5_000)).isTrue();
      assertThat(writer.offer(Table.METRIC, p1, metricRow("dl.metric", now), 0, 5_000)).isTrue();
      assertThat(writer.awaitIdle(60_000)).isTrue();
      WalPosition p2 =
          wal.append(WalRecordType.STAT, APP, 1, 2, System.currentTimeMillis(), 0, new byte[] {1});
      assertThat(writer.offer(Table.EXT_STAT_RAW, p2, extRow(77, now), 0, 5_000)).isTrue();
      assertThat(writer.awaitIdle(60_000)).isTrue();

      // Three refusals of the broken block with a deterministic code, then the next block.
      assertThat(extAttempts.get()).isEqualTo(ClickHouseBatchWriter.DEAD_LETTER_ATTEMPTS + 1);
      assertThat(refusals).hasSize(ClickHouseBatchWriter.DEAD_LETTER_ATTEMPTS);
      assertThat(refusals).allMatch(r -> r.startsWith("ext_stat_raw:") && !r.endsWith(":0"));
      System.out.println("[wo28-dead-letter] refusals " + refusals);

      List<Path> files;
      try (Stream<Path> s = Files.list(letters)) {
        files = s.filter(f -> f.toString().endsWith(DeadLetterStore.SUFFIX)).toList();
      }
      assertThat(files).hasSize(1);
      Path file = files.get(0);
      Matcher m =
          Pattern.compile("-ext_stat_raw-code(\\d+)-").matcher(file.getFileName().toString());
      assertThat(m.find()).as(file.getFileName().toString()).isTrue();
      assertThat(ClickHouseErrors.DETERMINISTIC_INSERT_CODES)
          .contains(Integer.parseInt(m.group(1)));
      assertThat(Files.readAllBytes(file))
          .isEqualTo(
              RowBinaryBuffer.header(Table.EXT_STAT_RAW).concat(broken.rowBinary()).toByteArray());
      if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
            .isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(letters)))
            .isEqualTo("rwx------");
      }
      assertThat(
              registry
                  .find("argos.collector.dropped")
                  .tag("reason", "dead_letter")
                  .counter()
                  .count())
          .isEqualTo(2);
      assertThat(registry.find("argos.writer.dead.letter.blocks").gauge().value()).isEqualTo(1);
      assertThat(registry.find("argos.writer.dead.letter.bytes").gauge().value())
          .isEqualTo((double) Files.size(file));

      // The WAL coverage moved past both blocks; every intent has its commit.
      assertThat(writer.durableThrough(Table.EXT_STAT_RAW)).isEqualTo(p2.packed());
      assertThat(writer.pendingIntents()).isEmpty();
      List<WalRecord> records = walRecords(wal);
      long intents = records.stream().filter(r -> r.type() == WalRecordType.BLOCK_INTENT).count();
      long commits = records.stream().filter(r -> r.type() == WalRecordType.BLOCK_COMMIT).count();
      assertThat(intents).isEqualTo(3);
      assertThat(commits).isEqualTo(3);

      // The valid blocks are in ClickHouse: the transiently refused metric and the next ext block.
      assertThat(transientLeft.get()).isNegative();
      assertThat(scalar("SELECT count() FROM argos.metric WHERE app_id = " + APP)).isEqualTo(1);
      assertThat(scalar("SELECT count() FROM argos.ext_stat_raw WHERE app_id = " + APP))
          .isEqualTo(1);
      assertThat(scalar("SELECT sum(count) FROM argos.ext_stat_raw WHERE app_id = " + APP))
          .isEqualTo(3);
    } finally {
      writer.close();
      real.close();
      wal.close();
    }
  }
}
