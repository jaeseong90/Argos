/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.storage.generator.model.Topology;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Parameters, presets, spans and the default time range. */
class GeneratorConfigTest {

  private static final long DAY = GeneratorConfig.DAY_MS;

  @Test
  @DisplayName("Defaults are the 13 §15 distributions at full scale")
  void defaults() {
    GeneratorConfig c = GeneratorConfig.defaults();
    assertThat(c.apps).isEqualTo(10);
    assertThat(c.prodApps).isEqualTo(7);
    assertThat(c.instances()).isEqualTo(50);
    assertThat(c.services).isEqualTo(100);
    assertThat(c.serviceZipf).isEqualTo(1.1);
    assertThat(c.sqls).isEqualTo(2000);
    assertThat(c.extHosts).isEqualTo(20);
    assertThat(c.versions).isEqualTo(3);
    assertThat(c.batchShare).isEqualTo(0.03);
    assertThat(c.errorRate).isEqualTo(0.01);
    assertThat(c.errorGroups).isEqualTo(50);
    assertThat(c.latencyMedianMs).isEqualTo(80);
    assertThat(c.latencySigma).isEqualTo(1.0);
    assertThat(c.outlierRate).isEqualTo(0.01);
    assertThat(c.userIdShare).isEqualTo(0.3);
    assertThat(c.remoteIps).isEqualTo(10_000);
    assertThat(c.requestIdShare).isEqualTo(0.5);
    assertThat(c.lateRate).isEqualTo(0.005);
    assertThat(c.duplicateRate).isEqualTo(0.001);
    assertThat(c.sqlIdsMean).isEqualTo(3);
    assertThat(c.tpsSteps).containsExactly(500.0, 2000.0, 5000.0);
    assertThat(c.flushIntervalMs).isEqualTo(2000);
    assertThat(c.flushRows).isEqualTo(50_000);
    assertThat(c.xlogSpanMs()).isEqualTo(30 * DAY);
    assertThat(c.profileSpanMs()).isEqualTo(7 * DAY);
    assertThat(c.metricSpanMs()).isEqualTo(14 * DAY);
    // Options outside the 13 §15 shape are off by default; 1 s simulation tick.
    assertThat(c.activeSqls).isZero();
    assertThat(c.profileStepRate).isZero();
    assertThat(c.tickMs).isEqualTo(1000);
  }

