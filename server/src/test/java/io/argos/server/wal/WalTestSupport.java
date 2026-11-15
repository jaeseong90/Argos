/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.wal;

import io.argos.server.wal.api.WalPosition;
import io.argos.server.wal.api.WalRecordType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/** Helpers of the WAL unit tests: a started WAL in a temporary directory. */
final class WalTestSupport {

  private WalTestSupport() {}

  /** A started WAL with the given segment and maximum sizes (fsync interval 1 ms). */
  static FileWal started(Path dir, long segmentBytes, long maxBytes, SimpleMeterRegistry registry)
      throws IOException {
    FileWal wal = new FileWal(dir, segmentBytes, maxBytes, 1, 0.8, registry, Clock.systemUTC());
    WalDirectory directory = WalDirectory.open(dir);
    WalRecovery.Scan scan = WalRecovery.scan(directory, r -> {});
    wal.start(directory, scan.segments());
    return wal;
  }

  /** Appends one TX record with a text payload. */
  static WalPosition appendTx(FileWal wal, int instanceId, long seq, String text) throws Exception {
    return wal.append(
        WalRecordType.TX,
        instanceId,
        77L,
        seq,
        1_700_000_000_000L + seq,
        0,
        text.getBytes(StandardCharsets.UTF_8));
  }

  /** Every record of every segment of {@code dir}, in WAL order. */
  static List<WalRecord> readAll(WalDirectory dir) throws IOException {
    List<WalRecord> records = new ArrayList<>();
    for (int id : dir.segmentIds()) {
      try (WalSegmentReader reader = WalSegmentReader.open(dir.segmentFile(id), id)) {
        reader.scan(WalFormat.SEGMENT_HEADER_BYTES, Long.MAX_VALUE, records::add);
      }
    }
    return records;
  }
}
