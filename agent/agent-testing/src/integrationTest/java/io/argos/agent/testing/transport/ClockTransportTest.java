/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.argos.agent.testing.collector.FakeCollector;
import io.argos.agent.testing.collector.HeartbeatRecord;
import io.argos.agent.testing.collector.SessionInfo;
import io.argos.agent.testing.launcher.ExitedApp;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-ACC-02a, the agent side of T-ACC-02 (D-02, 11 §9, ADR-0010 C-122 letter suffix): the
 * collector's clock runs 3 s ahead; the agent's heartbeats carry {@code t1} and the {@code
 * prev_t1}/{@code prev_t4} of the last completed round, and the offset the collector computes from
 * them is about +3 s. The agent never corrects its own timestamps. The WARN on the instance is
 * WO-22.
 */
@Tag("req:D-02")
class ClockTransportTest {
  @TempDir(cleanup = CleanupMode.NEVER)
  Path work;

  @AfterEach
  void deleteRun() {
    TransportRuns.deleteRun(work);
  }

  @Test
  @DisplayName(
      "T-ACC-02a collector clock +3 s: the collector's offset median from t1, prev_t1 and prev_t4 is"
          + " about 3 s; argos_agent_clock_offset_ms reports it; batch times stay on the agent clock")
  void offsetAboutThreeSeconds() throws Exception {
    try (FakeCollector collector =
        FakeCollector.builder().tls(true).tlsDirectory(work).heartbeatIntervalS(1).start()) {
      collector.faults().clockOffset(Duration.ofSeconds(3));
      long before = System.currentTimeMillis();
      ExitedApp run =
          TransportRuns.runToExit(
              TransportRuns.trivial(
                  work.resolve("clock"), TransportRuns.mainJdk(), collector, "12000"));
      long after = System.currentTimeMillis();

      List<SessionInfo> sessions = collector.sessions();
      assertThat(sessions).as(run.agentLog()).isNotEmpty();
      long instance = sessions.get(0).instanceId();

      List<HeartbeatRecord> heartbeats = collector.heartbeats();
      assertThat(heartbeats.size())
          .as("heartbeats at 1 s on a 12 s run; log:\n%s", run.agentLog())
          .isGreaterThanOrEqualTo(6);
      assertThat(heartbeats.get(0).prevT1()).as("no round before the first").isZero();
      assertThat(heartbeats.get(0).prevT4()).isZero();
      for (int i = 1; i < heartbeats.size(); i++) {
        HeartbeatRecord current = heartbeats.get(i);
        HeartbeatRecord previous = heartbeats.get(i - 1);
        assertThat(current.prevT1()).as("heartbeat %d", i).isEqualTo(previous.t1());
        assertThat(current.prevT4())
            .as("the ack of the last round arrived after its t1, on the agent clock")
            .isBetween(previous.t1(), current.t1());
        assertThat(current.t1() - previous.t1()).as("1 s interval").isBetween(500L, 5_000L);
        assertThat(current.t1()).as("agent clock, not corrected").isBetween(before, after);
      }
      OptionalLong offset = collector.clockOffsetMs(instance);
      assertThat(offset).isPresent();
      assertThat((double) offset.getAsLong())
          .as("the moving median; reference value on a shared laptop")
          .isCloseTo(3_000.0, within(500.0));

      long reported = run.selfDiagnostics().counter("argos_agent_clock_offset_ms");
      assertThat((double) reported)
          .as("the agent's gauge is the server's value of the last ack")
          .isCloseTo(3_000.0, within(500.0));
    }
  }
}
