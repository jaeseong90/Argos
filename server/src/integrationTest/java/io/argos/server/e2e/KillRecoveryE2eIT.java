/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.AckStatus;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.fakeagent.AgentClock;
import io.argos.server.testing.fakeagent.AgentTokens;
import io.argos.server.testing.fakeagent.BatchKind;
import io.argos.server.testing.fakeagent.Batches;
import io.argos.server.testing.fakeagent.CallResult;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.HelloResult;
import io.argos.server.testing.fakeagent.Records;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-SV-010 and T-ACC-13 (b) end to end (18 §10, D-13, DSN-12-01): a fake agent sends 1,000
 * transaction batches over TLS to the whole server, started as a child JVM from the boot jar; every
 * batch is acknowledged, so every batch is in the WAL (fsync before ack). The server process is
 * killed twice ({@code destroyForcibly}: no shutdown hook, no last flush):
 *
 * <ol>
 *   <li>between two batches, after batch 500;
 *   <li>while a ClickHouse insert is in flight: ClickHouse is paused ({@code docker pause}) before
 *       the last 100 batches, so the writer has appended a {@code BLOCK_INTENT} and waits for its
 *       insert when the process dies.
 * </ol>
 *
 * After each restart on the same WAL directory the recovery (the {@code WAL_RECOVERY} start step)
 * loads what was not committed; after the second it sends the uncommitted block again with its
 * recorded token (C-61), which its log shows. At the end {@code count(xlog)} = transactions sent,
 * no duplicate {@code tx_id}, and every transaction rollup = transactions sent.
 */
@Tag("integration")
@Tag("slow")
@Tag("req:D-13")
@Tag("req:DSN-12-01")
class KillRecoveryE2eIT {

  private static final int BATCHES = 1_000;
  private static final int RECORDS_PER_BATCH = 10;
  private static final int KILL_AFTER = 500;
  private static final int PAUSE_BEFORE = 900;

  @TempDir Path work;

  @Test
  @Tag("req:T-ACC-13")
  @DisplayName(
      "T-SV-010/T-ACC-13 (b) 1,000 acked batches, the boot-jar server killed between batches and"
          + " during an insert, restarted twice: count(xlog) = sent, duplicates 0, rollups = sent,"
          + " the uncommitted intent re-inserted with its token")
  void killAndRecover() throws Exception {
    try (ServerTestEnvironment env = ServerTestEnvironment.start()) {
      String app = "e2e-kill";
      Records records = new Records(AgentClock.system(), 7, 1_010L);
      long epoch = 1_700_000_000_010L;
      String chContainer = E2e.clickHouseContainer(env);
      long sent = 0;
      long startNanos = System.nanoTime();
      long[] restartMs = new long[2];

      // Start 1: batches 1..500, then a kill between batches. The start migrates PostgreSQL; the
      // token row comes after.
      ServerProcess server = ServerProcess.start(env, work, "start-1", Map.of(), "1g", false);
      AgentTokens.IssuedToken token = E2e.issue(env, app);
      try (FakeAgent agent = E2e.agent(env, token.token(), app, "kill-1", records, epoch)) {
        connect(agent);
        sent += send(agent, 1, KILL_AFTER);
      }
      server.kill();
      E2e.BlockRecords afterFirstKill = E2e.blockRecords(env.walDirectory());

      // Start 2: recovery, batches 501..900, ClickHouse paused, batches 901..1000, a kill while
      // the writer's insert waits for the paused ClickHouse.
      long t0 = System.nanoTime();
      server = ServerProcess.start(env, work, "start-2", Map.of(), "1g", false);
      restartMs[0] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
      E2e.BlockRecords afterSecondKill;
      boolean paused = false;
      try {
        try (FakeAgent agent = E2e.agent(env, token.token(), app, "kill-1", records, epoch)) {
          connect(agent);
          sent += send(agent, KILL_AFTER + 1, PAUSE_BEFORE);
          E2e.pauseClickHouse(chContainer);
          paused = true;
          sent += send(agent, PAUSE_BEFORE + 1, BATCHES);
          // The writer flushes 2 s after the oldest queued row (argos.writer.flush-interval-ms):
          // wait until its block intent is in the WAL and the insert waits for ClickHouse.
          long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
          while (E2e.blockRecords(env.walDirectory()).uncommitted().isEmpty()
              && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(200);
          }
          TimeUnit.SECONDS.sleep(1);
        }
        server.kill();
        afterSecondKill = E2e.blockRecords(env.walDirectory());
      } finally {
        if (paused) {
          E2e.unpauseClickHouse(chContainer);
        }
        server.close();
      }
      assertThat(afterSecondKill.uncommitted())
          .as("a block intent without a commit at the second kill (insert in flight)")
          .isNotEmpty();

      // Start 3: recovery re-inserts the uncommitted blocks with their tokens, then loads the rest.
      t0 = System.nanoTime();
      Map<String, Long> totals;
      String recoveryLog;
      try (ServerProcess last = ServerProcess.start(env, work, "start-3", Map.of(), "1g", false)) {
        restartMs[1] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        totals = E2e.awaitTotals(env, token.appId(), sent, Duration.ofMinutes(3));
        recoveryLog = last.logText();
      }
      for (String intent : afterSecondKill.uncommitted()) {
        assertThat(recoveryLog)
            .as("the recovery log names the re-inserted block " + intent)
            .contains("WAL recovery: block " + intent + " of ")
            .contains("sent again with its token");
      }
      System.out.printf(
          Locale.ROOT,
          "T-SV-010 (reference, shared laptop): %d batches x %d transactions acked in %d s;"
              + " WAL at kill 1: %d intents, %d uncommitted, %d data records; at kill 2: %d"
              + " intents, %d uncommitted (%s), %d data records; restarts to the listener %d ms"
              + " and %d ms; totals %s%n",
          BATCHES,
          RECORDS_PER_BATCH,
          TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNanos),
          afterFirstKill.intents().size(),
          afterFirstKill.uncommitted().size(),
          afterFirstKill.dataRecords(),
          afterSecondKill.intents().size(),
          afterSecondKill.uncommitted().size(),
          afterSecondKill.uncommitted(),
          afterSecondKill.dataRecords(),
          restartMs[0],
          restartMs[1],
          totals);
      assertThat(sent).isEqualTo((long) BATCHES * RECORDS_PER_BATCH);
      assertThat(totals)
          .as("count(xlog) = sent, no duplicate tx_id, every rollup = sent")
          .isEqualTo(E2e.expected(sent));
    }
  }

  private static void connect(FakeAgent agent) {
    HelloResult hello = agent.connect();
    assertThat(hello.accepted()).as(hello.toString()).isTrue();
  }

  /** Sends batches {@code from..to} (inclusive) and returns the transactions acknowledged. */
  private static long send(FakeAgent agent, int from, int to) {
    long n = 0;
    for (int seq = from; seq <= to; seq++) {
      CallResult r =
          agent.send(
              BatchKind.TRANSACTIONS,
              Batches.transactions(
                  agent.batches().header(BatchKind.TRANSACTIONS, seq, agent.batches().agentEpoch()),
                  E2e.transactions(agent.records(), RECORDS_PER_BATCH)),
              seq,
              FakeAgent.CallSpec.defaults());
      assertThat(r.ackStatus()).as("batch %d: %s", seq, r).isEqualTo(AckStatus.ACK_OK);
      assertThat(r.ack().getLastSeq()).isEqualTo(seq);
      assertThat(r.ack().getDroppedItems()).isZero();
      n += RECORDS_PER_BATCH;
    }
    return n;
  }
}
