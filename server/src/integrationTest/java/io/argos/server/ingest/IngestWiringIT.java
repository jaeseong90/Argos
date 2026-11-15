/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.bootstrap.PhaseOrder;
import io.argos.server.bootstrap.StartupSequence;
import io.argos.server.ingest.api.ApdexProvider;
import io.argos.server.ingest.api.DictionaryIntake;
import io.argos.server.ingest.api.IngestPipeline;
import io.argos.server.ops.management.MeterNames;
import io.argos.server.storage.api.ClickHouseWriter;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.wal.FileWal;
import io.argos.server.wal.WalDirectory;
import io.argos.server.wal.api.WalWriter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The durable ingest path inside the real server (Spring context, start sequence DSN-12-15): the
 * {@code WAL_RECOVERY} step opens the WAL and replays it before the listeners, the beans of the
 * contracts are the lane's implementations, the WAL survives a restart with its identity, and the
 * meters of 12 §9.4 follow the naming rule.
 */
@Tag("req:D-13")
@Tag("req:DSN-12-15")
class IngestWiringIT {

  @TempDir Path temp;

  @Test
  @DisplayName(
      "T-SV-010c the server runs WAL recovery in WAL_RECOVERY before the listeners, wires the ingest"
          + " contracts and registers the 12 §9.4 ingest meters")
  void serverStartsWithTheDurableIngestPath() throws Exception {
    Path walDir = temp.resolve("wal");
    try (ServerTestEnvironment env = ServerTestEnvironment.start()) {
      String identity;
      try (ArgosTestServer server =
          ArgosTestServer.start(env, Map.of("argos.wal.dir", walDir.toString()))) {
        StartupSequence sequence = server.bean(StartupSequence.class);
        List<StartupSequence.StepResult> results = sequence.results();
        assertThat(results)
            .filteredOn(r -> r.phase() == PhaseOrder.WAL_RECOVERY)
            .extracting(StartupSequence.StepResult::name)
            .contains("wal-recovery");
        int recovery = indexOf(results, "wal-recovery");
        for (int i = 0; i < results.size(); i++) {
          if (results.get(i).phase() == PhaseOrder.LISTENERS) {
            assertThat(i).as("listener step after WAL recovery").isGreaterThan(recovery);
          }
        }
        assertThat(server.bean(WalWriter.class)).isInstanceOf(FileWal.class);
        assertThat(server.bean(ClickHouseWriter.class)).isNotNull();
        assertThat(server.bean(IngestPipeline.class)).isInstanceOf(IngestService.class);
        assertThat(server.bean(DictionaryIntake.class)).isInstanceOf(DictionaryResolver.class);
        assertThat(server.bean(ApdexProvider.class)).isInstanceOf(ApdexStamper.class);
        FileWal wal = server.bean(FileWal.class);
        assertThat(wal.isStarted()).isTrue();
        assertThat(Files.exists(walDir.resolve(WalDirectory.IDENTITY_FILE))).isTrue();
        identity = wal.identity().walInstanceId();
        MeterRegistry registry = server.bean(MeterRegistry.class);
        for (String name :
            List.of(
                "argos.wal.bytes",
                "argos.wal.segments",
                "argos.wal.fsync",
                "argos.wal.queued.bytes",
                "argos.writer.queue.rows",
                "argos.writer.inserts",
                "argos.writer.rows",
                "argos.ingest.lag",
                "argos.ts.clamped",
                "argos.ts.rejected",
                "argos.late.arrivals",
                "argos.dictionary.unresolved",
                "argos.dictionary.overflow")) {
          List<Meter> meters = registry.find(name).meters().stream().toList();
          assertThat(meters).as(name).isNotEmpty();
          for (Meter m : meters) {
            assertThat(MeterNames.violation(m.getId())).as(name).isEmpty();
          }
        }
      }
      // A restart reuses the WAL directory and its identity (C-61).
      try (ArgosTestServer again =
          ArgosTestServer.start(env, Map.of("argos.wal.dir", walDir.toString()))) {
        assertThat(again.bean(FileWal.class).identity().walInstanceId()).isEqualTo(identity);
        assertThat(again.bean(FileWal.class).identity().epoch()).isEqualTo(1);
      }
    }
  }

  private static int indexOf(List<StartupSequence.StepResult> results, String name) {
    for (int i = 0; i < results.size(); i++) {
      if (results.get(i).name().equals(name)) {
        return i;
      }
    }
    return -1;
  }
}