  @Test
  @DisplayName("N-06 load options and tick-ms parse; a tick must divide 1 s")
  void loadOptions() {
    GeneratorConfig n06 =
        GeneratorConfig.parse(
            List.of(
                "--instances-per-app=30",
                "--active-sqls=200",
                "--profile-step-rate=50000",
                "--tick-ms=100"));
    assertThat(n06.instances()).isEqualTo(300);
    assertThat(n06.activeSqls).isEqualTo(200);
    assertThat(n06.profileStepRate).isEqualTo(50_000);
    assertThat(n06.tickMs).isEqualTo(100);
    for (String bad : List.of("0", "5", "300", "2000", "-100")) {
      assertThatThrownBy(() -> GeneratorConfig.parse(List.of("--tick-ms=" + bad)))
          .as("tick-ms=%s", bad)
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> GeneratorConfig.parse(List.of("--active-sqls=-1")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> GeneratorConfig.parse(List.of("--profile-step-rate=-1")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("scale shrinks the time span only; the ci profile is 1/100")
  void scale() {
    GeneratorConfig ci = GeneratorConfig.parse(List.of("--profile=ci"));
    assertThat(ci.scale).isEqualTo(0.01);
    assertThat(ci.tps).isEqualTo(500);
    assertThat(ci.xlogSpanMs()).isEqualTo(Math.round(0.3 * DAY));
    assertThat(ci.profileSpanMs()).isEqualTo(Math.round(0.07 * DAY));
    assertThat(ci.metricSpanMs()).isEqualTo(Math.round(0.14 * DAY));
    // Expected volumes at 1/100: xlog 13 M, metric 12 M (13 §15 / 100).
    double xlog = ci.tps * ci.xlogSpanMs() / 1000.0;
    double metric = ci.instances() * ci.metricSeries * (ci.metricSpanMs() / 5000.0);
    assertThat(xlog).isEqualTo(12_960_000.0);
    assertThat(metric).isEqualTo(12_096_000.0);
    GeneratorConfig explicit = GeneratorConfig.parse(List.of("--profile=ci", "--scale=0.05"));
    assertThat(explicit.scale).isEqualTo(0.05);
  }

  @Test
  @DisplayName("Unknown keys and invalid values are rejected")
  void validation() {
    assertThatThrownBy(() -> GeneratorConfig.parse(List.of("--nope=1")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown parameter");
    assertThatThrownBy(() -> GeneratorConfig.parse(List.of("--scale=2")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> GeneratorConfig.parse(List.of("tps=1")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> GeneratorConfig.parse(List.of("--error-rate=1.5")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> GeneratorConfig.parse(List.of("--profile=huge")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("Default range: recent, crosses a UTC midnight, inside every retention")
  void defaultRange() {
    long now = Instant.parse("2026-10-24T15:17:42Z").toEpochMilli();
    GeneratorConfig ci = GeneratorConfig.parse(List.of("--profile=ci"));
    RunRange r = RunRange.bulk(ci, now);
    long midnight = Instant.parse("2026-10-24T00:00:00Z").toEpochMilli();
    assertThat(r.start()).isLessThan(midnight);
    assertThat(r.end()).isGreaterThan(midnight).isLessThanOrEqualTo(now);
    assertThat(r.end() - r.start()).isEqualTo(ci.xlogSpanMs());
    assertThat(r.profileFrom()).isEqualTo(r.end() - ci.profileSpanMs());
    RunRange full = RunRange.bulk(GeneratorConfig.defaults(), now);
    assertThat(full.end()).isLessThanOrEqualTo(now);
    assertThat(now - full.start()).isLessThanOrEqualTo(30 * DAY + 3_600_000L);
    // Version switch on day 15 of 30, deploy at 09:00 of that day.
    assertThat(full.versionSwitchAt() - full.start()).isEqualTo(14 * DAY);
    assertThat(full.deployAt() - full.versionSwitchAt()).isEqualTo(9 * 3_600_000L);
  }

  @Test
  @DisplayName(
      "Topology: 10 apps (7 prod), 50 instances, 100+10 services, 2,000 SQL, 20 hosts, 50 groups")
  void topology() {
    GeneratorConfig c = GeneratorConfig.defaults();
    long now = Instant.parse("2026-10-24T00:00:00Z").toEpochMilli();
    Topology t = Topology.build(c, RunRange.bulk(c, now));
    assertThat(t.apps).hasSize(10);
    assertThat(t.apps.stream().filter(a -> a.env.equals("prod"))).hasSize(7);
    assertThat(t.instances).hasSize(50);
    assertThat(t.httpServices).hasSize(100);
    assertThat(t.apps.stream().mapToInt(a -> a.sqls.size()).sum()).isEqualTo(2000);
    assertThat(t.hosts).hasSize(20);
    assertThat(t.hosts.stream().filter(h -> h.internal)).hasSize(10);
    assertThat(t.apps.stream().mapToInt(a -> a.errorGroups.size()).sum()).isEqualTo(50);
    assertThat(t.apps.stream().mapToDouble(a -> a.httpShare).sum())
        .isCloseTo(1.0, org.assertj.core.api.Assertions.within(1e-9));
    assertThat(t.apps)
        .allSatisfy(
            a -> {
              assertThat(a.appVersionIds).hasSize(3).doesNotHaveDuplicates();
              assertThat(a.callee).isNotNull();
              assertThat(a.callee.env).isEqualTo(a.env);
            });
    assertThat(t.instances.stream().map(i -> i.id).distinct()).hasSize(50);
    assertThat(t.unmeasuredInstances()).hasSize(Topology.CPU_UNMEASURED_INSTANCES);
  }
}
