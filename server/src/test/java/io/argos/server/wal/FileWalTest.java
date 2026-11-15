/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.wal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.google.protobuf.ByteString;
import io.argos.proto.ProtocolLimits;
import io.argos.server.config.ArgosProperties;
import io.argos.server.session.api.IpMode;
import io.argos.server.session.api.UserIdMode;
import io.argos.server.wal.api.WalAppendPendingException;
import io.argos.server.wal.api.WalBusyException;
import io.argos.server.wal.api.WalDataPayload;
import io.argos.server.wal.api.WalFullException;
import io.argos.server.wal.api.WalPosition;
import io.argos.server.wal.api.WalRecordType;
import io.argos.server.wal.api.WalWriteException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The WAL writer and reader (12 §3.4, DSN-12-01, DSN-12-02, ADR-0010 C-129, ADR-0007 C-61). */
@Tag("req:D-13")
@Tag("req:DSN-12-01")
@Tag("req:DSN-12-02")
class FileWalTest {

  @TempDir Path dir;

  @Test
  @DisplayName("T-SV-010j a record has the 12 §3.4 layout with ts_correction_ms and a CRC-32C")
  void recordLayout() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    FileWal wal = WalTestSupport.started(dir, 1 << 20, 1 << 24, registry);
    byte[] payload = "batch".getBytes(StandardCharsets.UTF_8);
    WalPosition p =
        wal.append(
            WalRecordType.STAT, 42, 0x0102030405060708L, 9, 1_700_000_000_123L, 20_000, payload);
    wal.close();

