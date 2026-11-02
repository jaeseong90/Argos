/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.launcher;

import java.util.ArrayList;
import java.util.List;

/**
 * JVM option presets of sample app runs (18 §5.10, 19 §11, ADR-0004 C-27).
 *
 * <ul>
 *   <li>{@link #bytecodeVerification(int)}: {@code -XX:+BytecodeVerificationLocal} so that the JVM
 *       also verifies the JDK classes the agent transforms (T-AG-006). It is a product flag on JDK
 *       8 and 11 and a diagnostic flag from JDK 17 on (checked: 17, 21 and 25 reject it without
 *       {@code -XX:+UnlockDiagnosticVMOptions} before it).
 *   <li>{@link #unsafeDeny(int)}: {@code --sun-misc-unsafe-memory-access=deny} on JDK 25 (18 §5.10,
 *       F-A35): any {@code sun.misc.Unsafe} memory access of the agent fails the run.
 *   <li>{@link #NO_PERF_DATA}: keeps the JVM's {@code hsperfdata} file out of the temp directory
 *       (T-BLD-006 watches it).
 * </ul>
 */
public final class JvmOptions {
  public static final String NO_PERF_DATA = "-XX:-UsePerfData";
  public static final String UNLOCK_DIAGNOSTIC = "-XX:+UnlockDiagnosticVMOptions";
  public static final String VERIFY_LOCAL = "-XX:+BytecodeVerificationLocal";
  public static final String UNSAFE_DENY = "--sun-misc-unsafe-memory-access=deny";

  /** First JDK on which {@link #VERIFY_LOCAL} is a diagnostic flag (11 accepts it plain). */
  static final int DIAGNOSTIC_VERIFY_FROM = 17;

  /** First JDK whose runs add {@link #UNSAFE_DENY} by default (18 §5.10). */
  static final int UNSAFE_DENY_FROM = 25;

  private JvmOptions() {}

  /** C-27 verification of transformed JDK classes on {@code jdk}. */
  public static List<String> bytecodeVerification(int jdk) {
    return jdk >= DIAGNOSTIC_VERIFY_FROM
        ? List.of(UNLOCK_DIAGNOSTIC, VERIFY_LOCAL)
        : List.of(VERIFY_LOCAL);
  }

  /** {@link #UNSAFE_DENY} on JDK 25 and later, nothing before. */
  public static List<String> unsafeDeny(int jdk) {
    return jdk >= UNSAFE_DENY_FROM ? List.of(UNSAFE_DENY) : List.of();
  }

  /**
   * The default options of every harness run on {@code jdk}: no perf data file, C-27 verification
   * (unless {@code verification} is false) and the JDK 25 Unsafe deny.
   */
  public static List<String> defaults(int jdk, boolean verification) {
    List<String> options = new ArrayList<>();
    options.add(NO_PERF_DATA);
    if (verification) {
      options.addAll(bytecodeVerification(jdk));
    }
    options.addAll(unsafeDeny(jdk));
    return options;
  }

  /** The {@code -XX:NativeMemoryTracking=summary} option of NMT runs (18 §5.9 "네이티브"). */
  public static String nativeMemoryTracking() {
    return "-XX:NativeMemoryTracking=summary";
  }
}
