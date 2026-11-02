/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

/**
 * Per-thread nesting counters (10 §3, D-24): advice records only the outermost call of a nested
 * chain (JDBC wrappers, delegating clients). Keys are the small constants below.
 *
 * <p>Usage in advice: {@code if (CallDepth.enter(KEY) != 1) ...} in enter and {@code
 * CallDepth.exit(KEY)} in exit, both unconditionally paired. Works on virtual threads too
 * (ThreadLocal).
 */
public final class CallDepth {
  public static final int JDBC_STATEMENT = 0;
  public static final int JDBC_CONNECTION = 1;
  public static final int JDBC_DATASOURCE = 2;
  public static final int HTTP_CLIENT = 3;
  public static final int SERVLET = 4;
  public static final int EXECUTOR = 5;
  public static final int LOGGING = 6;

  /** Number of keys (slots per thread). */
  public static final int KEYS = 8;

  private static final ThreadLocal<int[]> DEPTHS =
      new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
          return new int[KEYS];
        }
      };

  private CallDepth() {}

  /** Increments the counter of {@code key}; returns the new depth (1 = outermost call). */
  public static int enter(int key) {
    if (key < 0 || key >= KEYS) {
      return 0;
    }
    int[] depths = DEPTHS.get();
    return ++depths[key];
  }

  /** Decrements the counter of {@code key} (never below 0); returns the new depth. */
  public static int exit(int key) {
    if (key < 0 || key >= KEYS) {
      return 0;
    }
    int[] depths = DEPTHS.get();
    int depth = depths[key] - 1;
    if (depth < 0) {
      depth = 0;
    }
    depths[key] = depth;
    return depth;
  }

  /** The current depth of {@code key} on this thread. */
  public static int depth(int key) {
    if (key < 0 || key >= KEYS) {
      return 0;
    }
    return DEPTHS.get()[key];
  }
}
