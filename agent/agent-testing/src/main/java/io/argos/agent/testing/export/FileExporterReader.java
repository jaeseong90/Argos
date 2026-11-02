/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.export;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Reader of the JSONL file of the agent's file exporter (18 §5.1 FileExporterReader): {@code
 * -Dargos.test.exporter=file:<dir>} writes {@code <dir>/}{@value #FILE_NAME}; the WO-02 PoC key
 * {@code -Dargos.exporter=file -Dargos.exporter.file.path=<file>} writes the named file. The
 * agent's sender thread appends and flushes about every 100 ms, so tests wait for the transactions
 * they expect ({@link #await}) instead of reading once. A line being written may be incomplete:
 * only lines that end with a newline are read.
 */
public final class FileExporterReader {
  /** File name of {@code argos.test.exporter=file:<dir>} (agent-core {@code Exporters}). */
  public static final String FILE_NAME = "argos-transactions.jsonl";

  private final Path file;

  public FileExporterReader(Path file) {
    this.file = file;
  }

  /** The reader of {@code argos.test.exporter=file:<directory>}. */
  public static FileExporterReader inDirectory(Path directory) {
    return new FileExporterReader(directory.resolve(FILE_NAME));
  }

  public Path file() {
    return file;
  }

  /** Every complete line written so far, parsed. */
  public List<TransactionRecord> read() {
    if (!Files.exists(file)) {
      return new ArrayList<>();
    }
    String text;
    try {
      text = Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    List<TransactionRecord> records = new ArrayList<>();
    int start = 0;
    int newline;
    while ((newline = text.indexOf('\n', start)) >= 0) {
      String line = text.substring(start, newline).strip();
      if (!line.isEmpty()) {
        records.add(TransactionRecord.parse(line));
      }
      start = newline + 1;
    }
    return records;
  }

  /** The transactions that {@code filter} accepts. */
  public List<TransactionRecord> read(Predicate<TransactionRecord> filter) {
    List<TransactionRecord> matching = new ArrayList<>();
    for (TransactionRecord record : read()) {
      if (filter.test(record)) {
        matching.add(record);
      }
    }
    return matching;
  }

  /**
   * Waits until at least {@code count} transactions match {@code filter} (polling every 50 ms) and
   * returns all matching ones; returns what is there when {@code timeout} passes.
   */
  public List<TransactionRecord> await(
      Predicate<TransactionRecord> filter, int count, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    List<TransactionRecord> matching = read(filter);
    while (matching.size() < count && System.nanoTime() < deadline) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      matching = read(filter);
    }
    return matching;
  }

  /** Transactions of the service {@code service}. */
  public static Predicate<TransactionRecord> service(String service) {
    return record -> service.equals(record.service());
  }

  /** Transactions of the request path {@code path}. */
  public static Predicate<TransactionRecord> path(String path) {
    return record -> path.equals(record.path());
  }

  /** Every transaction. */
  public static Predicate<TransactionRecord> any() {
    return record -> true;
  }
}
