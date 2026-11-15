/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import static io.argos.server.ingest.IngestFixtures.session;
import static io.argos.server.ingest.IngestFixtures.tx;
import static io.argos.server.ingest.IngestFixtures.txId;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.wal.api.WalRecordType;
import io.argos.storage.clickhouse.Account;
import io.argos.storage.clickhouse.ClickHouseHttp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The server half of T-SV-020 (N-15: receive → visible to {@code argos_reader} within 5 s) with the
 * writer's default 2 s flush interval: the time from the append (the ack point) until the row is
 * readable. A wall-clock bound, so a {@code slow} integration test (ADR-0010 C-146 (2)); the
 * numbers of this shared laptop are a reference only.
 */
@Tag("req:N-15")
@Tag("req:T-SV-020")
@Tag("slow")
class IngestLatencyIT {

  @TempDir Path temp;

  @Test
  @DisplayName(
      "T-SV-020a receive -> argos_reader query: every one of 20 batches visible within 5 s")
  void receiveToQuery() throws Exception {
    try (ServerTestEnvironment env = ServerTestEnvironment.start()) {
      IngestStack.ensureMigrated(env);
      int appId;
      try (Connection c = env.connect(PgAccount.APP)) {
        appId = AgentTokens.ensureApp(c, "it-latency", "prod");
      }
      Path dir = temp.resolve("wal");
      Files.createDirectories(dir);
      ClickHouseHttp reader = env.clickHouse(Account.READER);
      List<Long> latencies = new ArrayList<>();
      IngestStack.Options defaults =
          IngestStack.Options.defaults().withFlush(2_000, 50_000); // argos.writer defaults
      try (IngestStack stack = IngestStack.start(env, dir, defaults)) {
        for (int seq = 1; seq <= 20; seq++) {
          long now = System.currentTimeMillis();
          long id = txId(now, seq);
          stack.append(
              WalRecordType.TX,
              session(appId, 1001, now, 0, 500, ""),
              IngestFixtures.txBatch(tx(id, now - 5, 1_000, 0, 0)),
              seq);
          long appended = System.nanoTime();
          while (reader.scalarLong(
                  "SELECT count() FROM argos.xlog WHERE app_id = "
                      + appId
                      + " AND tx_id = "
                      + Long.toUnsignedString(id))
              == 0) {
            assertThat(System.nanoTime() - appended)
                .as("batch %d not visible yet", seq)
                .isLessThan(30_000_000_000L);
            Thread.sleep(50);
          }
          latencies.add((System.nanoTime() - appended) / 1_000_000);
          Thread.sleep(137 * (seq % 7));
        }
        double p99Lag = 0;
        for (io.micrometer.core.instrument.distribution.ValueAtPercentile v :
            stack.registry.get("argos.ingest.lag").timer().takeSnapshot().percentileValues()) {
          if (v.percentile() == 0.99) {
            p99Lag = v.value(java.util.concurrent.TimeUnit.MILLISECONDS);
          }
        }
        System.out.printf(
            "T-SV-020a: receive -> query ms %s, argos_ingest_lag_seconds p99 %.0f ms (reference, shared laptop)%n",
            latencies, p99Lag);
      }
      assertThat(Collections.max(latencies))
          .as("slowest receive -> query (ms)")
          .isLessThanOrEqualTo(5_000L);
    }
  }
}
