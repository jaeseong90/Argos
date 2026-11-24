/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.clickhouse;

import com.google.protobuf.ByteString;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.api.BlockToken;
import io.argos.server.storage.api.Table;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The writer's dead-letter area (ADR-0010 C-158 (3); WO-28): a block that ClickHouse refused with a
 * deterministic code three times ({@code
 * storage.query.ClickHouseErrors#DETERMINISTIC_INSERT_CODES}) is written to {@code
 * argos.writer.dead-letter.dir} as one file of {@code RowBinaryWithNamesAndTypes} bytes (the exact
 * insert body: an operator can load it again with {@code INSERT INTO argos.<table> FORMAT
 * RowBinaryWithNamesAndTypes} after fixing the cause).
 *
 * <ul>
 *   <li>The directory is created 0700 and every file 0600 on POSIX file systems (written to a
 *       temporary name and moved into place); on other file systems the permissions are those of
 *       the parent directory (documented for Windows).
 *   <li>The area is bounded by {@code argos.writer.dead-letter.max-bytes} (1 GiB): a block that
 *       does not fit, or cannot be written, is discarded. Either way its rows count into {@code
 *       argos_collector_dropped_total{reason="dead_letter"}}, so the server drop alert template and
 *       the collection health server drops see them (PM question 6 assumption).
 *   <li>Nothing is deleted automatically; {@link #refresh()} recounts the files (an operator may
 *       remove them) for the gauges {@code argos_writer_dead_letter_blocks} and {@code
 *       argos_writer_dead_letter_bytes}.
 *   <li>A file name holds the time, the table, the ClickHouse code and the block's WAL range (from
 *       its token); nothing of the content is logged.
 * </ul>
 *
 * The block files hold collected data (personal data possible): 02 data classification request.
 */
@Component
public final class DeadLetterStore {

  /** File suffix of a stored block. */
  public static final String SUFFIX = ".rowbinary";

