/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.AckStatus;
import io.argos.server.config.IntegrationLogs;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.fakeagent.AgentClock;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.CallResult;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.argos.server.testing.fakeagent.Records;
import io.argos.server.wal.FileWal;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-ACC-13 (c) end to end (12 §3.9 "ClickHouse 다운", 18 §10): a fake agent keeps sending over gRPC
 * to the whole server while ClickHouse is paused ({@code docker pause}) for {@code
 * argos.test.chOutageMinutes} minutes (3 by default; {@code -Pserver.chOutageMinutes=30} for the
 * full 03 §3.2 variant, on a quiet machine). Every batch is acknowledged {@code ACK_OK} without
 * waiting for ClickHouse, the WAL is the spool, and after the outage sent = {@code count(xlog)} =
 * {@code sum(count)} of every transaction rollup, with no duplicate {@code tx_id}.
 */
@Tag("integration")
@Tag("slow")
@Tag("req:D-13")
@Tag("req:T-ACC-13")
class ClickHouseOutageE2eIT {

  @Test
  @DisplayName(
      "T-ACC-13 (c) ClickHouse paused while a fake agent sends over gRPC: acks stay OK, the WAL"
          + " grows, after the outage sent = count(xlog) = every rollup")
  void outageThroughTheCollector() throws Exception {
    long outageMinutes = Long.getLong("argos.test.chOutageMinutes", 3);
    IntegrationLogs.ensureLogPath();
    try (ServerTestEnvironment env = ServerTestEnvironment.start();
        ArgosTestServer server = ArgosTestServer.start(env)) {
      String app = "e2e-outage";
      AgentTokens.IssuedToken token = E2e.issue(env, app);
      String container = E2e.clickHouseContainer(env);
      FileWal wal = server.bean(FileWal.class);
      long sent = 0;
      long maxAckMs = 0;
      long walBefore;
      long walAtPeak;
      int batches = 0;
      try (FakeAgent agent =
          E2e.agent(
              env, token.token(), app, "outage-1", new Records(AgentClock.system(), 21, 21), 21)) {
        HelloResult hello = agent.connect();
        assertThat(hello.accepted()).as(hello.toString()).isTrue();
        // 10 s of normal traffic, the outage with traffic, 10 s of traffic after it.
        long[] phase = new long[2];
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
          send(agent, phase);
          batches++;
        }
        sent += phase[0];
        assertThat(E2e.awaitTotals(env, token.appId(), sent, Duration.ofMinutes(1)))
            .isEqualTo(E2e.expected(sent));
        walBefore = wal.bytesInUse();
        E2e.pauseClickHouse(container);
        long[] during = new long[2];
        try {
          end = System.nanoTime() + TimeUnit.MINUTES.toNanos(outageMinutes);
          while (System.nanoTime() < end) {
            send(agent, during);
            batches++;
          }
          walAtPeak = wal.bytesInUse();
        } finally {
          E2e.unpauseClickHouse(container);
        }
        sent += during[0];
        maxAckMs = during[1];
        long[] after = new long[2];
        end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
          send(agent, after);
          batches++;
        }
        sent += after[0];
      }
      Map<String, Long> totals = E2e.awaitTotals(env, token.appId(), sent, Duration.ofMinutes(5));
      System.out.printf(
          Locale.ROOT,
          "T-ACC-13 (c) e2e (reference, shared laptop): outage %d min, %d batches, %d transactions,"
              + " WAL %d -> %d bytes, slowest ack during the outage %d ms, totals %s%n",
          outageMinutes,
          batches,
          sent,
          walBefore,
          walAtPeak,
          maxAckMs,
          totals);
      assertThat(walAtPeak).as("the WAL is the spool during the outage").isGreaterThan(walBefore);
      assertThat(maxAckMs).as("an ack never waits for ClickHouse (ms)").isLessThan(5_000);
      assertThat(totals).isEqualTo(E2e.expected(sent));
    }
  }

  /** One batch of 20 transactions every 250 ms; {@code acc} = {transactions, slowest ack ms}. */
  private static void send(FakeAgent agent, long[] acc) throws InterruptedException {
    long t0 = System.nanoTime();
    CallResult r =
        agent.sendTransactions(agent.batches().transactions(E2e.transactions(agent.records(), 20)));
    long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
    assertThat(r.ackStatus()).as(r.toString()).isEqualTo(AckStatus.ACK_OK);
    assertThat(r.ack().getDroppedItems()).isZero();
    acc[0] += 20;
    acc[1] = Math.max(acc[1], ms);
    TimeUnit.MILLISECONDS.sleep(Math.max(0, 250 - ms));
  }
}
