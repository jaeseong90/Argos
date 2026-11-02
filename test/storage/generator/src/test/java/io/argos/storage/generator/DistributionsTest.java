/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.argos.storage.generator.model.Topology;
import io.argos.storage.generator.model.Weighted;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The distributions of docs/13 §15 on a 10-minute simulation (200 TPS, about 120,000 transactions).
 * Tolerances are several standard deviations of the sample.
 */
class DistributionsTest {

  private static SimulationFixture f;

  @BeforeAll
  static void simulate() throws InterruptedException {
    f = new SimulationFixture(SimulationFixture.defaultConfig());
  }

  @Test
  @DisplayName("Zipf: probabilities follow k^-s and sampling reproduces them")
  void zipf() {
    Weighted zipf = Weighted.zipf(100, 1.1);
    double sum = 0;
    for (int i = 0; i < zipf.size(); i++) {
      sum += zipf.probability(i);
    }
    assertThat(sum).isCloseTo(1.0, within(1e-9));
    assertThat(zipf.probability(0) / zipf.probability(1)).isCloseTo(Math.pow(2, 1.1), within(1e-9));
    SplittableRandom random = new SplittableRandom(1);
    int n = 200_000;
    int top = 0;
    for (int i = 0; i < n; i++) {
      if (zipf.sample(random) == 0) {
        top++;
      }
    }
    assertThat((double) top / n).isCloseTo(zipf.probability(0), within(0.006));
  }

  @Test
  @DisplayName("HTTP services: global Zipf s=1.1 over 100 ranks (root transactions)")
  void serviceZipf() {
    Weighted expected = Weighted.zipf(100, 1.1);
    long total = f.rootHttp;
    for (int rank : new int[] {1, 2, 5, 20}) {
      double observed = f.rootHttpByRank.getOrDefault(rank, 0L) / (double) total;
      assertThat(observed)
          .as("rank %d", rank)
          .isCloseTo(expected.probability(rank - 1), within(expected.probability(rank - 1) * 0.1));
    }
    assertThat(f.rootHttpByRank.keySet()).hasSizeGreaterThan(90);
  }

  @Test
  @DisplayName("Response time: log-normal median 80 ms, sigma 1.0, plus 1 % outliers of 2-30 s")
  void latency() {
    List<Integer> regular = new ArrayList<>();
    for (int e : f.rootHttpElapsed) {
      if (e < f.config.outlierMinMs) {
        regular.add(e);
      }
    }
    Collections.sort(regular);
    double median = regular.get(regular.size() / 2);
    assertThat(median).isBetween(76.0, 84.0);
    double mean = 0;
    for (int e : regular) {
      mean += Math.log(Math.max(1, e));
    }
    mean /= regular.size();
    double var = 0;
    for (int e : regular) {
      double d = Math.log(Math.max(1, e)) - mean;
      var += d * d;
    }
    double sigma = Math.sqrt(var / regular.size());
    // The tail above 2 s is cut from the regular part, which lowers the sample sigma slightly.
    assertThat(sigma).isBetween(0.94, 1.02);
    double outlierShare = (double) f.outliers / f.rootHttp;
    // 1 % outliers plus the log-normal tail above 2 s (about 0.06 %).
    assertThat(outlierShare).isBetween(0.0090, 0.0125);
    assertThat(Collections.max(f.rootHttpElapsed)).isLessThanOrEqualTo(f.config.outlierMaxMs);
    assertThat(f.timedOutCount).isPositive();
  }

  @Test
  @DisplayName("kind BATCH 3 %, errors 1 % over at most 50 groups, children from internal calls")
  void kindsAndErrors() {
    assertThat((double) f.batch / f.xlogRows).isCloseTo(0.03, within(0.003));
    assertThat((double) f.errors / f.xlogRows).isCloseTo(0.01, within(0.0015));
    assertThat(f.errorGroups).hasSizeLessThanOrEqualTo(50).hasSizeGreaterThan(30);
    // Internal calls: 8 % of HTTP roots of apps with a callee (all apps here).
    assertThat((double) f.children / f.rootHttp).isCloseTo(0.08, within(0.006));
    // Total rate = TPS (roots + children + batch).
    assertThat((double) f.xlogRows)
        .isCloseTo(f.config.tps * 600, within(f.config.tps * 600 * 0.02));
  }

  @Test
  @DisplayName("user_id 30 % (hashed), request_id 50 %, masked client addresses (<= 10,000)")
  void privacyFields() {
    assertThat((double) f.rootHttpWithUser / f.rootHttp).isCloseTo(0.30, within(0.01));
    assertThat((double) f.rootHttpWithRequest / f.rootHttp).isCloseTo(0.50, within(0.01));
    assertThat(f.remoteIps).hasSizeLessThanOrEqualTo(10_000).hasSizeGreaterThan(3_000);
    for (int ip : f.remoteIps) {
      assertThat(ip & 0xFF).as("masked /24").isZero();
    }
  }

  @Test
  @DisplayName("sql_ids: mean 3 distinct statements per HTTP root transaction")
  void sqlIds() {
    assertThat((double) f.rootHttpSqlIds / f.rootHttp).isCloseTo(3.0, within(0.12));
  }

  @Test
  @DisplayName("Profile capture: errors + slow + 10 % (about 12 %), about 14 steps per capture")
  void profileCapture() {
    assertThat((double) f.captured / f.xlogRows).isBetween(0.11, 0.14);
    double steps = (double) f.profileRows / f.profileByTx.size();
    assertThat(steps).isBetween(10.0, 20.0);
  }

  @Test
  @DisplayName("Instances: clock skew within +/-2 s, two instances at +60 s")
  void clockSkew() {
    Topology t = f.topology;
    assertThat(t.skewedInstances()).hasSize(2);
    for (Topology.Instance i : t.instances) {
      if (i.skewed) {
        assertThat(i.skewMs).isBetween(58_000L, 62_000L);
      } else {
        assertThat(Math.abs(i.skewMs)).isLessThanOrEqualTo(2_000L);
      }
    }
  }

  @Test
  @DisplayName("Late arrivals: about 0.5 % of rows, delayed 1-30 min, flagged late")
  void lateArrivals() {
    double share = (double) f.late / f.xlogRows;
    assertThat(share).isBetween(0.003, 0.008);
    assertThat(f.manifest.lateBatches()).isPositive();
  }
}
