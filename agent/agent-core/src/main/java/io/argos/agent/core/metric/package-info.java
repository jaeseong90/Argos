/* SPDX-License-Identifier: Apache-2.0 */
/**
 * Metric collectors (10 §11, F-A09-F-A11): JVM, GC name mapping, pools, Tomcat, system and cgroup
 * metrics, the metric registry with its 500-series cap and the active snapshot.
 *
 * <p>WO-13 adds the sources of the pool and Tomcat series (10 §6.4, DSN-10-12: getters only, never
 * JMX): {@link io.argos.agent.core.metric.PoolRegistry} and {@link
 * io.argos.agent.core.metric.TomcatRegistry}, weak and bounded, filled through {@code
 * Tracer.registerPool}/{@code registerConnector} and read with {@code snapshot()}; their values
 * reach the development self-diagnostics file through {@link
 * io.argos.agent.core.metric.MetricDiagnostics}.
 *
 * <p>WO-16 adds the 5-second collector: {@link io.argos.agent.core.metric.MetricCollector} (one
 * {@code MetricBatch} per grid point, called by {@code argos-metrics}) over {@link
 * io.argos.agent.core.metric.MetricRegistry} (series, counter deltas with reset correction, the
 * cap), {@code JvmMetrics} with {@link io.argos.agent.core.metric.GcNameMapper}, {@code
 * SystemMetrics} with {@link io.argos.agent.core.metric.CgroupReader}, the pool and Tomcat
 * registries, the connection waits of a {@link io.argos.agent.core.metric.PoolWaitSource} and the
 * agent's own counters ({@link io.argos.agent.core.metric.CounterSource} for more of them). The
 * metric names are a public contract (snapshot {@code metric-names.txt} of the unit tests).
 */
package io.argos.agent.core.metric;
