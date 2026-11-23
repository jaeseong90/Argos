/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.metric;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.trace.TraceRuntime;
import io.argos.proto.v1.MetricBatch;
import io.argos.proto.v1.MetricSample;
import java.sql.Connection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@link MetricCollector} (10 §11, F-A09-F-A11): one batch per grid point with the JVM, pool,
 * Tomcat, system and agent series; pool series from the registry getters (max = the configured
 * maximum, JMX off), connection waits per pool (13 §3.5, C-68), the registry counters (ADR-0010
 * C-185 (4)), the agent's own counters with deltas, counter sources, and a collection that never
 * throws.
 */
@Tag("req:F-A09")
@Tag("req:F-A10")
@Tag("req:T-AG-062")
class MetricCollectorTest {

  private static Map<String, MetricSample> byName(MetricBatch batch) {
    return CgroupReaderTest.byName(batch);
  }

  @Test
  @DisplayName(
      "T-AG-062 pool, Tomcat and wait series from the registries: jdbc_pool_max = the configured 7,"
          + " active follows a borrowed connection, tomcat max threads 17 and connections 123,"
          + " waits 4 and 1.5 s; every sample on the grid point")
  void poolsAndConnectors() throws Exception {
    try (CollectorScenario s = new CollectorScenario()) {
      Map<String, MetricSample> v;
      try (Connection held = s.dataSource.getConnection()) {
        v = byName(s.collector.collect(1_700_000_005_000L, 1L));
        assertThat(held.isValid(1)).isTrue();
      }
      assertThat(v.get("jdbc_pool_max{pool=\"orders-pool\"}").getValue()).isEqualTo(7);
      assertThat(v.get("jdbc_pool_active{pool=\"orders-pool\"}").getValue()).isEqualTo(1);
      assertThat(v.get("jdbc_pool_idle{pool=\"orders-pool\"}").getValue()).isNotNegative();
      assertThat(v.get("jdbc_pool_waiting{pool=\"orders-pool\"}").getValue()).isZero();
      assertThat(v.get("tomcat_threads_max{connector=\"http-nio-18081\"}").getValue())
          .isEqualTo(17);
      assertThat(v.get("tomcat_threads_current{connector=\"http-nio-18081\"}").getValue())
          .isEqualTo(2);
      assertThat(v.get("tomcat_connections_max{connector=\"http-nio-18081\"}").getValue())
          .isEqualTo(123);
      assertThat(v).containsKey("tomcat_threads_busy{connector=\"http-nio-18081\"}");
      assertThat(v.get("jdbc_pool_wait_count_total{pool=\"orders-pool\"}").getValue()).isEqualTo(4);
      assertThat(v.get("jdbc_pool_wait_seconds_total{pool=\"orders-pool\"}").getValue())
          .isEqualTo(1.5);
      assertThat(v.values()).allMatch(sample -> sample.getTsMs() == 1_700_000_005_000L);
      assertThat(s.collector.cgroupMode()).isEqualTo(CgroupReader.V2);
    }
  }

  @Test
  @DisplayName(
      "C-185 (4) and 10 §11 agent self metrics: registry overflow and getter errors per registry,"
          + " agent counters with deltas, queue gauges, agent CPU, series dropped; <= 500 series")
  void selfMetrics() throws Exception {
    try (CollectorScenario s = new CollectorScenario()) {
      MetricBatch first = s.collector.collect(5_000, 1L);
      s.counters.incIgnored("path");
      s.counters.incIgnored("path");
      s.counters.incSqlOutsideTx();
      Map<String, MetricSample> v = byName(s.collector.collect(10_000, 2L));
      assertThat(first.getSeriesCount()).isLessThanOrEqualTo(500);
      assertThat(v)
          .containsKeys(
              "argos_agent_registry_overflow_total{registry=\"pool\"}",
              "argos_agent_registry_getter_errors_total{registry=\"pool\"}",
              "argos_agent_registry_overflow_total{registry=\"tomcat\"}",
              "argos_agent_registry_getter_errors_total{registry=\"tomcat\"}",
              "argos_agent_queue_size{queue=\"transaction\"}",
              "argos_agent_heap_bytes_estimated",
              "argos_agent_transport_up",
              "argos_agent_cpu_seconds_total",
              MetricRegistry.SERIES_DROPPED);
      assertThat(v.get("argos_agent_ignored_total{reason=\"path\"}").getValue()).isEqualTo(2);
      assertThat(v.get("argos_agent_ignored_total{reason=\"path\"}").getDelta()).isEqualTo(2);
      assertThat(v.get("argos_agent_sql_outside_tx_total").getDelta()).isEqualTo(1);
      assertThat(v.get("argos_agent_dropped_total{queue=\"metric\"}").getDelta()).isZero();
      assertThat(v.get(MetricRegistry.SERIES_DROPPED).getValue()).isZero();
    }
  }