    assertThat(p.segmentId()).isZero();
    assertThat(p.offset()).isEqualTo(WalFormat.SEGMENT_HEADER_BYTES);
    assertThat(p.length()).isEqualTo(8 + 37 + payload.length);
    byte[] file = Files.readAllBytes(dir.resolve("seg-00000000.wal"));
    ByteBuffer header = ByteBuffer.wrap(file, 0, 16);
    assertThat(new String(file, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("ARGW");
    header.position(4);
    assertThat(header.getShort()).isEqualTo((short) 1);
    assertThat(header.getShort()).isZero();
    assertThat(header.getLong()).isPositive();

    ByteBuffer rec = ByteBuffer.wrap(file, 16, p.length());
    int len = rec.getInt();
    int crc = rec.getInt();
    assertThat(len).isEqualTo(37 + payload.length);
    CRC32C expected = new CRC32C();
    expected.update(file, 24, len);
    assertThat(crc).isEqualTo((int) expected.getValue());
    assertThat(rec.get()).isEqualTo((byte) 4); // STAT
    assertThat(rec.getInt()).isEqualTo(42);
    assertThat(rec.getLong()).isEqualTo(0x0102030405060708L);
    assertThat(rec.getLong()).isEqualTo(9);
    assertThat(rec.getLong()).isEqualTo(1_700_000_000_123L);
    assertThat(rec.getLong()).isEqualTo(20_000L); // ts_correction_ms (C-129 (3))
    byte[] back = new byte[payload.length];
    rec.get(back);
    assertThat(back).isEqualTo(payload);

    List<WalRecord> records = WalTestSupport.readAll(WalDirectory.open(dir));
    assertThat(records).hasSize(1);
    WalRecord r = records.get(0);
    assertThat(r.position()).isEqualTo(p);
    assertThat(r.tsCorrectionMs()).isEqualTo(20_000);
    assertThat(r.payload()).isEqualTo(payload);
  }

  @Test
  @DisplayName(
      "T-SV-010k a torn tail is cut at the first invalid record and later appends follow it")
  void tornTailIsCut() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    FileWal wal = WalTestSupport.started(dir, 1 << 20, 1 << 24, registry);
    List<WalPosition> positions = new ArrayList<>();
    for (int i = 1; i <= 5; i++) {
      positions.add(WalTestSupport.appendTx(wal, 1, i, "record " + i));
    }
    wal.close();
    Path seg = dir.resolve("seg-00000000.wal");
    long validEnd = Files.size(seg);
    // A partial record (a crash during the write): a length that reaches beyond the file end.
    try (FileChannel ch =
        FileChannel.open(seg, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
      ByteBuffer partial = ByteBuffer.allocate(12);
      partial.putInt(1000).putInt(0).putInt(7).flip();
      ch.write(partial);
    }
    // Corrupt the last complete record too: its CRC no longer matches, so the cut is before it.
    WalPosition last = positions.get(4);
    try (FileChannel ch = FileChannel.open(seg, StandardOpenOption.WRITE)) {
      ch.write(ByteBuffer.wrap(new byte[] {(byte) 0xEE}), last.offset() + 30);
    }

    WalDirectory directory = WalDirectory.open(dir);
    WalRecovery.Scan scan = WalRecovery.scan(directory, r -> {});
    assertThat(scan.dataRecords()).isEqualTo(4);
    assertThat(scan.truncatedSegments()).containsExactly(0);
    assertThat(Files.size(seg)).isEqualTo(last.offset()).isLessThan(validEnd);

    FileWal again =
        new FileWal(
            dir, 1 << 20, 1 << 24, 1, 0.8, new SimpleMeterRegistry(), java.time.Clock.systemUTC());
    again.start(directory, scan.segments());
    WalPosition next = WalTestSupport.appendTx(again, 1, 6, "after restart");
    again.close();
    assertThat(next.segmentId()).isEqualTo(1);
    assertThat(WalTestSupport.readAll(WalDirectory.open(dir)))
        .extracting(WalRecord::seq)
        .containsExactly(1L, 2L, 3L, 4L, 6L);
  }

  @Test
  @DisplayName("T-SV-010l concurrent appends share group fsyncs and each returns after its fsync")
  void groupFsync() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    FileWal wal =
        new FileWal(dir, 1 << 22, 1 << 26, 20, 0.8, registry, java.time.Clock.systemUTC());
    WalDirectory directory = WalDirectory.open(dir);
    wal.start(directory, WalRecovery.scan(directory, r -> {}).segments());
    int threads = 8;
    int perThread = 25;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    List<Future<List<WalPosition>>> futures = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      int instance = t + 1;
      futures.add(
          pool.submit(
              () -> {
                List<WalPosition> mine = new ArrayList<>();
                for (int i = 1; i <= perThread; i++) {
                  WalPosition p = WalTestSupport.appendTx(wal, instance, i, "x".repeat(100));
                  // Durable when append returns (D-13): the durable mark covers it.
                  assertThat(wal.durableMark().covers(p)).isTrue();
                  mine.add(p);
                }
                return mine;
              }));
    }
    List<WalPosition> all = new ArrayList<>();
    for (Future<List<WalPosition>> f : futures) {
      all.addAll(f.get());
    }
    pool.shutdown();
    wal.close();
    long fsyncs = registry.get("argos.wal.fsync").timer().count();
    assertThat(all).hasSize(threads * perThread).doesNotHaveDuplicates();
    assertThat(fsyncs).as("group fsyncs for %d appends", all.size()).isLessThan(all.size() / 2);
    assertThat(WalTestSupport.readAll(WalDirectory.open(dir))).hasSize(threads * perThread);
  }

  @Test
  @DisplayName(
      "T-SV-010o 11 §10 a data append whose fsync outlasts the append wait (or is interrupted)"
          + " throws WalAppendPendingException; its future completes later with the record's"
          + " durable position")
  void slowFsyncIsPending() throws Exception {
    // Scaled: 50 ms instead of the server's 1 s (ADR-0010 C-146 (2)); WalAppendWaitIT checks 1 s.
    FileWal wal = startedWithWait(dir.resolve("timeout"), Duration.ofMillis(50));
    CountDownLatch stuck = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    wal.beforeFsyncForTest(blockUntil(stuck, release));

    Throwable thrown = catchThrowable(() -> WalTestSupport.appendTx(wal, 1, 1, "slow"));
    assertThat(thrown).isInstanceOf(WalAppendPendingException.class);
    assertThat(stuck.await(10, TimeUnit.SECONDS)).as("writer reached the fsync").isTrue();
    CompletableFuture<WalPosition> outcome =
        ((WalAppendPendingException) thrown).pending().toCompletableFuture();
    assertThat(outcome).isNotDone();
    assertThat(wal.durableMark().offset()).isEqualTo(WalFormat.SEGMENT_HEADER_BYTES);

    release.countDown();
    WalPosition position = outcome.get(10, TimeUnit.SECONDS);
    assertThat(wal.durableMark().covers(position))
        .as("%s covers %s", wal.durableMark(), position)
        .isTrue();
    wal.beforeFsyncForTest(null);
    WalPosition next = WalTestSupport.appendTx(wal, 1, 2, "after");
    assertThat(next.offset()).isEqualTo(position.end());
    wal.close();
    assertThat(WalTestSupport.readAll(WalDirectory.open(dir.resolve("timeout"))))
        .extracting(WalRecord::position)
        .containsExactly(position, next);

    // An interrupt while waiting for the fsync: pending as well, with the interrupt flag kept.
    FileWal waiting = startedWithWait(dir.resolve("interrupt"), Duration.ofSeconds(30));
    CountDownLatch stuck2 = new CountDownLatch(1);
    CountDownLatch release2 = new CountDownLatch(1);
    waiting.beforeFsyncForTest(blockUntil(stuck2, release2));
    CompletableFuture<Throwable> caught = new CompletableFuture<>();
    AtomicBoolean interruptKept = new AtomicBoolean();
    Thread appender =
        new Thread(
            () -> {
              caught.complete(catchThrowable(() -> WalTestSupport.appendTx(waiting, 1, 1, "x")));
              interruptKept.set(Thread.currentThread().isInterrupted());
            },
            "wal-test-appender");
    appender.start();
    assertThat(stuck2.await(10, TimeUnit.SECONDS)).as("writer reached the fsync").isTrue();
    appender.interrupt();
    appender.join(10_000);
    assertThat(caught.get(10, TimeUnit.SECONDS)).isInstanceOf(WalAppendPendingException.class);
    assertThat(interruptKept).as("interrupt flag restored").isTrue();
    CompletableFuture<WalPosition> later =
        ((WalAppendPendingException) caught.get()).pending().toCompletableFuture();
    release2.countDown();
    WalPosition durable = later.get(10, TimeUnit.SECONDS);
    assertThat(waiting.durableMark().covers(durable))
        .as("%s covers %s", waiting.durableMark(), durable)
        .isTrue();
    waiting.close();
  }

  private static FileWal startedWithWait(Path walDir, Duration wait) throws Exception {
    FileWal wal =
        new FileWal(
            walDir,
            1 << 20,
            1 << 24,
            1,
            0.8,
            new SimpleMeterRegistry(),
            java.time.Clock.systemUTC(),
            wait);
    WalDirectory directory = WalDirectory.open(walDir);
    wal.start(directory, WalRecovery.scan(directory, r -> {}).segments());
    return wal;
  }

  /** A fsync hook that signals {@code stuck} and blocks until {@code release} (a stuck disk). */
  private static Runnable blockUntil(CountDownLatch stuck, CountDownLatch release) {
    return () -> {
      stuck.countDown();
      try {
        release.await(30, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    };
  }

  @Test
  @Tag("req:SEC-11")
  @DisplayName(
      "T-SV-010s 11 §10 while a group fsync is stuck past the append wait a data append is refused"
          + " at once with WalBusyException and nothing is queued; block records are still queued;"
          + " once the fsync ends every queued record is durable and appends work again")
  void stuckFsyncRefusesDataAtOnce() throws Exception {
    // Scaled: 250 ms instead of the server's 1 s (ADR-0010 C-146 (2)); no wall-clock upper bound.
    Duration wait = Duration.ofMillis(250);
    FileWal wal = startedWithWait(dir, wait);
    CountDownLatch stuck = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    wal.beforeFsyncForTest(blockUntil(stuck, release));
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Throwable first = catchThrowable(() -> WalTestSupport.appendTx(wal, 1, 1, "stuck"));
      assertThat(first).isInstanceOf(WalAppendPendingException.class);
      CompletableFuture<WalPosition> firstOutcome =
          ((WalAppendPendingException) first).pending().toCompletableFuture();
      assertThat(stuck.await(10, TimeUnit.SECONDS)).as("writer reached the fsync").isTrue();
      // The group began before its fsync hook ran, so from here on it is at least this old.
      long stuckAt = System.nanoTime();

      // (iii) a block record is still queued during the stall.
      Future<WalPosition> intent =
          pool.submit(() -> wal.appendBlockIntent("t:1:0-0".getBytes(StandardCharsets.UTF_8)));
      awaitQueueSize(wal, 1);

      // (i) once the group is older than the append wait, a data append is refused at once.
      sleepAtLeast(stuckAt, wait.multipliedBy(2));
      int queueBefore = wal.queueSize();
      long bytesBefore = wal.queuedDataBytes();
      Throwable busy = catchThrowable(() -> WalTestSupport.appendTx(wal, 1, 2, "refused"));
      assertThat(busy)
          .isInstanceOf(WalBusyException.class)
          .hasMessageContaining("append wait")
          .hasNoCause();
      assertThat(wal.queueSize()).as("nothing queued").isEqualTo(queueBefore).isEqualTo(1);
      assertThat(wal.queuedDataBytes()).isEqualTo(bytesBefore).isZero();
      assertThat(firstOutcome).isNotDone();
      assertThat(intent).isNotDone();

      // (iv) the fsync ends: every queued record becomes durable, appends work again.
      release.countDown();
      WalPosition p1 = firstOutcome.get(10, TimeUnit.SECONDS);
      WalPosition pIntent = intent.get(10, TimeUnit.SECONDS);
      wal.beforeFsyncForTest(null);
      WalPosition p3 = WalTestSupport.appendTx(wal, 1, 3, "after");
      DurableMark mark = wal.durableMark();
      assertThat(List.of(p1, pIntent, p3)).allSatisfy(p -> assertThat(mark.covers(p)).isTrue());
      assertThat(wal.queuedDataBytes()).isZero();
      assertThat(wal.queueSize()).isZero();
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
    wal.close();
    List<WalRecord> records = WalTestSupport.readAll(WalDirectory.open(dir));
    assertThat(records)
        .filteredOn(r -> r.type() == WalRecordType.TX)
        .extracting(WalRecord::seq)
        .as("the refused seq 2 was never written")
        .containsExactly(1L, 3L);
    assertThat(records).filteredOn(r -> r.type() == WalRecordType.BLOCK_INTENT).hasSize(1);
  }

  @Test
  @Tag("req:C-157")
  @DisplayName(
      "C-157 (6) argos_wal_queued_bytes reports the data bytes waiting in the writer queue: the"
          + " records queued behind a stuck fsync, then 0 once the writer took them")
  void queuedBytesGauge() throws Exception {
    PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    FileWal wal =
        new FileWal(
            dir,
            1L << 26,
            1L << 28,
            1,
            0.8,
            registry,
            java.time.Clock.systemUTC(),
            Duration.ofSeconds(60));
    WalDirectory directory = WalDirectory.open(dir);
    wal.start(directory, WalRecovery.scan(directory, r -> {}).segments());
    assertThat(registry.get("argos.wal.queued.bytes").gauge().value()).isZero();
    assertThat(scraped(registry)).isZero();
    CountDownLatch stuck = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    wal.beforeFsyncForTest(blockUntil(stuck, release));
    byte[] payload = new byte[100_000];
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      Future<WalPosition> head = pool.submit(() -> appendLarge(wal, 0, payload));
      assertThat(stuck.await(10, TimeUnit.SECONDS)).as("writer reached the fsync").isTrue();
      List<Future<WalPosition>> waiting = new ArrayList<>();
      for (int i = 1; i <= 3; i++) {
        long seq = i;
        waiting.add(pool.submit(() -> appendLarge(wal, seq, payload)));
      }
      awaitQueueSize(wal, 3);
      long queued = wal.queuedDataBytes();
      assertThat(queued).isEqualTo(3L * WalFormat.recordLength(payload.length));
      assertThat(registry.get("argos.wal.queued.bytes").gauge().value()).isEqualTo((double) queued);
      assertThat(scraped(registry)).as("the Prometheus name of 12 §9.4").isEqualTo((double) queued);

      release.countDown();
      head.get(30, TimeUnit.SECONDS);
      for (Future<WalPosition> f : waiting) {
        f.get(30, TimeUnit.SECONDS);
      }
      assertThat(registry.get("argos.wal.queued.bytes").gauge().value()).isZero();
    } finally {
      release.countDown();
      pool.shutdownNow();
      wal.close();
    }
  }

  @Test
  @Tag("req:SEC-11")
  @DisplayName(
      "T-SV-010s 12 §11 data records waiting behind a stuck fsync are bounded: at 64 MiB a data"
          + " append is refused at once with WalBusyException and the waiting bytes stay at the"
          + " limit; block records are still queued; once the fsync ends every queued record is"
          + " durable and the waiting bytes return to 0")
  void queuedDataBytesAreBounded() throws Exception {
    assertThat(FileWal.MAX_QUEUED_DATA_BYTES).isEqualTo(64L * 1024 * 1024);
    // A long append wait: only the byte limit refuses records here, not the age of the stall.
    FileWal wal =
        new FileWal(
            dir,
            1L << 26,
            1L << 28,
            1,
            0.8,
            new SimpleMeterRegistry(),
            java.time.Clock.systemUTC(),
            Duration.ofSeconds(60));
    WalDirectory directory = WalDirectory.open(dir);
    wal.start(directory, WalRecovery.scan(directory, r -> {}).segments());
    CountDownLatch stuck = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    wal.beforeFsyncForTest(blockUntil(stuck, release));
    // Every record is exactly one group long, so the limit holds exactly this many.
    byte[] payload = new byte[(int) (FileWal.GROUP_BYTES - WalFormat.recordLength(0))];
    int fit = (int) (FileWal.MAX_QUEUED_DATA_BYTES / FileWal.GROUP_BYTES);
    ExecutorService pool = Executors.newFixedThreadPool(fit + 2);
    try {
      // The head record is a full group: the writer takes it alone and is stuck in its fsync.
      Future<WalPosition> head = pool.submit(() -> appendLarge(wal, 0, payload));
      assertThat(stuck.await(10, TimeUnit.SECONDS)).as("writer reached the fsync").isTrue();
      assertThat(wal.queuedDataBytes()).as("the writer's group is no longer waiting").isZero();
      List<Future<WalPosition>> waiting = new ArrayList<>();
      for (int i = 1; i <= fit; i++) {
        long seq = i;
        waiting.add(pool.submit(() -> appendLarge(wal, seq, payload)));
      }
      awaitQueueSize(wal, fit);
      assertThat(wal.queuedDataBytes()).isEqualTo(FileWal.MAX_QUEUED_DATA_BYTES);

      // (ii) at the limit a data append is refused at once; the waiting bytes stay at the limit.
      Throwable large = catchThrowable(() -> appendLarge(wal, 99, payload));
      Throwable small = catchThrowable(() -> WalTestSupport.appendTx(wal, 2, 100, "small"));
      assertThat(large)
          .isInstanceOf(WalBusyException.class)
          .hasMessageContaining(Long.toString(FileWal.MAX_QUEUED_DATA_BYTES));
      assertThat(small).isInstanceOf(WalBusyException.class);
      assertThat(wal.queuedDataBytes())
          .isEqualTo(FileWal.MAX_QUEUED_DATA_BYTES)
          .isLessThanOrEqualTo(FileWal.MAX_QUEUED_DATA_BYTES);
      assertThat(wal.queueSize()).as("nothing queued").isEqualTo(fit);

      // (iii) a block record is still queued at the limit.
      Future<WalPosition> commit =
          pool.submit(() -> wal.appendBlockCommit("t:1:0-0".getBytes(StandardCharsets.UTF_8)));
      awaitQueueSize(wal, fit + 1);
      assertThat(wal.queuedDataBytes()).isEqualTo(FileWal.MAX_QUEUED_DATA_BYTES);

      // (iv) the fsync ends: every queued record becomes durable, the waiting bytes return to 0.
      release.countDown();
      List<WalPosition> durable = new ArrayList<>();
      durable.add(head.get(30, TimeUnit.SECONDS));
      for (Future<WalPosition> f : waiting) {
        durable.add(f.get(30, TimeUnit.SECONDS));
      }
      durable.add(commit.get(30, TimeUnit.SECONDS));
      assertThat(wal.queuedDataBytes()).isZero();
      assertThat(wal.queueSize()).isZero();
      wal.beforeFsyncForTest(null);
      durable.add(WalTestSupport.appendTx(wal, 2, 200, "after"));
      DurableMark mark = wal.durableMark();
      assertThat(durable).allSatisfy(p -> assertThat(mark.covers(p)).isTrue());
      assertThat(wal.queuedDataBytes()).isZero();
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
    wal.close();
    List<Long> seqs = new ArrayList<>();
    WalDirectory written = WalDirectory.open(dir);
    for (int id : written.segmentIds()) {
      try (WalSegmentReader reader = WalSegmentReader.open(written.segmentFile(id), id)) {
        reader.scan(
            WalFormat.SEGMENT_HEADER_BYTES,
            Long.MAX_VALUE,
            r -> {
              if (r.type() == WalRecordType.TX) {
                seqs.add(r.seq());
              }
            });
      }
    }
    List<Long> expected = new ArrayList<>();
    for (long s = 0; s <= fit; s++) {
      expected.add(s);
    }
    expected.add(200L);
    assertThat(seqs)
        .as("the refused seqs 99 and 100 were never written")
        .hasSameElementsAs(expected)
        .hasSize(expected.size());
  }

  @Test
  @Tag("req:SEC-11")
  @DisplayName(
      "T-SV-010u a batch of the largest argos.collector.max-message-bytes, with the largest session"
          + " envelope, is one WAL record that recovery can read (body <= 64 MiB) and that an empty"
          + " writer queue admits (<= 64 MiB of waiting data): it is never acked unreadable nor"
          + " refused as busy on every resend")
  void largestMessageFitsOneRecord() {
    String longest = "x".repeat(0xFFFF);
    int envelope =
        new WalDataPayload(
                Integer.MAX_VALUE,
                longest,
                UUID.randomUUID(),
                IpMode.FULL,
                UserIdMode.HASHED,
                Integer.MAX_VALUE,
                longest,
                ByteString.EMPTY)
            .encode()
            .length;
    long largest =
        (long) WalFormat.recordLength(envelope) + ArgosProperties.Collector.MAX_MESSAGE_BYTES_UPPER;
    long body = largest - WalFormat.RECORD_PREFIX_BYTES;
    assertThat(body).as("WAL body read by recovery").isLessThanOrEqualTo(WalFormat.MAX_BODY_BYTES);
    assertThat(largest)
        .as("record admitted into an empty writer queue")
        .isLessThanOrEqualTo(FileWal.MAX_QUEUED_DATA_BYTES);
    assertThat(ArgosProperties.Collector.MAX_MESSAGE_BYTES_UPPER)
        .as("the default fits as well")
        .isGreaterThanOrEqualTo(ProtocolLimits.DEFAULT_MAX_MESSAGE_BYTES);
  }

  /** A TX data record of instance 2 with {@code payload} as is. */
  private static WalPosition appendLarge(FileWal wal, long seq, byte[] payload) throws Exception {
    return wal.append(WalRecordType.TX, 2, 77L, seq, 1_700_000_000_000L + seq, 0, payload);
  }

  /** The value of {@code argos_wal_queued_bytes} in the Prometheus text exposition. */
  private static double scraped(PrometheusMeterRegistry registry) {
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("(?m)^argos_wal_queued_bytes (\\S+)$")
            .matcher(registry.scrape());
    assertThat(m.find()).as("argos_wal_queued_bytes in the scrape").isTrue();
    return Double.parseDouble(m.group(1));
  }

  /** Waits until {@code size} records are in the writer queue (fails after 30 s). */
  private static void awaitQueueSize(FileWal wal, int size) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (wal.queueSize() < size && System.nanoTime() < deadline) {
      Thread.sleep(2);
    }
    assertThat(wal.queueSize()).as("records in the writer queue").isEqualTo(size);
  }

  /** Sleeps until at least {@code duration} has passed since {@code sinceNanos}. */
  private static void sleepAtLeast(long sinceNanos, Duration duration) throws InterruptedException {
    long until = sinceNanos + duration.toNanos();
    long left;
    while ((left = until - System.nanoTime()) > 0) {
      TimeUnit.NANOSECONDS.sleep(left);
    }
  }

  @Test
  @DisplayName("T-SV-010m segments rotate at argos.wal.segment-bytes; a larger record gets its own")
  void rotation() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    long segmentBytes = 4096;
    FileWal wal = WalTestSupport.started(dir, segmentBytes, 1 << 24, registry);
    List<WalPosition> positions = new ArrayList<>();
    for (int i = 1; i <= 40; i++) {
      positions.add(WalTestSupport.appendTx(wal, 1, i, "y".repeat(300)));
    }
    WalPosition big = WalTestSupport.appendTx(wal, 1, 41, "z".repeat(10_000));
    WalPosition after = WalTestSupport.appendTx(wal, 1, 42, "small");
    wal.close();

    assertThat(positions).isSorted();
    assertThat(positions.get(39).segmentId()).isGreaterThan(2);
    for (WalPosition p : positions) {
      assertThat(p.end()).isLessThanOrEqualTo(segmentBytes);
    }
    assertThat(big.offset()).isEqualTo(WalFormat.SEGMENT_HEADER_BYTES);
    assertThat(after.segmentId()).isEqualTo(big.segmentId() + 1);
    assertThat(registry.get("argos.wal.segments").gauge().value())
        .isEqualTo(after.segmentId() + 1.0);
    long onDisk = 0;
    for (int id : WalDirectory.open(dir).segmentIds()) {
      onDisk += Files.size(dir.resolve(WalFormat.segmentName(id)));
    }
    assertThat(registry.get("argos.wal.bytes").gauge().value()).isEqualTo((double) onDisk);
    assertThat(WalTestSupport.readAll(WalDirectory.open(dir))).hasSize(42);
  }

  @Test
  @DisplayName("T-SV-010e above argos.wal.max-bytes data records fail with WalFullException")
  void walFull() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    FileWal wal = WalTestSupport.started(dir, 4096, 8192, registry);
    int written = 0;
    WalFullException full = null;
    for (int i = 1; i <= 100 && full == null; i++) {
      try {
        WalTestSupport.appendTx(wal, 1, i, "w".repeat(500));
        written++;
      } catch (WalFullException e) {
        full = e;
      }
    }
    assertThat(full).isNotNull();
    assertThat(full.maxBytes()).isEqualTo(8192);
    assertThat(written).isBetween(10, 16);
    assertThat(wal.bytesInUse()).isLessThanOrEqualTo(8192);
    // Block records are still accepted (they are needed to free space).
    WalPosition commit = wal.appendBlockCommit("t:1:0-0".getBytes(StandardCharsets.UTF_8));
    assertThat(commit).isNotNull();
    assertThat(registry.get("argos.wal.warnings").counter().count()).isEqualTo(1.0);
    assertThat(wal.usageRatio()).isGreaterThanOrEqualTo(0.8);
    wal.close();
    assertThatThrownBy(() -> WalTestSupport.appendTx(wal, 1, 999, "closed"))
        .isInstanceOf(WalWriteException.class);
  }

  @Test
  @DisplayName("T-SV-010f appends before the start fail without an ack (WalWriteException)")
  void notStarted() {
    FileWal wal =
        new FileWal(
            dir, 4096, 8192, 1, 0.8, new SimpleMeterRegistry(), java.time.Clock.systemUTC());
    assertThatThrownBy(() -> WalTestSupport.appendTx(wal, 1, 1, "x"))
        .isInstanceOf(WalWriteException.class);
    assertThatThrownBy(() -> wal.append(WalRecordType.BLOCK_INTENT, 1, 1, 1, 1, 0, new byte[0]))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName(
      "argos.wal.segment-bytes: 4 GiB is accepted, 4 GiB + 1 is refused at construction (the"
          + " largest record offset a WalPosition holds)")
  void segmentBytesUpperBound() {
    long fourGiB = 4L * 1024 * 1024 * 1024;
    assertThat(FileWal.MAX_SEGMENT_BYTES).isEqualTo(fourGiB);
    assertThat(
            new FileWal(
                dir,
                fourGiB,
                2 * fourGiB,
                1,
                0.8,
                new SimpleMeterRegistry(),
                java.time.Clock.systemUTC()))
        .isNotNull();
    assertThatThrownBy(
            () ->
                new FileWal(
                    dir,
                    fourGiB + 1,
                    2 * fourGiB,
                    1,
                    0.8,
                    new SimpleMeterRegistry(),
                    java.time.Clock.systemUTC()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("argos.wal.segment-bytes (4294967297) is above 4 GiB");
    // The constructor does no I/O: nothing was created.
    assertThat(dir.toFile().list()).isEmpty();
  }

  @Test
  @DisplayName(
      "T-SV-010g the WAL identity survives a restart; an emptied directory gets a new epoch")
  void identityAndEpoch() throws Exception {
    WalDirectory first = WalDirectory.open(dir);
    assertThat(first.identity().epoch()).isEqualTo(1);
    FileWal wal =
        new FileWal(
            dir, 4096, 1 << 20, 1, 0.8, new SimpleMeterRegistry(), java.time.Clock.systemUTC());
    wal.start(first, List.of());
    WalTestSupport.appendTx(wal, 1, 1, "a");
    wal.close();

    WalDirectory second = WalDirectory.open(dir);
    assertThat(second.identity()).isEqualTo(first.identity());

    for (int id : second.segmentIds()) {
      Files.delete(second.segmentFile(id));
    }
    WalDirectory third = WalDirectory.open(dir);
    assertThat(third.identity().walInstanceId()).isEqualTo(first.identity().walInstanceId());
    assertThat(third.identity().epoch()).isEqualTo(2);

    Files.delete(dir.resolve(WalDirectory.IDENTITY_FILE));
    WalDirectory fourth = WalDirectory.open(dir);
    assertThat(fourth.identity().walInstanceId()).isNotEqualTo(first.identity().walInstanceId());
    assertThat(fourth.identity().epoch()).isEqualTo(1);
    if (WalDirectory.isPosix()) {
      assertThat(Files.getPosixFilePermissions(dir))
          .containsExactlyInAnyOrder(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.OWNER_EXECUTE);
    }
  }

  @Test
  @DisplayName("T-SV-010h the tail reader returns durable records in WAL order across segments")
  void tailReader() throws Exception {
    FileWal wal = WalTestSupport.started(dir, 2048, 1 << 22, new SimpleMeterRegistry());
    List<WalPosition> written = new ArrayList<>();
    for (int i = 1; i <= 30; i++) {
      written.add(WalTestSupport.appendTx(wal, 1, i, "r".repeat(150)));
      if (i % 7 == 0) {
        wal.appendBlockCommit(("t:1:" + i + "-" + i).getBytes(StandardCharsets.UTF_8));
      }
    }
    List<WalPosition> read = new ArrayList<>();
    try (WalTailReader reader = new WalTailReader(wal, 0, WalFormat.SEGMENT_HEADER_BYTES)) {
      WalRecord r;
      while ((r = reader.next(50)) != null) {
        if (r.type().isData()) {
          read.add(r.position());
        }
      }
      assertThat(reader.atDurableEnd()).isTrue();
      WalPosition more = WalTestSupport.appendTx(wal, 1, 31, "more");
      WalRecord next = reader.next(1_000);
      assertThat(next).isNotNull();
      assertThat(next.position()).isEqualTo(more);
    }
    wal.close();
    assertThat(read).isEqualTo(written);
  }
}
