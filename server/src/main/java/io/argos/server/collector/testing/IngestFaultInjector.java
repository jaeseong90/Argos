/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.testing;

import io.argos.proto.v1.TransactionBatch;
import io.argos.server.collector.CollectorCalls;
import io.argos.server.collector.batch.Shaped;
import io.argos.server.config.ArgosProperties.Testing;
import io.argos.server.config.TestingHooks;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Failure injection on the collector's data path for the chaos tests (18 §11.1 T-CH-004, 18 §19
 * "argos.testing 실패 주입 5xx·RESOURCE_EXHAUSTED"; WO-52): {@code argos.testing.ingest-fault-file}
 * names a file whose content selects the fault of the data batches ({@code SendTransactions},
 * {@code SendProfiles}, {@code SendMetrics}, {@code SendStats}; never the session stream nor the
 * volatile active snapshots):
 *
 * <ul>
 *   <li>{@code UNAVAILABLE[:ratio]}: the batch is answered {@code UNAVAILABLE} (the agent retries
 *       the same {@code seq});
 *   <li>{@code INTERNAL[:ratio]}: answered {@code INTERNAL} (a server error);
 *   <li>{@code RESOURCE_EXHAUSTED[:ratio]}: answered {@code RESOURCE_EXHAUSTED} with the trailer
 *       {@code argos-retry-after-ms} ({@value #RETRY_AFTER_MS} ms, 11 §10: the agent retries after
 *       that time);
 *   <li>{@code QUOTA[:ratio]}: transaction batches only: the last {@code round(records x ratio)}
 *       records are removed before the instance quota and go the real quota way (counted as {@code
 *       quota} drops in {@code argos_collector_dropped_total}, {@code ACK_PARTIAL_DROP} with {@code
 *       dropped_items}, the instance's server-drop state); other kinds pass.
 * </ul>
 *
 * For the three error modes the ratio (default 1, range (0, 1]) is the probability that a batch is
 * refused; it is refused before deduplication, so nothing is claimed or written. The file is read
 * at most once per second; an absent or empty file, or a content that does not parse, means no
 * fault (a content that does not parse is reported once with a WARN). Each change of the fault is
 * logged once (WARN). The hook exists only in development builds: {@link #from(TestingHooks)} gives
 * {@link #NONE} for a release build ({@link TestingHooks}) and when the key is not set.
 */
public final class IngestFaultInjector {

  /** {@code argos-retry-after-ms} of an injected {@code RESOURCE_EXHAUSTED}. */
  public static final long RETRY_AFTER_MS = 1_000;

  /** The file is read at most this often. */
  static final long READ_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

  /** The largest file content read (anything longer is not a fault). */
  static final int MAX_BYTES = 256;

  private static final Logger LOG = LoggerFactory.getLogger(IngestFaultInjector.class);

  /** No injection (release builds, key not set, protocol tests). */
  public static final IngestFaultInjector NONE = new IngestFaultInjector(null, System::nanoTime);

  /** The modes of the file. */
  public enum Mode {
    UNAVAILABLE,
    INTERNAL,
    RESOURCE_EXHAUSTED,
    QUOTA
  }

  /**
   * One parsed fault.
   *
   * @param mode the mode
   * @param ratio (0, 1]
   */
  public record Fault(Mode mode, double ratio) {

    /** Validates. */
    public Fault {
      Objects.requireNonNull(mode, "mode");
      if (!(ratio > 0 && ratio <= 1)) {
        throw new IllegalArgumentException("ratio must be in (0, 1], got " + ratio);
      }
    }

    /**
     * {@code <MODE>[:ratio]} (case-insensitive, surrounding blanks ignored); blank is null (no
     * fault).
     *
     * @throws IllegalArgumentException when the text is neither blank nor a fault
     */
    public static Fault parse(String text) {
      if (text == null || text.isBlank()) {
        return null;
      }
      String t = text.strip();
      int colon = t.indexOf(':');
      String name = (colon < 0 ? t : t.substring(0, colon)).strip().toUpperCase(Locale.ROOT);
      Mode mode;
      try {
        mode = Mode.valueOf(name);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(
            "unknown mode '" + name + "' (UNAVAILABLE, INTERNAL, RESOURCE_EXHAUSTED, QUOTA)", e);
      }
      double ratio = 1;
      if (colon >= 0) {
        try {
          ratio = Double.parseDouble(t.substring(colon + 1).strip());
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("ratio is not a number: " + t.substring(colon + 1), e);
        }
      }
      return new Fault(mode, ratio);
    }

    @Override
    public String toString() {
      return mode + ":" + ratio;
    }
  }

  private final Path file;
  private final LongSupplier nanoTime;
  private final AtomicLong nextRead;
  private DoubleSupplier random = () -> ThreadLocalRandom.current().nextDouble();
  private volatile Fault current;
  private volatile String lastText = "";
  private volatile String lastReported = "";

  IngestFaultInjector(Path file, LongSupplier nanoTime) {
    this.file = file;
    this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    this.nextRead = new AtomicLong(nanoTime.getAsLong());
  }

  /** The injector of {@code argos.testing.ingest-fault-file}, or {@link #NONE}. */
  public static IngestFaultInjector from(TestingHooks hooks) {
    return hooks
        .active()
        .map(Testing::ingestFaultFile)
        .map(f -> new IngestFaultInjector(Path.of(f), System::nanoTime))
        .orElse(NONE);
  }

  /** An injector of {@code file} with a clock and a random source (unit tests). */
  static IngestFaultInjector of(Path file, LongSupplier nanoTime, DoubleSupplier random) {
    IngestFaultInjector i = new IngestFaultInjector(file, nanoTime);
    i.random = random;
    return i;
  }

  /** Whether a file is watched at all. */
  public boolean enabled() {
    return file != null;
  }

  /** The fault now (the file read at most once per second), or null. */
  public Fault current() {
    if (file == null) {
      return null;
    }
    long now = nanoTime.getAsLong();
    long next = nextRead.get();
    if (now - next >= 0 && nextRead.compareAndSet(next, now + READ_INTERVAL_NANOS)) {
      refresh();
    }
    return current;
  }

  private void refresh() {
    String text;
    try {
      byte[] bytes = Files.readAllBytes(file);
      text =
          bytes.length > MAX_BYTES
              ? "<" + bytes.length + " bytes>"
              : new String(bytes, StandardCharsets.UTF_8).strip();
    } catch (NoSuchFileException e) {
      text = "";
    } catch (IOException e) {
      text = "";
    }
    if (text.equals(lastText)) {
      return;
    }
    lastText = text;
    Fault parsed;
    try {
      parsed = Fault.parse(text);
    } catch (IllegalArgumentException e) {
      parsed = null;
      if (!text.equals(lastReported)) {
        lastReported = text;
        LOG.warn(
            "argos.testing.ingest-fault-file: content not understood, no fault injected: {}",
            e.getMessage());
      }
    }
    Fault before = current;
    current = parsed;
    if (!Objects.equals(before, parsed)) {
      if (parsed == null) {
        LOG.warn("argos.testing.ingest-fault-file: fault injection off");
      } else {
        LOG.warn(
            "argos.testing.ingest-fault-file: injecting {} into {}% of the data batches (test hook,"
                + " development build)",
            parsed.mode(), Math.round(parsed.ratio() * 100));
      }
    }
  }

  /**
   * The error to answer a data batch with now ({@code UNAVAILABLE}, {@code INTERNAL}, {@code
   * RESOURCE_EXHAUSTED} with {@code argos-retry-after-ms}), or null when the batch goes on.
   */
  public StatusRuntimeException reject() {
    Fault f = current();
    if (f == null || f.mode() == Mode.QUOTA || !hit(f.ratio())) {
      return null;
    }
    return switch (f.mode()) {
      case UNAVAILABLE ->
          Status.UNAVAILABLE
              .withDescription("Injected fault (argos.testing.ingest-fault-file); retry")
              .asRuntimeException();
      case INTERNAL ->
          Status.INTERNAL
              .withDescription("Injected fault (argos.testing.ingest-fault-file)")
              .asRuntimeException();
      case RESOURCE_EXHAUSTED ->
          CollectorCalls.error(
              Status.RESOURCE_EXHAUSTED.withDescription(
                  "Injected fault (argos.testing.ingest-fault-file); retry later"),
              null,
              RETRY_AFTER_MS);
      case QUOTA -> null;
    };
  }

  /**
   * The instance quota {@code quota} of the batch, after removing the injected quota drop ({@code
   * QUOTA}: transaction batches only) from its end: the removed records never take quota tokens,
   * and both counts add up in the result's dropped items, so that the caller answers {@code
   * ACK_PARTIAL_DROP} and counts them as {@code quota} drops exactly like the real quota.
   */
  public <B> Shaped<B> quota(BiFunction<Integer, B, Shaped<B>> quota, int instanceId, B batch) {
    Fault f = current();
    if (f == null || f.mode() != Mode.QUOTA || !(batch instanceof TransactionBatch tx)) {
      return quota.apply(instanceId, batch);
    }
    int count = tx.getRecordsCount();
    int drop = dropCount(count, f.ratio());
    if (drop == 0) {
      return quota.apply(instanceId, batch);
    }
    TransactionBatch kept =
        tx.toBuilder()
            .clearRecords()
            .addAllRecords(tx.getRecordsList().subList(0, count - drop))
            .build();
    @SuppressWarnings("unchecked")
    B keptBatch = (B) kept;
    Shaped<B> real = quota.apply(instanceId, keptBatch);
    return new Shaped<>(real.batch(), real.droppedItems() + drop);
  }

  /** {@code round(count x ratio)} within [0, count]. */
  static int dropCount(int count, double ratio) {
    return (int) Math.max(0, Math.min(count, Math.round(count * ratio)));
  }

  private boolean hit(double ratio) {
    return ratio >= 1 || random.getAsDouble() < ratio;
  }
}
