/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.proto.v1.ActiveTransactionSnapshot;
import io.argos.proto.v1.DictionaryKind;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.MetricKind;
import io.argos.proto.v1.MetricSample;
import io.argos.proto.v1.MetricSeries;
import io.argos.proto.v1.TransactionBatch;
import io.argos.proto.v1.TransactionKind;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.ingest.api.MetricPoint;
import io.argos.server.ingest.api.RealtimePublisher;
import io.argos.server.ingest.api.XlogPoint;
import io.argos.server.session.api.IpMode;
import io.argos.server.session.api.SessionContext;
import io.argos.server.session.api.UserIdMode;
import io.argos.server.storage.api.Table;
import io.argos.server.storage.clickhouse.ClickHouseBatchWriter;
import io.argos.server.wal.FileWal;
import io.argos.server.wal.WalDirectory;
import io.argos.server.wal.WalRecovery;
import io.argos.server.wal.api.WalDataPayload;
import io.argos.server.wal.api.WalPosition;
import io.argos.server.wal.api.WalRecordType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.Status;

/**
 * Failures of the ingest pipeline after the WAL are visible (12 §3.9): a record whose decode throws
 * is counted and skipped without stopping the load; a dispatcher that stops reports DOWN through
 * {@link IngestHealthIndicator} and is counted. A real WAL in a temporary directory, the real
 * writer with an in-memory inserter.
 */
@Tag("req:D-13")
class IngestServiceTest {

  private static final long RECEIVED = 1_767_225_000_000L;

  @TempDir Path dir;

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final IngestMetrics metrics = new IngestMetrics(registry);
  private final List<Table> inserts = new CopyOnWriteArrayList<>();
  private FileWal wal;
  private WalDirectory directory;
  private WalRecovery.Scan scan;
  private ClickHouseBatchWriter writer;
  private IngestService ingest;

  private void start(RecordDecoder decoder) throws IOException {
    start(decoder, RealtimePublisher.noop());
  }

  private void start(RecordDecoder decoder, RealtimePublisher publisher) throws IOException {
    wal = new FileWal(dir, 1 << 20, 1 << 24, 1, 0.8, registry, Clock.systemUTC());
    directory = WalDirectory.open(dir);
    scan = WalRecovery.scan(directory, r -> {});
    wal.start(directory, scan.segments());
    writer = new ClickHouseBatchWriter(wal, 50, 1_000, 10_000, null, registry, Clock.systemUTC());
    writer.start((table, token, data) -> inserts.add(table));
    ingest = new IngestService(wal, writer, decoder, metrics, publisher, Clock.systemUTC());
  }

  @AfterEach
  void stop() {
    if (ingest != null) {
      ingest.close();
    }
    if (writer != null) {
      writer.abort();
    }
    if (wal != null) {
      wal.close();
    }
  }

  private WalPosition appendTx(String appVersion, long seq) throws Exception {
    SessionContext s =
        IngestTestDoubles.session(IpMode.FULL, UserIdMode.PLAIN, RECEIVED, 0, appVersion);
    TransactionBatch batch =
        TransactionBatch.newBuilder()
            .addRecords(
                TransactionRecord.newBuilder()
                    .setTxId(seq)
                    .setKind(TransactionKind.HTTP)
                    .setStartTsMs(RECEIVED - 100))
            .build();
    return wal.append(
        WalRecordType.TX,
        s.instanceId(),
        s.agentEpoch(),
        seq,
        s.receivedAtMs(),
        s.tsCorrectionMs(),
        WalDataPayload.of(s, batch.toByteString()).encode());
  }

  private double errors(String stage) {
    return registry.get("argos.ingest.errors").tag("stage", stage).counter().count();
  }