  private static final Logger LOG = LoggerFactory.getLogger(DeadLetterStore.class);

  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);

  private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9-]");

  private static final Set<PosixFilePermission> DIR_MODE =
      PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> FILE_MODE =
      PosixFilePermissions.fromString("rw-------");

  /** What happened to a block. */
  public enum Outcome {
    /** Written to the area. */
    STORED,
    /** Not written: the area is full or the file could not be written. */
    DISCARDED
  }

  private final Path dir;
  private final long maxBytes;
  private final Clock clock;
  private final Counter droppedRows;
  private final AtomicLong blocks = new AtomicLong();
  private final AtomicLong bytes = new AtomicLong();
  private final Object lock = new Object();

  /** The bean of the server: {@code argos.writer.dead-letter.*}. */
  @Autowired
  public DeadLetterStore(ArgosProperties properties, MeterRegistry registry) {
    this(
        Path.of(properties.writer().deadLetter().dir()),
        properties.writer().deadLetter().maxBytes().toBytes(),
        registry,
        Clock.systemUTC());
  }

  /**
   * A store with explicit settings (tests).
   *
   * @param dir the directory (created on the first block)
   * @param maxBytes upper bound of all files
   */
  public DeadLetterStore(Path dir, long maxBytes, MeterRegistry registry, Clock clock) {
    this.dir = Objects.requireNonNull(dir, "dir").toAbsolutePath().normalize();
    this.maxBytes = maxBytes;
    this.clock = clock;
    this.droppedRows =
        Counter.builder("argos.collector.dropped").tag("reason", "dead_letter").register(registry);
    Gauge.builder("argos.writer.dead.letter.blocks", blocks, AtomicLong::get)
        .description("Blocks in the writer's dead-letter area (ADR-0010 C-158 (3))")
        .register(registry);
    Gauge.builder("argos.writer.dead.letter.bytes", bytes, AtomicLong::get)
        .description("Bytes of the blocks in the writer's dead-letter area")
        .register(registry);
    refresh();
  }

  /** The directory of the block files. */
  public Path directory() {
    return dir;
  }

  /** Blocks in the area (as of the last write or {@link #refresh()}). */
  public long blocks() {
    return blocks.get();
  }

  /** Bytes in the area (as of the last write or {@link #refresh()}). */
  public long bytes() {
    return bytes.get();
  }

  /**
   * Stores one refused block, or discards it when it does not fit; its rows are counted as dropped
   * either way.
   *
   * @param table the table it was meant for
   * @param token its C-61 token (WAL range)
   * @param data the insert body ({@code RowBinaryWithNamesAndTypes} header and rows)
   * @param rows rows of the block
   * @param code the ClickHouse code of the refusals
   * @return what happened; never throws
   */
  public Outcome store(Table table, BlockToken token, ByteString data, int rows, int code) {
    droppedRows.increment(Math.max(0, rows));
    synchronized (lock) {
      if (bytes.get() + data.size() > maxBytes) {
        LOG.error(
            "Block of {} ({} rows, token {}) refused by ClickHouse with code {} is discarded: the"
                + " dead-letter area {} is full ({} of {} bytes); its rows are counted as dropped",
            table.tableName(),
            rows,
            token,
            code,
            dir,
            bytes.get(),
            maxBytes);
        return Outcome.DISCARDED;
      }
      String name =
          TIME.format(clock.instant())
              + "-"
              + table.tableName()
              + "-code"
              + code
              + "-"
              + UNSAFE.matcher(token.value()).replaceAll("_")
              + SUFFIX;
      Path target = dir.resolve(name);
      try {
        write(target, data);
      } catch (IOException | RuntimeException e) {
        LOG.error(
            "Block of {} ({} rows, token {}) refused by ClickHouse with code {} is discarded:"
                + " writing it to the dead-letter area {} failed ({}); its rows are counted as"
                + " dropped",
            table.tableName(),
            rows,
            token,
            code,
            dir,
            e.getClass().getSimpleName());
        return Outcome.DISCARDED;
      }
      blocks.incrementAndGet();
      bytes.addAndGet(data.size());
      LOG.error(
          "Block of {} ({} rows, token {}) refused by ClickHouse with code {} three times was moved"
              + " to the dead-letter area as {}; its rows are counted as dropped",
          table.tableName(),
          rows,
          token,
          code,
          name);
      return Outcome.STORED;
    }
  }

  /** Recounts the block files of the area (they are only removed by an operator). */
  public void refresh() {
    long n = 0;
    long size = 0;
    if (Files.isDirectory(dir)) {
      try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*" + SUFFIX)) {
        for (Path f : files) {
          try {
            size += Files.size(f);
            n++;
          } catch (IOException e) {
            // removed meanwhile
          }
        }
      } catch (IOException e) {
        LOG.debug("Counting the dead-letter area failed: {}", e.toString());
        return;
      }
    }
    synchronized (lock) {
      blocks.set(n);
      bytes.set(size);
    }
  }

  private void write(Path target, ByteString data) throws IOException {
    createDirectory();
    boolean posix = Files.getFileAttributeView(dir, PosixFileAttributeView.class) != null;
    Path tmp = dir.resolve("." + target.getFileName() + ".tmp");
    Files.deleteIfExists(tmp);
    if (posix) {
      Files.createFile(tmp, PosixFilePermissions.asFileAttribute(FILE_MODE));
    } else {
      Files.createFile(tmp);
    }
    try {
      try (OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.WRITE)) {
        data.writeTo(out);
      }
      try (var channel = java.nio.channels.FileChannel.open(tmp, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      try {
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(tmp, target);
      }
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  private void createDirectory() throws IOException {
    if (Files.isDirectory(dir)) {
      return;
    }
    Path parent = dir.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    try {
      boolean posix =
          parent != null
              && Files.getFileAttributeView(parent, PosixFileAttributeView.class) != null;
      if (posix) {
        Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(DIR_MODE));
        // The umask may have removed bits only; make the mode exact.
        Files.setPosixFilePermissions(dir, DIR_MODE);
      } else {
        Files.createDirectory(dir);
      }
    } catch (FileAlreadyExistsException e) {
      // created meanwhile
    }
  }
}
