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
 * io.argos.agent.core.metric.MetricDiagnostics}. The 5-second collector and the {@code MetricBatch}
 * transport are WO-16's.
 */
package io.argos.agent.core.metric;
