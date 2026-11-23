/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.proto.v1.AckStatus;
import io.argos.proto.v1.CloseReason;
import io.argos.proto.v1.ServerMessage;
import io.argos.proto.v1.SessionClose;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The server stop of 12 §3.9 with the durable ingest path (WO-21): {@code SessionClose
 * {SERVER_SHUTDOWN, retry_after_s = 30}} to every session and the gRPC drain first, then the ingest
 * dispatcher, then the ClickHouse writer's last flush, then the WAL; what was acknowledged and not
 * loaded stays in the WAL, and the next start loads it exactly once.
 */
@Tag("integration")
@Tag("req:D-13")
@Tag("req:DSN-12-01")
class ShutdownE2eIT {

  @Test
  @DisplayName(
      "T-SV-010d graceful stop in the 12 §3.9 order (SessionClose and drain, ingest, writer flush,"
          + " WAL), then a restart on the same WAL: count(xlog) = sent, every rollup = sent")
  void stopInOrderThenRestart() throws Exception {
    try (ServerTestEnvironment env = ServerTestEnvironment.start()) {
      String app = "e2e-shutdown";
      long sent = 0;
      String stopLog;
      Optional<SessionClose> close;
      // The server's start migrates PostgreSQL; the token row comes after.
      ArgosTestServer first = ArgosTestServer.start(env);
      AgentTokens.IssuedToken token = E2e.issue(env, app);
      try (FakeAgent agent =
          E2e.agent(
              env,
              token.token(),
              app,
              "shutdown-1",
              new Records(AgentClock.system(), 31, 31),
              31)) {
        HelloResult hello = agent.connect();
        assertThat(hello.accepted()).as(hello.toString()).isTrue();
        for (int i = 0; i < 50; i++) {
          CallResult r =
              agent.sendTransactions(
                  agent.batches().transactions(E2e.transactions(agent.records(), 20)));
          assertThat(r.ackStatus()).as(r.toString()).isEqualTo(AckStatus.ACK_OK);
          sent += 20;
        }
        long before = IntegrationLogs.offset();
        // The last batches are still in the writer's 2 s window: the stop's last flush takes them.
        first.close();
        // The log may roll over between the offset and now (IntegrationLogs.since).
        stopLog = IntegrationLogs.since(before);
        close =
            agent
                .awaitServerMessage(ServerMessage::hasClose, Duration.ofSeconds(10))
                .map(ServerMessage::getClose);
      }
      assertThat(close).as("SessionClose of the stop").isPresent();
      assertThat(close.get().getReason()).isEqualTo(CloseReason.SERVER_SHUTDOWN);
      assertThat(close.get().getRetryAfterS()).isEqualTo(30);
      List<String> steps =
          List.of(
              "gRPC collector stopping",
              "gRPC collector stopped",
              "Ingest dispatcher stopped",
              "ClickHouse writer stopped after its last flush",
              "WAL closed");
      int previous = -1;
      for (String step : steps) {
        int at = stopLog.indexOf(step);
        assertThat(at)
            .as("'%s' in the stop log, after the previous step", step)
            .isGreaterThan(previous);
        previous = at;
      }

      try (ArgosTestServer second = ArgosTestServer.start(env, Map.of())) {
        assertThat(second.bean(FileWal.class).identity().epoch())
            .as("the same WAL directory and identity (C-61)")
            .isEqualTo(1);
        Map<String, Long> totals = E2e.awaitTotals(env, token.appId(), sent, Duration.ofMinutes(2));
        assertThat(totals).isEqualTo(E2e.expected(sent));
      }
    }
  }
}
