/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import static io.argos.server.ingest.IngestFixtures.session;
import static io.argos.server.ingest.IngestFixtures.tx;
import static io.argos.server.ingest.IngestFixtures.txId;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerPort;
import io.argos.proto.v1.TransactionRecord;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.wal.api.WalRecordType;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;

/**
 * T-ACC-13 (c) (12 §3.9 "ClickHouse 다운", 18 §10): ClickHouse is paused ({@code docker pause}) for
 * {@code argos.test.chOutageMinutes} minutes (3 by default, {@code -Pserver.chOutageMinutes=30} for
 * the full variant) while batches keep arriving: every append (the ack point) returns, the WAL
 * grows, and after the unpause every row lands exactly once.
 */
@Tag("req:D-13")
@Tag("req:T-ACC-13")
@Tag("slow")
class ClickHouseOutageIT {

  @TempDir Path temp;

  @Test
  @DisplayName(
      "T-ACC-13 (c) ClickHouse paused while batches arrive: acks stay OK, the WAL grows, after the"
          + " outage every row lands exactly once")
  void outage() throws Exception {
    long outageMinutes = Long.getLong("argos.test.chOutageMinutes", 3);
    Duration outage = Duration.ofMinutes(outageMinutes);
    try (ServerTestEnvironment env = ServerTestEnvironment.start()) {
      IngestStack.ensureMigrated(env);
      int appId;
      try (Connection c = env.connect(PgAccount.APP)) {
        appId = AgentTokens.ensureApp(c, "it-outage", "prod");
      }
      String containerId = clickHouseContainer(env);
      DockerClient docker = DockerClientFactory.instance().client();
      Path dir = temp.resolve("outage-wal");
      Files.createDirectories(dir);
      IngestStack.Options options =
          new IngestStack.Options(500, 50_000, 200_000, 4L << 20, 1L << 30, 10_000, 1_000, i -> i);
      int sent = 0;
      long maxAppendMs = 0;
      long walBefore;
      long walAtPeak;
      try (IngestStack stack = IngestStack.start(env, dir, options)) {
        int seq = 0;
        // 10 s of normal traffic, the outage with traffic, 10 s of traffic after it.
        long normalEnd = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < normalEnd) {
          sent += sendBatch(stack, appId, ++seq);
          Thread.sleep(250);
        }
        stack.awaitLoaded();
        walBefore = stack.wal.bytesInUse();
        docker.pauseContainerCmd(containerId).exec();
        try {
          long outageEnd = System.nanoTime() + outage.toNanos();
          while (System.nanoTime() < outageEnd) {
            long t0 = System.nanoTime();
            sent += sendBatch(stack, appId, ++seq);
            maxAppendMs =
                Math.max(maxAppendMs, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
            Thread.sleep(250);
          }
          walAtPeak = stack.wal.bytesInUse();
        } finally {
          docker.unpauseContainerCmd(containerId).exec();
        }
        long afterEnd = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < afterEnd) {
          sent += sendBatch(stack, appId, ++seq);
          Thread.sleep(250);
        }
        stack.awaitLoaded();
      }
      long rows =
          env.clickHouse(io.argos.storage.clickhouse.Account.READER)
              .scalarLong("SELECT count() FROM argos.xlog WHERE app_id = " + appId);
      long distinct =
          env.clickHouse(io.argos.storage.clickhouse.Account.READER)
              .scalarLong("SELECT uniqExact(tx_id) FROM argos.xlog WHERE app_id = " + appId);
      long rollup =
          env.clickHouse(io.argos.storage.clickhouse.Account.READER)
              .scalarLong("SELECT sum(count) FROM argos.app_stat_1m WHERE app_id = " + appId);
      System.out.printf(
          "T-ACC-13 (c): outage %d min, sent %d rows, WAL %d -> %d bytes, slowest append %d ms"
              + " (reference, shared laptop)%n",
          outageMinutes, sent, walBefore, walAtPeak, maxAppendMs);
      assertThat(walAtPeak).as("the WAL is the spool during the outage").isGreaterThan(walBefore);
      assertThat(maxAppendMs).as("an append (ack) never waits for ClickHouse").isLessThan(5_000);
      assertThat(rows).isEqualTo(sent);
      assertThat(distinct).isEqualTo(sent);
      assertThat(rollup).isEqualTo(sent);
    }
  }

  private static int sendBatch(IngestStack stack, int appId, int seq) throws Exception {
    long now = System.currentTimeMillis();
    List<TransactionRecord> records = new ArrayList<>();
    for (int j = 0; j < 20; j++) {
      records.add(tx(txId(now, seq * 100 + j), now - 20, 1_500, 1, 0).build());
    }
    stack.append(
        WalRecordType.TX,
        session(appId, 951, now, 0, 500, ""),
        IngestFixtures.txBatch(records),
        seq);
    return records.size();
  }

  /** The id of the ClickHouse container of {@code env}: the one publishing its HTTP port. */
  private static String clickHouseContainer(ServerTestEnvironment env) {
    int port = URI.create(env.clickHouseUrl()).getPort();
    DockerClient docker = DockerClientFactory.instance().client();
    for (Container c : docker.listContainersCmd().exec()) {
      for (ContainerPort p : c.getPorts()) {
        if (p.getPublicPort() != null && p.getPublicPort() == port) {
          return c.getId();
        }
      }
    }
    throw new IllegalStateException("No container publishes port " + port);
  }
}
