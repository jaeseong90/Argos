/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.ByteString;
import io.argos.server.storage.api.EncodedRows;
import io.argos.server.storage.api.Table;
import io.argos.server.wal.FileWal;
import io.argos.server.wal.WalDirectory;
import io.argos.server.wal.WalRecovery;
import io.argos.server.wal.api.WalRecordType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The batch writer's flush request in real time (a table thread wakes once a second when idle): a
 * request made with nothing queued, as the recovery makes at every start, does not flush the next
 * entry early. A WAL in a temporary directory and an in-memory inserter, no database; slow because
 * it waits for two idle wake-ups (ADR-0010 C-146 (2)).
 */
@Tag("slow")
@Tag("req:D-13")
class WriterFlushRequestIT {

  @TempDir Path dir;

  @Test
  @DisplayName(
      "a flush request on an empty queue is not remembered: a later entry waits for the interval"
          + " or the row count")
  void flushRequestOnEmptyQueueNotRemembered() throws Exception {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    FileWal wal = new FileWal(dir, 1 << 20, 1 << 26, 1, 0.8, registry, Clock.systemUTC());
    WalDirectory directory = WalDirectory.open(dir);
    wal.start(directory, WalRecovery.scan(directory, r -> {}).segments());
    ClickHouseBatchWriter writer =
        new ClickHouseBatchWriter(wal, 60_000, 4, 10, null, registry, Clock.systemUTC());
    List<Table> inserts = new CopyOnWriteArrayList<>();
    try {
      writer.start((table, token, data) -> inserts.add(table));
      writer.flushNow(); // what the recovery does at every start, with nothing queued
      var position =
          wal.append(WalRecordType.TX, 1, 1, 1, System.currentTimeMillis(), 0, new byte[] {1});
      EncodedRows one = new EncodedRows(1, ByteString.copyFrom("a", StandardCharsets.UTF_8));
      assertThat(writer.offer(Table.XLOG, position, one, 0, 1000)).isTrue();
      // The idle table thread wakes at least once a second: two chances to flush the single row
      // (of 4, at an interval of 60 s) because of the old request.
      Thread.sleep(2_200);
      assertThat(inserts).isEmpty();
      assertThat(writer.queuedRows(Table.XLOG)).isEqualTo(1);
      writer.flushNow(); // a request with the row queued flushes it
      assertThat(writer.awaitIdle(10_000)).isTrue();
      assertThat(inserts).containsExactly(Table.XLOG);
    } finally {
      writer.close();
      wal.close();
    }
  }
}
