/* SPDX-License-Identifier: Apache-2.0 */

/**
 * The stack and the probes of the server load run ({@code serverLoadTest}, 18 §8, WO-29):
 *
 * <ul>
 *   <li>{@link io.argos.server.testing.load.LoadServerJvm}: the boot jar as a child JVM with the 17
 *       §7.2 options, native memory tracking, a JFR recording of pinned virtual threads and a
 *       metrics token generated at run time;
 *   <li>{@link io.argos.server.testing.load.MetricsScraper}, {@link
 *       io.argos.server.testing.load.PrometheusText} and {@link
 *       io.argos.server.testing.load.CumulativeHistogram}: {@code /actuator/prometheus} scrapes,
 *       histogram differences and interpolated quantiles;
 *   <li>{@link io.argos.server.testing.load.ClickHouseProbe}: parts, {@code DelayedInserts}, memory
 *       and the N-18 settings of ClickHouse;
 *   <li>{@link io.argos.server.testing.load.ResidentSetProbe}, {@link
 *       io.argos.server.testing.load.DockerContainers}, {@link io.argos.server.testing.load.Jcmd}
 *       and {@link io.argos.server.testing.load.JfrPinning}: the server's resident set, the
 *       containers' memory and OOM kills, native memory, class histograms and pinned events.
 * </ul>
 *
 * <p>No JUnit dependency: the runner in {@code test/load/fake-agents} uses these classes. Nothing
 * here logs a token or a password.
 *
 * <p>Owner: WO-29.
 */
package io.argos.server.testing.load;