  @Test
  @DisplayName(
      "12 §3.9 an unreadable durable WAL record stops the dispatcher: the start fails instead of"
          + " waiting, health ingest DOWN, argos_ingest_errors_total{stage=dispatch} counted")
  void dispatcherStopIsVisible() throws Exception {
    start(IngestTestDoubles.decoder(new IngestTestDoubles.MemoryStore(), metrics));
    IngestHealthIndicator health = new IngestHealthIndicator(ingest);
    WalPosition position = appendTx("", 1);
    assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    // The durable record is damaged on disk before the dispatcher reads it: its CRC-32C no longer
    // matches (the dispatcher starts with the recovery).
    try (FileChannel channel =
        FileChannel.open(
            directory.segmentFile(position.segmentId()),
            StandardOpenOption.READ,
            StandardOpenOption.WRITE)) {
      ByteBuffer one = ByteBuffer.allocate(1);
      long at = position.end() - 1;
      assertThat(channel.read(one, at)).isEqualTo(1);
      one.flip();
      byte flipped = (byte) ~one.get(0);
      channel.write(ByteBuffer.wrap(new byte[] {flipped}), at);
      channel.force(true);
    }
    assertThatThrownBy(() -> ingest.recover(scan))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("dispatcher stopped");
    assertThat(ingest.isDispatcherStopped()).isTrue();
    assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.health().getDetails()).isEmpty();
    assertThat(errors("dispatch")).isEqualTo(1.0);
    assertThat(inserts).isEmpty();
  }

  @Test
  @DisplayName(
      "12 §3.9 a record whose decode throws outside its items is skipped and counted"
          + " (stage=decode); the next record is loaded and the dispatcher keeps running")
  void recordDecodeFailureIsCounted() throws Exception {
    IngestTestDoubles.MemoryStore store = new IngestTestDoubles.MemoryStore();
    DictionaryResolver failing =
        new DictionaryResolver(
            store,
            IngestTestDoubles.provider(new IngestTestDoubles.RecordingMessenger()),
            metrics,
            10_000,
            1_000_000,
            Clock.systemUTC()) {
          @Override
          public void ensureServerEntry(int appId, DictionaryKind kind, long id, String text) {
            if (text.equals("broken")) {
              throw new IllegalStateException("record-level failure of the test");
            }
            super.ensureServerEntry(appId, kind, id, text);
          }
        };
    start(new RecordDecoder(failing, new ErrorGroupRecorder(store, metrics), metrics));
    IngestHealthIndicator health = new IngestHealthIndicator(ingest);
    appendTx("broken", 1); // the session's version is looked up once per record, before its items
    appendTx("", 2);
    ingest.recover(scan);
    assertThat(ingest.isProcessedThrough(wal.durableMark())).isTrue();
    assertThat(errors("decode")).isEqualTo(1.0);
    assertThat(inserts).containsExactly(Table.XLOG); // one block: the rows of the second record
    assertThat(writer.durableThrough(Table.XLOG)).isNotNegative();
    assertThat(ingest.isDispatcherStopped()).isFalse();
    assertThat(health.health().getStatus()).isEqualTo(Status.UP);
  }

  private WalPosition appendHeap(long receivedMs, long tsMs, double value, long seq)
      throws Exception {
    SessionContext s = IngestTestDoubles.session(IpMode.FULL, UserIdMode.PLAIN, receivedMs, 0, "");
    MetricBatch batch =
        MetricBatch.newBuilder()
            .addSeries(
                MetricSeries.newBuilder()
                    .setIndex(0)
                    .setName("jvm_memory_used_bytes{area=\"heap\"}")
                    .setKind(MetricKind.GAUGE))
            .addSamples(MetricSample.newBuilder().setSeriesIndex(0).setTsMs(tsMs).setValue(value))
            .build();
    return wal.append(
        WalRecordType.METRIC,
        s.instanceId(),
        s.agentEpoch(),
        seq,
        s.receivedAtMs(),
        s.tsCorrectionMs(),
        WalDataPayload.of(s, batch.toByteString()).encode());
  }

  @Test
  @Tag("req:D-15")
  @DisplayName(
      "T-SV-020f heap, GC and CPU samples reach the realtime metric port only from the live path:"
          + " never from WAL recovery, never for a late row")
  void metricsPublishedLiveOnly() throws Exception {
    List<List<MetricPoint>> published = new CopyOnWriteArrayList<>();
    RealtimePublisher recording =
        new RealtimePublisher() {
          @Override
          public void publishXlog(SessionContext session, List<XlogPoint> points) {}

          @Override
          public void publishActive(SessionContext session, ActiveTransactionSnapshot snapshot) {}

          @Override
          public void publishMetrics(SessionContext session, List<MetricPoint> points) {
            published.add(points);
          }
        };
    start(IngestTestDoubles.decoder(new IngestTestDoubles.MemoryStore(), metrics), recording);
    long now = System.currentTimeMillis();
    long grid = Math.floorDiv(now, 5_000L) * 5_000L;
    appendHeap(now, grid, 1.0, 1); // read by the recovery: never published
    ingest.recover(scan);
    assertThat(published).isEmpty();

    now = System.currentTimeMillis();
    grid = Math.floorDiv(now, 5_000L) * 5_000L;
    appendHeap(now, now - 90_000, 3.0, 2); // late = 1
    appendHeap(now, grid, 2.0, 3);
    // Records are handed to the port in WAL order after they are marked processed: wait for the
    // last one's publish.
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (published.isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertThat(ingest.isProcessedThrough(wal.durableMark())).as("live records loaded").isTrue();
    assertThat(published)
        .containsExactly(List.of(new MetricPoint(MetricPoint.Series.HEAP_USED, grid, 2.0)));
  }
}