  @Test
  @DisplayName(
      "counter sources add self metrics (_total = counter, else gauge); a failing source or pool"
          + " wait source never fails the collection")
  void sourcesAndFailures() {
    TraceRuntime runtime =
        TraceRuntime.create(
            AgentConfig.of(Collections.<String, String>emptyMap()), new AgentCounters());
    PoolWaitSource broken =
        () -> {
          throw new IllegalStateException("boom");
        };
    MetricCollector collector =
        new MetricCollector(
            500,
            runtime,
            broken,
            CgroupReaderTest.fixture("none"),
            false,
            OsBean.NONE,
            new GcNameMapper(() -> {}));
    Map<String, Long> stats = new LinkedHashMap<String, Long>();
    stats.put("argos_agent_stats_overflow_total{kind=\"sql\"}", 3L);
    stats.put("argos_agent_test_gauge", 9L);
    assertThat(collector.addCounterSource(() -> stats)).isTrue();
    assertThat(
            collector.addCounterSource(
                () -> {
                  throw new IllegalStateException("boom");
                }))
        .isTrue();
    MetricBatch batch = collector.collect(5_000, 1L);
    Map<String, MetricSample> v = byName(batch);
    assertThat(v.get("argos_agent_stats_overflow_total{kind=\"sql\"}").getDelta()).isEqualTo(3);
    assertThat(v.get("argos_agent_test_gauge").getDelta()).isZero();
    assertThat(v).containsKey("jvm_threads_live");
    assertThat(v.keySet()).noneMatch(name -> name.startsWith("jdbc_pool_wait"));
    assertThat(collector.cgroupMode()).isEqualTo(CgroupReader.NONE);
    Map<String, Integer> kinds = new HashMap<String, Integer>();
    batch.getSeriesList().forEach(series -> kinds.put(series.getName(), series.getKindValue()));
    assertThat(kinds.get("argos_agent_test_gauge")).isEqualTo(1);
    assertThat(kinds.get("argos_agent_stats_overflow_total{kind=\"sql\"}")).isEqualTo(2);
    for (int i = 2; i < MetricCollector.MAX_COUNTER_SOURCES; i++) {
      collector.addCounterSource(() -> stats);
    }
    assertThat(collector.addCounterSource(() -> stats)).as("bounded").isFalse();
  }

  @Test
  @DisplayName("create(): metrics.max-series from the configuration, the machine's cgroup mode")
  void create() {
    Map<String, String> values = new HashMap<String, String>();
    values.put("metrics.max-series", "3");
    MetricCollector collector = MetricCollector.create(AgentConfig.of(values), null, null);
    assertThat(collector.registry().maxSeries()).isEqualTo(3);
    assertThat(collector.cgroupMode()).isEqualTo(CgroupReader.systemMode());
    MetricBatch batch = collector.collect(5_000);
    assertThat(batch.getSeriesCount()).isEqualTo(3);
    assertThat(byName(batch).get(MetricRegistry.SERIES_DROPPED).getValue()).isPositive();
  }
}
