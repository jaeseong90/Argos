/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.wal;

import io.argos.server.storage.api.BlockIntent;
import io.argos.server.storage.api.BlockToken;
import io.argos.server.wal.api.WalPosition;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The scan half of WAL recovery (12 §3.4 "복구"): every segment in order, the torn tail of a segment
 * cut at its first invalid record (a partial write of a crash), every data record handed to the
 * caller in WAL order (deduplication window restore, C-3), and the block index of the WAL itself
 * (DSN-12-02): every {@code BLOCK_INTENT} with whether a {@code BLOCK_COMMIT} of its token exists.
 *
 * <p>The re-insert of uncommitted intents with their recorded tokens and the load of the records
 * after the last intent are done by the ingest side ({@code io.argos.server.ingest.IngestService})
 * in the {@code WAL_RECOVERY} start phase, before any listener opens (DSN-12-15).
 */
public final class WalRecovery {

  private static final Logger LOG = LoggerFactory.getLogger(WalRecovery.class);

  /** Progress is logged at most this often. */
  private static final long PROGRESS_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);

  private WalRecovery() {}

  /** An intent found by the scan. */
  public record FoundIntent(WalPosition position, BlockIntent intent, boolean committed) {}

  /**
   * The result of the scan.
   *
   * @param segments every remaining segment, ascending, with its summary
   * @param intents every block intent in WAL order
   * @param dataRecords number of data records
   * @param truncatedSegments segments whose tail was cut
   * @param bytes bytes of the remaining segments
   */
  public record Scan(
      List<SegmentInfo> segments,
      List<FoundIntent> intents,
      long dataRecords,
      List<Integer> truncatedSegments,
      long bytes) {

    /** The intents without a commit, in WAL order. */
    public List<FoundIntent> uncommitted() {
      return intents.stream().filter(i -> !i.committed()).toList();
    }

    /** The first segment, or -1 for an empty WAL. */
    public int firstSegmentId() {
      return segments.isEmpty() ? -1 : segments.get(0).segmentId();
    }
  }

  /**
   * Scans every segment of {@code dir} and cuts torn tails.
   *
   * @param dir the opened WAL directory (the writer is not started yet)
   * @param dataRecords receives every data record in WAL order
   * @throws IOException when a segment cannot be read or cut
   */
  public static Scan scan(WalDirectory dir, Consumer<WalRecord> dataRecords) throws IOException {
    List<Integer> ids = dir.segmentIds();
    List<SegmentInfo> infos = new ArrayList<>();
    List<WalPosition> intentPositions = new ArrayList<>();
    List<BlockIntent> intents = new ArrayList<>();
    Set<String> committed = new HashSet<>();
    List<Integer> truncated = new ArrayList<>();
    long[] dataCount = {0};
    long totalBytes = 0;
    long started = System.nanoTime();
    long[] lastLog = {started};
    for (int i = 0; i < ids.size(); i++) {
      int id = ids.get(i);
      Path file = dir.segmentFile(id);
      boolean last = i == ids.size() - 1;
      if (Files.size(file) < WalFormat.SEGMENT_HEADER_BYTES) {
        // A segment whose creation was cut by a crash before its header was written.
        if (last) {
          LOG.warn("WAL segment {} has no complete header: deleted", file.getFileName());
          Files.delete(file);
          continue;
        }
        throw new IOException("WAL segment " + file + " has no complete header");
      }
      SegmentInfo info;
      try (WalSegmentReader reader = WalSegmentReader.open(file, id)) {
        SegmentInfo segment = new SegmentInfo(id, 0);
        WalSegmentReader.ScanResult result =
            reader.scan(
                WalFormat.SEGMENT_HEADER_BYTES,
                Long.MAX_VALUE,
                record -> {
                  switch (record.type()) {
                    case BLOCK_INTENT -> {
                      try {
                        intents.add(BlockIntent.decode(record.payload()));
                        intentPositions.add(record.position());
                      } catch (IllegalArgumentException e) {
                        LOG.warn(
                            "WAL block intent at {} unreadable, ignored: {}",
                            record.position(),
                            e.getMessage());
                      }
                    }
                    case BLOCK_COMMIT -> {
                      try {
                        committed.add(BlockToken.fromCommitPayload(record.payload()).value());
                      } catch (IllegalArgumentException e) {
                        LOG.warn(
                            "WAL block commit at {} unreadable, ignored: {}",
                            record.position(),
                            e.getMessage());
                      }
                    }
                    default -> {
                      segment.recordWritten(record.type(), record.position());
                      dataRecords.accept(record);
                      dataCount[0]++;
                    }
                  }
                  long now = System.nanoTime();
                  if (now - lastLog[0] > PROGRESS_INTERVAL_NANOS) {
                    lastLog[0] = now;
                    LOG.info(
                        "WAL recovery scan: segment {} of {}, {} data records so far",
                        WalFormat.segmentName(id),
                        ids.size(),
                        dataCount[0]);
                  }
                });
        if (result.torn()) {
          LOG.warn(
              "WAL segment {} cut at offset {} of {} ({}): the tail was not durable",
              file.getFileName(),
              result.validEnd(),
              result.fileSize(),
              result.tornReason());
          if (!last) {
            LOG.warn(
                "WAL segment {} is not the last one: records after offset {} are lost",
                file.getFileName(),
                result.validEnd());
          }
          cut(file, result.validEnd());
          truncated.add(id);
        }
        segment.truncatedTo(result.validEnd());
        info = segment;
      }
      infos.add(info);
      totalBytes += info.size();
    }
    List<FoundIntent> found = new ArrayList<>(intents.size());
    for (int i = 0; i < intents.size(); i++) {
      BlockIntent intent = intents.get(i);
      found.add(
          new FoundIntent(
              intentPositions.get(i), intent, committed.contains(intent.token().value())));
    }
    LOG.info(
        "WAL recovery scan finished in {} ms: {} segments, {} bytes, {} data records, {} block"
            + " intents ({} without commit), {} segments cut",
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
        infos.size(),
        totalBytes,
        dataCount[0],
        found.size(),
        found.stream().filter(f -> !f.committed()).count(),
        truncated.size());
    return new Scan(List.copyOf(infos), List.copyOf(found), dataCount[0], truncated, totalBytes);
  }

  private static void cut(Path file, long size) throws IOException {
    try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
      channel.truncate(size);
      channel.force(true);
    }
  }
}
