/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.transportpoc.grpcnetty;

/**
 * Netty system properties of 10 §10.3, under the names the shaded Netty reads (ADR-0003 C-23 policy
 * RELOCATED, ADR-0008): {@code io.argos.agent.shaded.io.netty.*}. The agent never sets the original
 * {@code io.netty.*} names, so the application's own Netty keeps its configuration and an
 * application's {@code -Dio.netty.*} does not change the agent's Netty.
 *
 * <p>The constants are written with the relocation prefix on purpose: Shadow rewrites string
 * constants that start with a relocated package ({@code io.netty.}) in every class it shades,
 * including the agent's own classes, so a plain {@code "io.netty.noUnsafe"} literal would end up
 * relocated as well; the explicit form keeps the source and the jar identical.
 */
final class NettyProps {
  static final String PREFIX = "io.argos.agent.shaded.";

  static final String NO_UNSAFE = PREFIX + "io.netty.noUnsafe";
  static final String ALLOCATOR_TYPE = PREFIX + "io.netty.allocator.type";
  static final String NO_PREFER_DIRECT = PREFIX + "io.netty.noPreferDirect";
  static final String MAX_DIRECT_MEMORY = PREFIX + "io.netty.maxDirectMemory";
  static final String TRY_REFLECTION_SET_ACCESSIBLE =
      PREFIX + "io.netty.tryReflectionSetAccessible";
  static final String EVENT_LOOP_THREADS = PREFIX + "io.netty.eventLoopThreads";
  static final String NUM_DIRECT_ARENAS = PREFIX + "io.netty.allocator.numDirectArenas";
  // Not in 10 §10.3, set by the PoC: no leak detector, no object pools, no reflective selector
  // rewrite (all three only matter for high-throughput servers).
  static final String LEAK_DETECTION_LEVEL = PREFIX + "io.netty.leakDetection.level";
  static final String RECYCLER_MAX_CAPACITY = PREFIX + "io.netty.recycler.maxCapacityPerThread";
  static final String NO_KEY_SET_OPTIMIZATION = PREFIX + "io.netty.noKeySetOptimization";
  // Netty 4.2 only: no VarHandle byte/buffer accessors. It does not affect the JCTools queues:
  // Netty 4.2 always uses its VarHandle queues (Java 11 class files, major 55) on Java 11+ when
  // Unsafe is off (PlatformDependent.hasJctoolsVarHandle() == javaVersion() >= 11).
  static final String VAR_HANDLE_ENABLED = PREFIX + "io.netty.varHandle.enabled";

  /** 10 §10.3 values (direct memory 8 MiB). */
  static final String[][] VALUES = {
    {NO_UNSAFE, "true"},
    {ALLOCATOR_TYPE, "unpooled"},
    {NO_PREFER_DIRECT, "true"},
    {MAX_DIRECT_MEMORY, "8388608"},
    {TRY_REFLECTION_SET_ACCESSIBLE, "false"},
    {EVENT_LOOP_THREADS, "1"},
    {NUM_DIRECT_ARENAS, "0"},
    {LEAK_DETECTION_LEVEL, "disabled"},
    {RECYCLER_MAX_CAPACITY, "0"},
    {NO_KEY_SET_OPTIMIZATION, "true"},
    {VAR_HANDLE_ENABLED, "false"},
  };

  private NettyProps() {}

  /** Sets every property unless the operator already set it (before any Netty class loads). */
  static void apply() {
    for (String[] entry : VALUES) {
      if (System.getProperty(entry[0]) == null) {
        System.setProperty(entry[0], entry[1]);
      }
    }
  }
}
