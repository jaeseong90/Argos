/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.BatchHeader;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.MetricKind;
import io.argos.server.ingest.api.RealtimePublisher;
import io.argos.server.session.api.IpMode;
import io.argos.server.session.api.SessionContext;
import io.argos.server.session.api.UserIdMode;
import io.argos.server.storage.api.Table;
import io.argos.server.storage.clickhouse.ClickHouseBatchWriter;
import io.argos.server.wal.FileWal;
import io.argos.server.wal.WalDirectory;
import io.argos.server.wal.WalRecord;
import io.argos.server.wal.WalRecovery;
import io.argos.server.wal.api.WalDataPayload;
import io.argos.server.wal.api.WalFullException;
import io.argos.server.wal.api.WalIdentity;
import io.argos.server.wal.api.WalPosition;
import io.argos.server.wal.api.WalRecordType;
import io.argos.server.wal.api.WalWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The server's active-transaction gauge of ADR-0010 C-178 (1) ({@link ActiveGauge}): the row it
 * becomes in ClickHouse {@code metric}, its WAL record (durable, decoded and inserted like an
 * agent's metric batch, kept out of the deduplication window) and the snapshot call that never
 * waits for the WAL.
 */
@Tag("req:C-178")
@Tag("req:D-13")
@Tag("req:F-U01")
class ActiveGaugeTest {

  private static final long RECEIVED = 1_767_225_000_000L; // on the 5 s grid

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final IngestMetrics metrics = new IngestMetrics(registry);

  private static SessionContext session(long correctionMs) {
    return IngestTestDoubles.session(IpMode.FULL, UserIdMode.PLAIN, RECEIVED, correctionMs, "");
  }

  private static ActiveTransactionSnapshot snapshot(long tsMs, int totalActive, long seq) {
    return ActiveTransactionSnapshot.newBuilder()
        .setHeader(BatchHeader.newBuilder().setInstanceId(3).setAgentEpoch(99).setSeq(seq))
        .setSnapshotTsMs(tsMs)
        .setTotalActive(totalActive)
        .build();
  }

  /** The metric rows the decoder makes of the gauge record of {@code snapshot}. */
  private List<Map<String, Object>> rows(SessionContext s, ActiveTransactionSnapshot snapshot) {
    MetricBatch batch = ActiveGauge.batch(s, snapshot);
    byte[] payload = WalDataPayload.of(s, batch.toByteString()).encode();
    WalRecord record =
        new WalRecord(
            new WalPosition(0, 16, 45 + payload.length),
            WalRecordType.METRIC,
            s.instanceId(),
            s.agentEpoch(),
            batch.getHeader().getSeq(),
            s.receivedAtMs(),
            s.tsCorrectionMs(),
            payload);
    RecordDecoder decoder = IngestTestDoubles.decoder(new IngestTestDoubles.MemoryStore(), metrics);
    var encoded = decoder.decode(record, EnumSet.allOf(Table.class), true).rows().get(Table.METRIC);
    assertThat(encoded).as("metric rows").isNotNull();
    return RowBinaryReader.rows(Table.METRIC, encoded.rowBinary().toByteArray());
  }

  private double errors(String stage) {
    var counter = registry.find("argos.ingest.errors").tag("stage", stage).counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  @DisplayName(
      "C-178 (1) row conversion: one gauge row argos_active_transactions of the session's app, env"
          + " and instance, value total_active, delta 0, at the snapshot time on the 5 s grid")
  void rowConversion() {
    SessionContext s = session(0);
    MetricBatch batch = ActiveGauge.batch(s, snapshot(RECEIVED - 2_300, 17, 41));
    assertThat(batch.getSeriesCount()).isEqualTo(1);
    assertThat(batch.getSeries(0).getName()).isEqualTo("argos_active_transactions");
    assertThat(batch.getSeries(0).getKind()).isEqualTo(MetricKind.GAUGE);
    assertThat(batch.getSamplesCount()).isEqualTo(1);
    assertThat(batch.getSamples(0).getTsMs()).isEqualTo(RECEIVED - 5_000);
    assertThat(batch.getHeader().getInstanceId()).isEqualTo(3);
    assertThat(batch.getHeader().getAgentEpoch()).isEqualTo(99);
    assertThat(batch.getHeader().getSeq()).isEqualTo(ActiveGauge.seq(41));

    List<Map<String, Object>> rows = rows(s, snapshot(RECEIVED - 2_300, 17, 41));
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0))
        .containsEntry("app_id", 7L)
        .containsEntry("env", "prod")
        .containsEntry("instance_id", 3L)
        .containsEntry("name", "argos_active_transactions")
        .containsEntry("kind", 1)
        .containsEntry("ts", (RECEIVED - 5_000) / 1000)
        .containsEntry("value", 17.0)
        .containsEntry("delta", 0.0);
  }

  @Test
  @DisplayName(
      "C-178 (1) the gauge time follows the session correction of C-129 like any metric sample;"
          + " total_active is unsigned; a snapshot without a time takes the receipt time")
  void correctionUnsignedAndMissingTime() {
    // Agent clock 10 s ahead: the WAL record carries the correction, the decoder subtracts it.
    SessionContext ahead = session(10_000);
    assertThat(rows(ahead, snapshot(RECEIVED + 10_000 + 1_200, 2, 1)).get(0))
        .containsEntry("ts", RECEIVED / 1000);
    assertThat(rows(session(0), snapshot(RECEIVED, -1, 2)).get(0))
        .containsEntry("value", 4_294_967_295.0);
    assertThat(rows(session(0), snapshot(0, 5, 3)).get(0)).containsEntry("ts", RECEIVED / 1000);
    assertThat(rows(ahead, snapshot(0, 5, 4)).get(0)).containsEntry("ts", RECEIVED / 1000);
  }

  @Test
  @DisplayName(
      "C-178 (1) the gauge record's seq has the top bit set: agent seqs (1 .. 2^63-1, 11 §6) are"
          + " never server records, so the WAL scan keeps the gauge out of the dedup window")
  void serverSeq() {
    assertThat(ActiveGauge.seq(0)).isEqualTo(Long.MIN_VALUE);
    assertThat(ActiveGauge.seq(41)).isEqualTo(Long.MIN_VALUE | 41);
    assertThat(ActiveGauge.seq(-1)).isEqualTo(-1L);
    for (long agent : new long[] {1, 2, 1_000_000, Long.MAX_VALUE}) {
      assertThat(ActiveGauge.isServerRecord(agent)).as("agent seq %d", agent).isFalse();
      assertThat(ActiveGauge.isServerRecord(ActiveGauge.seq(agent))).isTrue();
    }
  }

  @Test
  @DisplayName(
      "C-178 (1) through a real WAL: onActive writes a durable METRIC record of the session (server"
          + " seq), the dispatcher decodes it and the writer inserts its metric row")
  void throughTheWal(@TempDir Path dir) throws Exception {
    List<byte[]> metricBlocks = new CopyOnWriteArrayList<>();
    FileWal wal = new FileWal(dir, 1 << 20, 1 << 24, 1, 0.8, registry, Clock.systemUTC());
    WalDirectory directory = WalDirectory.open(dir);
    WalRecovery.Scan scan = WalRecovery.scan(directory, r -> {});
    wal.start(directory, scan.segments());
    ClickHouseBatchWriter writer =
        new ClickHouseBatchWriter(wal, 50, 1_000, 10_000, null, registry, Clock.systemUTC());
    writer.start(
        (table, token, data) -> {
          if (table == Table.METRIC) {
            metricBlocks.add(data.toByteArray());
          }
        });
    IngestService ingest =
        new IngestService(
            wal,
            writer,
            IngestTestDoubles.decoder(new IngestTestDoubles.MemoryStore(), metrics),
            metrics,
            RealtimePublisher.noop(),
            Clock.systemUTC());
    try {
      ingest.recover(scan);
      long now = System.currentTimeMillis();
      SessionContext s = IngestTestDoubles.session(IpMode.FULL, UserIdMode.PLAIN, now, 0, "");
      ingest.onActive(s, snapshot(now, 6, 12));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (metricBlocks.isEmpty() && System.nanoTime() < deadline) {
        TimeUnit.MILLISECONDS.sleep(20);
      }
      assertThat(metricBlocks).as("a metric block within 10 s").hasSize(1);
      List<Map<String, Object>> rows =
          RowBinaryReader.rows(Table.METRIC, withoutHeader(metricBlocks.get(0)));
      assertThat(rows).hasSize(1);
      assertThat(rows.get(0))
          .containsEntry("name", "argos_active_transactions")
          .containsEntry("instance_id", 3L)
          .containsEntry("value", 6.0)
          .containsEntry("ts", Math.floorDiv(now, 5_000) * 5);
    } finally {
      ingest.close();
      writer.abort();
      wal.close();
    }
    List<WalRecord> data = new ArrayList<>();
    WalRecovery.scan(WalDirectory.open(dir), data::add);
    assertThat(data).hasSize(1);
    WalRecord record = data.get(0);
    assertThat(record.type()).isEqualTo(WalRecordType.METRIC);
    assertThat(record.instanceId()).isEqualTo(3);
    assertThat(record.epoch()).isEqualTo(99L);
    assertThat(record.seq()).isEqualTo(ActiveGauge.seq(12));
    assertThat(ActiveGauge.isServerRecord(record.seq())).isTrue();
    assertThat(WalDataPayload.decode(record.payload()).appId()).isEqualTo(7);
  }

  @Test
  @DisplayName(
      "C-178 (1) the snapshot call never waits for the WAL: with the WAL stuck, "
          + ActiveGauge.QUEUE
          + " samples wait, the rest are dropped and counted (stage active_gauge); a full WAL drops"
          + " and counts too")
  void neverBlocks() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger appended = new AtomicInteger();
    StubWal stuck =
        new StubWal() {
          @Override
          public WalPosition append(
              WalRecordType type,
              int instanceId,
              long agentEpoch,
              long seq,
              long receivedMs,
              long tsCorrectionMs,
              byte[] payload) {
            try {
              release.await();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            appended.incrementAndGet();
            return new WalPosition(0, 16, 64);
          }
        };
    try (ActiveGauge gauge = new ActiveGauge(stuck, metrics)) {
      SessionContext s = session(0);
      long started = System.nanoTime();
      for (int i = 0; i < ActiveGauge.QUEUE + 5; i++) {
        gauge.record(s, snapshot(RECEIVED, i, i + 1));
      }
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
      assertThat(elapsedMs).as("no call waited for the stuck WAL").isLessThan(1_000);
      // One sample is being appended, QUEUE wait, four are dropped.
      assertThat(errors(ActiveGauge.ERROR_STAGE)).isEqualTo(4.0);
      release.countDown();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (appended.get() < ActiveGauge.QUEUE + 1 && System.nanoTime() < deadline) {
        TimeUnit.MILLISECONDS.sleep(10);
      }
      assertThat(appended.get()).isEqualTo(ActiveGauge.QUEUE + 1);
    }

    StubWal full =
        new StubWal() {
          @Override
          public WalPosition append(
              WalRecordType type,
              int instanceId,
              long agentEpoch,
              long seq,
              long receivedMs,
              long tsCorrectionMs,
              byte[] payload)
              throws WalFullException {
            throw new WalFullException(1, 1);
          }
        };
    try (ActiveGauge gauge = new ActiveGauge(full, metrics)) {
      gauge.append(session(0), ActiveGauge.batch(session(0), snapshot(RECEIVED, 1, 1)));
      assertThat(errors(ActiveGauge.ERROR_STAGE)).isEqualTo(5.0);
    }
  }

  /** The rows of a block after its {@code RowBinaryWithNamesAndTypes} header. */
  private static byte[] withoutHeader(byte[] block) {
    int[] at = {0};
    long columns = varint(block, at);
    for (long i = 0; i < 2 * columns; i++) {
      int length = (int) varint(block, at);
      at[0] += length;
    }
    return java.util.Arrays.copyOfRange(block, at[0], block.length);
  }

  private static long varint(byte[] bytes, int[] at) {
    long value = 0;
    int shift = 0;
    while (true) {
      int b = bytes[at[0]++] & 0xFF;
      value |= (long) (b & 0x7F) << shift;
      if ((b & 0x80) == 0) {
        return value;
      }
      shift += 7;
    }
  }

  /** A WAL writer for the tests above; only {@link #append} matters. */
  private abstract static class StubWal implements WalWriter {
    @Override
    public WalPosition appendBlockIntent(byte[] payload) {
      throw new UnsupportedOperationException();
    }

    @Override
    public WalPosition appendBlockCommit(byte[] payload) {
      throw new UnsupportedOperationException();
    }

    @Override
    public WalIdentity identity() {
      return new WalIdentity("test", 1);
    }

    @Override
    public long bytesInUse() {
      return 0;
    }

    @Override
    public long maxBytes() {
      return 1;
    }
  }
}
