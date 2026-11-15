/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.transport;

/**
 * Netty system properties of 10 §10.3 and ADR-0005 decision 7, under the names the shaded Netty
 * reads (ADR-0003 C-23 policy RELOCATED, ADR-0008): {@code io.argos.agent.shaded.io.netty.*}. The
 * agent never sets the original {@code io.netty.*} names, so an application's own Netty keeps its
 * configuration, and an application's {@code -Dio.netty.*} never changes the agent's Netty.
 *
 * <p>The constants are written with the relocation prefix on purpose: Shadow rewrites string
 * constants that start with a relocated package ({@code io.netty.}) in every class it shades, the
 * agent's own classes included, so a plain {@code "io.netty.noUnsafe"} literal would end up
 * relocated anyway; the explicit form keeps the source and the jar identical. There is no separate
 * prefix constant: every relocated-namespace constant of this class is listed in {@code
 * AgentShading.OWN_RELOCATED_LITERALS} (checkOwnRelocatedLiterals). In unit tests, where Netty is
 * not relocated, these names have no effect.
 *
 * <p>{@link #apply()} runs on {@code argos-sender} before the first Netty class is initialised
 * ({@code PlatformDependent} reads them once, in its static initialiser).
 */
final class NettyProps {
  static final String NO_UNSAFE = "io.argos.agent.shaded.io.netty.noUnsafe";
  static final String ALLOCATOR_TYPE = "io.argos.agent.shaded.io.netty.allocator.type";
  static final String NO_PREFER_DIRECT = "io.argos.agent.shaded.io.netty.noPreferDirect";
  static final String MAX_DIRECT_MEMORY = "io.argos.agent.shaded.io.netty.maxDirectMemory";
  static final String TRY_REFLECTION_SET_ACCESSIBLE =
      "io.argos.agent.shaded.io.netty.tryReflectionSetAccessible";
  static final String EVENT_LOOP_THREADS = "io.argos.agent.shaded.io.netty.eventLoopThreads";
  static final String NUM_DIRECT_ARENAS =
      "io.argos.agent.shaded.io.netty.allocator.numDirectArenas";

  // ADR-0005 decision 7 (P-3 additions): no leak detector, no object pools, no reflective selector
  // rewrite; all three only matter for high-throughput servers and the last one uses reflection.
  static final String LEAK_DETECTION_LEVEL = "io.argos.agent.shaded.io.netty.leakDetection.level";
  static final String RECYCLER_MAX_CAPACITY =
      "io.argos.agent.shaded.io.netty.recycler.maxCapacityPerThread";
  static final String NO_KEY_SET_OPTIMIZATION =
      "io.argos.agent.shaded.io.netty.noKeySetOptimization";

  /** The 8 MiB direct memory cap of 10 §10.3 (N-26). */
  static final long MAX_DIRECT_MEMORY_BYTES = 8L * 1024 * 1024;

  /** Name and value pairs: 10 §10.3 first, then the ADR-0005 decision 7 additions. */
  static final String[][] VALUES = {
    {NO_UNSAFE, "true"},
    {ALLOCATOR_TYPE, "unpooled"},
    {NO_PREFER_DIRECT, "true"},
    {MAX_DIRECT_MEMORY, Long.toString(MAX_DIRECT_MEMORY_BYTES)},
    {TRY_REFLECTION_SET_ACCESSIBLE, "false"},
    {EVENT_LOOP_THREADS, "1"},
    {NUM_DIRECT_ARENAS, "0"},
    {LEAK_DETECTION_LEVEL, "disabled"},
    {RECYCLER_MAX_CAPACITY, "0"},
    {NO_KEY_SET_OPTIMIZATION, "true"},
  };

  private NettyProps() {}

  /**
   * Sets every property the operator did not set (an operator's {@code
   * -Dio.argos.agent.shaded.io.netty.<name>} wins, ADR-0005 decision 7); returns how many were set.
   */
  static int apply() {
    int set = 0;
    for (String[] entry : VALUES) {
      if (System.getProperty(entry[0]) == null) {
        System.setProperty(entry[0], entry[1]);
        set++;
      }
    }
    return set;
  }
}
