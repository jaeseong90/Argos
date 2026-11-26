/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.soak;

import io.argos.server.storage.api.BlockIntent;
import io.argos.server.storage.api.BlockToken;
import io.argos.server.storage.api.Table;
import io.argos.server.storage.clickhouse.ClickHouseBatchWriter;
import io.argos.server.wal.WalFormat;
import io.argos.server.wal.WalSegmentReader;
import io.argos.server.wal.api.WalPosition;
import io.argos.server.wal.api.WalRecordType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The unloaded backlog of the server's WAL (ADR-0010 C-259 (4), 18 §11.2): the bytes from the
 * oldest data record whose rows are not all in ClickHouse yet (the load-acknowledged position) to
 * the write position (the end of the last valid record). The WAL itself is judged by this, not by
 * its size: the WAL rotates at {@code argos.wal.segment-bytes} (256 MiB, 12 §3.4) and deletes only
 * closed, fully loaded segments, so its size stays near one segment in normal operation.
 *
 * <p>Read-only scan of the segment files while the server runs (nothing is cut or written; a torn
 * tail of the active segment ends the scan of that segment; a segment deleted by the server's WAL
 * reclamation meanwhile was fully loaded and is skipped). A data record of type {@code D} is loaded
 * when every table {@code D} feeds ({@link ClickHouseBatchWriter#tablesOf}, the server's own
 * mapping) has a committed block intent ({@code BLOCK_INTENT} with a {@code BLOCK_COMMIT} of its
 * token) whose last position is at or after the record: the writer of a table inserts its blocks in
 * WAL order, one at a time, and an intent's range includes the records without rows for that table.
 * Limit of the method: a batch of a table made of such empty records only advances the server's
 * coverage without an intent, so a table that gets no rows for a while can count as not loaded here
 * (an over-estimate, never an under-estimate); the soak load feeds every table continuously.
 */
final class WalBacklog {

  private WalBacklog() {}

  /** One record of the scan that matters for the backlog. */
  record Entry(WalRecordType type, WalPosition position, BlockIntent intent, String commitToken) {}

  /**
   * One segment of the scan.
   *
   * @param segmentId the id
   * @param validEnd offset after the last valid record
   * @param entries data records, intents and commits in file order
   */
  record Segment(int segmentId, long validEnd, List<Entry> entries) {}

  /**
   * The backlog in bytes of the WAL in {@code dir}, 0 when every data record is loaded.
   *
   * @throws IOException when the directory cannot be listed or a segment cannot be read
   */
  static long scan(Path dir) throws IOException {
    List<Integer> ids = new ArrayList<>();
    try (Stream<Path> files = Files.list(dir)) {
      for (Path f : (Iterable<Path>) files::iterator) {
        OptionalInt id = WalFormat.segmentId(f.getFileName().toString());
        if (id.isPresent()) {
          ids.add(id.getAsInt());
        }
      }
    }
    ids.sort(null);
    List<Segment> segments = new ArrayList<>();
    for (int id : ids) {
      Path file = dir.resolve(WalFormat.segmentName(id));
      List<Entry> entries = new ArrayList<>();
      WalSegmentReader.ScanResult result;
      try (WalSegmentReader reader = WalSegmentReader.open(file, id)) {
        result =
            reader.scan(
                WalFormat.SEGMENT_HEADER_BYTES,
                Long.MAX_VALUE,
                record -> {
                  switch (record.type()) {
                    case BLOCK_INTENT -> {
                      try {
                        entries.add(
                            new Entry(
                                record.type(),
                                record.position(),
                                BlockIntent.decode(record.payload()),
                                null));
                      } catch (IllegalArgumentException e) {
                        // unreadable intent: its records stay unloaded here
                      }
                    }
                    case BLOCK_COMMIT -> {
                      try {
                        entries.add(
                            new Entry(
                                record.type(),
                                record.position(),
                                null,
                                BlockToken.fromCommitPayload(record.payload()).value()));
                      } catch (IllegalArgumentException e) {
                        // unreadable commit: its block stays unloaded here
                      }
                    }
                    default -> entries.add(new Entry(record.type(), record.position(), null, null));
                  }
                });
      } catch (NoSuchFileException e) {
        continue; // reclaimed by the server between the listing and the open: fully loaded
      }
      segments.add(new Segment(id, result.validEnd(), entries));
    }
    return backlog(segments);
  }

  /** The backlog of scanned segments (ascending ids). */
  static long backlog(List<Segment> segments) {
    Set<String> committed = new HashSet<>();
    for (Segment s : segments) {
      for (Entry e : s.entries()) {
        if (e.commitToken() != null) {
          committed.add(e.commitToken());
        }
      }
    }
    Map<Table, WalPosition> loadedThrough = new EnumMap<>(Table.class);
    for (Segment s : segments) {
      for (Entry e : s.entries()) {
        if (e.intent() != null && committed.contains(e.intent().token().value())) {
          WalPosition last = e.intent().last();
          loadedThrough.merge(e.intent().table(), last, (a, b) -> a.compareTo(b) >= 0 ? a : b);
        }
      }
    }
    for (int i = 0; i < segments.size(); i++) {
      Segment s = segments.get(i);
      for (Entry e : s.entries()) {
        if (!e.type().isData() || loaded(e, loadedThrough)) {
          continue;
        }
        long bytes = s.validEnd() - e.position().offset();
        for (int j = i + 1; j < segments.size(); j++) {
          bytes += segments.get(j).validEnd() - WalFormat.SEGMENT_HEADER_BYTES;
        }
        return Math.max(0, bytes);
      }
    }
    return 0;
  }

  private static boolean loaded(Entry e, Map<Table, WalPosition> loadedThrough) {
    for (Table table : ClickHouseBatchWriter.tablesOf(e.type())) {
      WalPosition through = loadedThrough.get(table);
      if (through == null || through.compareTo(e.position()) < 0) {
        return false;
      }
    }
    return true;
  }
}
