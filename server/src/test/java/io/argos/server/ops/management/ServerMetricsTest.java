/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.management;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.bootstrap.StartupPhase;
import io.argos.server.bootstrap.StartupSequence;
import io.argos.server.config.ServerBuild;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.health.contributor.Status;

/** Build info gauge, info contributor, readiness contributor and the meter naming rule. */
@Tag("req:F-S20")
class ServerMetricsTest {

  private final ServerBuild build = new ServerBuild("1.2.3", "abc1234", "1");

  @Test
  @DisplayName("T-SV-047 argos_build_info{version,commit} 1 in the Prometheus format")
  void buildInfo() {
    PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    registry.config().commonTags("application", "argos-server");
    new BuildInfoMetrics(build).bindTo(registry);

    String scrape = registry.scrape();
    assertThat(scrape)
        .containsPattern(
            "(?m)^argos_build_info\\{application=\"argos-server\",commit=\"abc1234\",version=\"1\\.2\\.3\"} 1(\\.0)?$");
    for (Meter meter : registry.getMeters()) {
      assertThat(MeterNames.violation(meter.getId())).as(meter.getId().getName()).isEmpty();
    }
  }

  @Test
  @DisplayName("T-SV-047 the naming rule: prefix argos., lower case, no explicit total")
  void namingRule() {
    SimpleMeterRegistry r = new SimpleMeterRegistry();
    assertThat(
            MeterNames.violation(Counter.builder("argos.collector.received").register(r).getId()))
        .isEmpty();
    assertThat(MeterNames.violation(Timer.builder("argos.ingest.lag").register(r).getId()))
        .isEmpty();
    assertThat(MeterNames.violation(Counter.builder("collector.received").register(r).getId()))
        .hasValueSatisfying(v -> assertThat(v).contains("prefix argos."));
    assertThat(MeterNames.violation(Counter.builder("argos.Sessions").register(r).getId()))
        .isPresent();
    assertThat(MeterNames.violation(Counter.builder("argos.writer.rows.total").register(r).getId()))
        .hasValueSatisfying(v -> assertThat(v).contains("total"));
  }

  @Test
  @DisplayName("/actuator/info shows version, commit and protocol only")
  void info() {
    Info.Builder builder = new Info.Builder();
    new ArgosInfoContributor(build).contribute(builder);
    assertThat(builder.build().getDetails())
        .containsOnlyKeys("version", "commit", "protocol")
        .containsEntry("version", "1.2.3")
        .containsEntry("commit", "abc1234");
  }

  @Test
  @DisplayName("readiness contributor: DOWN until the start sequence has completed, then UP")
  void migrationsIndicator() {
    StartupPhase step =
        new StartupPhase() {
          @Override
          public io.argos.server.bootstrap.PhaseOrder order() {
            return io.argos.server.bootstrap.PhaseOrder.CLICKHOUSE_MIGRATION;
          }

          @Override
          public void run() {}
        };
    StartupSequence sequence =
        new StartupSequence(
            new org.springframework.beans.factory.support.StaticListableBeanFactory(
                    java.util.Map.of("step", step))
                .getBeanProvider(StartupPhase.class));
    MigrationsHealthIndicator indicator = new MigrationsHealthIndicator(sequence);
    assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    sequence.run();
    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    assertThat(indicator.health().getDetails()).isEmpty();
    assertThat(List.of(sequence.results().get(0).phase()))
        .containsExactly(io.argos.server.bootstrap.PhaseOrder.CLICKHOUSE_MIGRATION);
  }
}
