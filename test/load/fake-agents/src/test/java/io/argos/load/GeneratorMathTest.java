/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.argos.server.testing.fakeagent.AgentClock;
import io.argos.server.testing.fakeagent.FakeAgent;
import io.argos.server.testing.fakeagent.Records;
import io.argos.server.testing.fakeagent.TxIds;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("req:N-06")
class GeneratorMathTest {

  @Test
  @DisplayName("Zipf over 100 services (s = 1.1): probabilities sum to 1, rank 0 is the mode")
  void zipf() {
    Zipf z = new Zipf(100, 1.1);
    double total = 0;
    for (int k = 0; k < z.size(); k++) {
      total += z.probability(k);
    }
    assertThat(total).isCloseTo(1.0, within(1e-9));
    assertThat(z.probability(0)).isGreaterThan(z.probability(1));
    int[] hits = new int[100];
    SplittableRandom random = new SplittableRandom(7);
    for (int i = 0; i < 100_000; i++) {
      hits[z.sample(random)]++;
    }
    assertThat(hits[0] / 100_000.0).isCloseTo(z.probability(0), within(0.01));
    assertThat(hits[0]).isGreaterThan(hits[9]).isGreaterThan(hits[99]);
  }

  @Test
  @DisplayName(
      "T-NFR-06a dashboard latencies: nearest-rank quantiles, only the measured window, failed"
          + " calls counted as errors")
  void latencyRecorder() {
    LatencyRecorder r = new LatencyRecorder();
    r.record("dashboard", 5_000, true);
    r.measuring(true);
    for (int i = 1; i <= 100; i++) {
      r.record("dashboard", i, true);
    }
    for (int i = 1; i <= 300; i++) {
      r.record("xlog", 1_000 + i, i != 1);
    }
    r.record("xlog", Double.NaN, false);
    r.measuring(false);
    r.record("xlog", 9_999, true);
    assertThat(r.count()).isEqualTo(400);
    assertThat(r.errors()).isEqualTo(2);
    assertThat(r.summary().get("dashboard")).containsExactly(100, 0, 50, 95, 100);
    assertThat(r.quantile(0.95)).isEqualTo(1_280);
    assertThat(LatencyRecorder.quantile(new double[0], 0.95)).isNaN();
  }

  @Test
  @Tag("req:D-13")
  @DisplayName(
      "T-NFR-06a back-dated start times: an 11-bit seq that wraps within one ms is drawn again,"
          + " so 20,000 transactions of one instance keep distinct tx_ids (D-13 uniqExact = sent)")
  void txIdsStayDistinctWhenSeqWrapsWithinOneMs() {
    LoadOptions options = LoadOptions.parse(List.of("--profile=smoke"));
    AgentClock clock = AgentClock.skewed(Duration.ZERO);
    int node = 7;
    ManagedChannel channel =
        ManagedChannelBuilder.forAddress("127.0.0.1", 9).usePlaintext().build();
    try {
      FakeAgent agent =
          FakeAgent.builder(channel)
              .ownsChannel(false)
              .appName("load-app-0")
              .env("prod")
              .instanceName("load-app-0-prod-i0")
              .clock(clock)
              .records(new Records(clock, node, 42L))
              .build();
      SimInstance instance =
          new SimInstance(
              0,
              new LoadApp(0, LoadApp.nameOf(0), "prod", null),
              agent,
              new Workload(options.services(), 50, 10, options.metricsPerInstance()),
              options,
              new LoadCounters(1),
              42L);
      // Four ticks of 5,000 transactions, one second apart: each tick wraps the 11-bit seq twice
      // while its start times fall within about one second, so seq k and k + 2,048 meet in the
      // same ms. The ids remembered (16,384) are evicted in the last tick.
      long nowMs = TxIds.EPOCH_MS + 10_000_000L;
      List<Long> ids = new ArrayList<>();
      for (int tick = 0; tick < 4; tick++, nowMs += 1_000) {
        for (int i = 0; i < 5_000; i++) {
          ids.add(instance.transaction(nowMs).getTxId());
        }
      }

      assertThat(new HashSet<>(ids)).hasSize(ids.size());
      // The same start times numbered by a plain 11-bit counter collide: the case is exercised.
      TxIds plain = new TxIds(node);
      Set<Long> plainIds = new HashSet<>();
      int collisions = 0;
      for (long id : ids) {
        if (!plainIds.add(plain.next(TxIds.startMs(id)))) {
          collisions++;
        }
      }
      assertThat(collisions).isPositive();
    } finally {
      channel.shutdownNow();
    }
  }
}
