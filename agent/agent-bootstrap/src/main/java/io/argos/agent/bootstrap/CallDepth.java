/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

/**
 * Per-thread nesting counters (10 §3, D-24): advice records only the outermost call of a nested
 * chain (JDBC wrappers, delegating clients). Keys are the small constants below.
 *
 * <p>Usage in advice: {@code if (CallDepth.enter(KEY) != 1) ...} in enter and {@code
 * CallDepth.exit(KEY)} in exit, both unconditionally paired. Works on virtual threads too
 * (ThreadLocal).
 *
 * <p>Each key also has one {@code long} value slot per thread ({@link #value}, {@link #setValue}):
 * a nested call hands a fact up to the outermost call of the same chain without a second
 * thread-local, for example the pool name a nested {@code DataSource.getConnection()} knows (10
 * §6.3, WO-13). The outermost call resets the slot when it starts. Addition to 10 §3 (WO-13).
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

  /** Per thread: the depths at {@code [key]}, the value slots at {@code [KEYS + key]}. */
  private static final ThreadLocal<long[]> STATE =
      new ThreadLocal<long[]>() {
        @Override
        protected long[] initialValue() {
          return new long[2 * KEYS];
        }
      };

  private CallDepth() {}

  /** Increments the counter of {@code key}; returns the new depth (1 = outermost call). */
  public static int enter(int key) {
    if (key < 0 || key >= KEYS) {
      return 0;
    }
    long[] state = STATE.get();
    return (int) ++state[key];
  }

  /** Decrements the counter of {@code key} (never below 0); returns the new depth. */
  public static int exit(int key) {
    if (key < 0 || key >= KEYS) {
      return 0;
    }
    long[] state = STATE.get();
    long depth = state[key] - 1;
    if (depth < 0) {
      depth = 0;
    }
    state[key] = depth;
    return (int) depth;
  }

  /** The current depth of {@code key} on this thread. */
  public static int depth(int key) {
    if (key < 0 || key >= KEYS) {
      return 0;
    }
    return (int) STATE.get()[key];
  }

  /**
   * True when the depths of the three JDBC keys ({@link #JDBC_STATEMENT}, {@link #JDBC_CONNECTION},
   * {@link #JDBC_DATASOURCE}) are all 0 on this thread: a JDBC call made now is the application's
   * own, not one a driver, a pool or the agent makes inside another JDBC call (10 §6.3, D-24). One
   * thread-local read.
   */
  public static boolean outsideJdbc() {
    long[] state = STATE.get();
    return state[JDBC_STATEMENT] == 0 && state[JDBC_CONNECTION] == 0 && state[JDBC_DATASOURCE] == 0;
  }

  /** The value slot of {@code key} on this thread (0 when never set or after a reset). */
  public static long value(int key) {
    if (key < 0 || key >= KEYS) {
      return 0;
    }
    return STATE.get()[KEYS + key];
  }

  /** Sets the value slot of {@code key} on this thread. */
  public static void setValue(int key, long value) {
    if (key < 0 || key >= KEYS) {
      return;
    }
    STATE.get()[KEYS + key] = value;
  }
}
