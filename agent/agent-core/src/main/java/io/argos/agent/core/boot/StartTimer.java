/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import java.util.concurrent.TimeUnit;

/**
 * Times the premain steps of 10 §4.3 (ADR-0009 C-79, N-30): P0-P2 (premain start to the core,
 * including the agent class loader), P3 (logger: thread, then destination), P4 (configuration), P5
 * (tracer, queues, module states), P6 (agent builder and {@code installOn}), P7 (agent threads) and
 * the end of the start sequence (P8, the banner). N-30 judges P2..P7 against {@value
 * #N30_BUDGET_MILLIS} ms; P0 and P1 take microseconds, so the P0..P7 sum is reported as that
 * figure.
 *
 * <p>With a {@link ClassCounter} (the agent class loader's defined-class count) every step also
 * gets the number of agent classes defined while it ran, so {@code debug.transform-timing} shows
 * where the start path loads classes (C-79 "측정 먼저"). Used by the premain thread only.
 */
final class StartTimer {
  /** N-30: P2..P7 at most 500 ms. */
  static final long N30_BUDGET_MILLIS = 500;

  static final int P3 = 0;
  static final int P4 = 1;
  static final int P5 = 2;
  static final int P6 = 3;
  static final int P7 = 4;
  static final int P8 = 5;

  private static final String[] NAMES = {
    "P3 logger", "P4 config", "P5 tracer", "P6 builder", "P7 threads"
  };

  /** Classes the agent class loader defined so far; negative when unknown. */
  interface ClassCounter {
    long definedClasses();
  }

  /** No class counts (outside the agent jar). */
  static final ClassCounter NO_COUNTER =
      new ClassCounter() {
        @Override
        public long definedClasses() {
          return -1;
        }
      };

  private final long premainStart;
  private final long coreStart;
  private final ClassCounter counter;
  private final long coreStartClasses;
  private final long[] spent = new long[NAMES.length];
  private final long[] classes = new long[NAMES.length];
  private int current = -1;
  private long currentStart;
  private long currentStartClasses;
  private long end = -1;

  /**
   * @param premainStartNanos {@code System.nanoTime()} at the start of premain (P0)
   * @param coreStartNanos {@code System.nanoTime()} when the core started (end of P2)
   */
  StartTimer(long premainStartNanos, long coreStartNanos) {
    this(premainStartNanos, coreStartNanos, NO_COUNTER);
  }

  /**
   * @param premainStartNanos {@code System.nanoTime()} at the start of premain (P0)
   * @param coreStartNanos {@code System.nanoTime()} when the core started (end of P2)
   * @param counter the agent class loader's defined-class count
   */
  StartTimer(long premainStartNanos, long coreStartNanos, ClassCounter counter) {
    this.premainStart = premainStartNanos;
    this.coreStart = coreStartNanos;
    this.counter = counter;
    this.currentStart = coreStartNanos;
    this.coreStartClasses = count();
    this.currentStartClasses = coreStartClasses;
  }

  private long count() {
    try {
      return counter.definedClasses();
    } catch (RuntimeException e) {
      return -1;
    }
  }

  /** Starts {@code step} (P3..P8); the time since the previous {@code begin} goes to that step. */
  void begin(int step) {
    long now = System.nanoTime();
    long defined = count();
    if (current >= 0 && current < spent.length) {
      spent[current] += now - currentStart;
      if (defined >= 0 && currentStartClasses >= 0) {
        classes[current] += defined - currentStartClasses;
      }
    }
    current = step;
    currentStart = now;
    currentStartClasses = defined;
    if (step == P8 && end < 0) {
      end = now;
    }
  }

  /**
   * Milliseconds from the start of premain to P8 (the banner's "started in", {@code premain_ms}).
   */
  double premainMillis() {
    long until = end >= 0 ? end : System.nanoTime();
    return (until - premainStart) / 1e6;
  }

  /** Milliseconds of P0..P2 (premain start to the core's first statement). */
  double bootstrapMillis() {
    return (coreStart - premainStart) / 1e6;
  }

  /** Milliseconds spent in {@code step} (P3..P7). */
  double millis(int step) {
    return spent[step] / 1e6;
  }

  /** Agent classes defined during {@code step} (P3..P7); negative when unknown. */
  long classes(int step) {
    return coreStartClasses < 0 ? -1 : classes[step];
  }

  /** Agent classes defined before the core's first statement (P2); negative when unknown. */
  long bootstrapClasses() {
    return coreStartClasses;
  }

  /** P0..P7 in milliseconds, the N-30 figure (P0 and P1 are negligible). */
  double n30Millis() {
    double total = bootstrapMillis();
    for (long nanos : spent) {
      total += nanos / 1e6;
    }
    return total;
  }

  /**
   * One line for {@code debug.transform-timing}: every step (with the agent classes it defined when
   * known), the N-30 sum and the agent class loader statistics ({@code
   * AgentControl.classLoaderStatistics()}, null when unknown).
   */
  String describe(long[] loader, int premainClasses) {
    StringBuilder out = new StringBuilder(320);
    out.append("start timing (ms): P0-P2 ").append(format(bootstrapMillis()));
    classCount(out, bootstrapClasses());
    for (int i = 0; i < NAMES.length; i++) {
      out.append(", ").append(NAMES[i]).append(' ').append(format(millis(i)));
      classCount(out, classes(i));
    }
    out.append("; P0-P7 ")
        .append(format(n30Millis()))
        .append(" (N-30 budget ")
        .append(N30_BUDGET_MILLIS)
        .append(" ms)");
    if (loader != null && loader.length >= 4) {
      out.append("; agent class loader: ")
          .append(loader[0])
          .append(" classes, ")
          .append(loader[1] / 1024)
          .append(" KiB read in ")
          .append(format(TimeUnit.NANOSECONDS.toMicros(loader[2]) / 1000.0))
          .append(" ms, defined in ")
          .append(format(TimeUnit.NANOSECONDS.toMicros(loader[3]) / 1000.0))
          .append(" ms");
    }
    if (premainClasses >= 0) {
      out.append("; ").append(premainClasses).append(" classes defined on the premain thread");
    }
    return out.toString();
  }

  private static void classCount(StringBuilder out, long count) {
    if (count >= 0) {
      out.append(" (").append(count).append(" cl)");
    }
  }

  /**
   * Milliseconds with three decimals ({@code 12.345}), without {@code java.util.Formatter} (not
   * loaded on the premain path).
   */
  static String format(double millis) {
    long micros = Math.round(millis * 1000.0);
    String sign = micros < 0 ? "-" : "";
    long abs = Math.abs(micros);
    long fraction = abs % 1000;
    return sign
        + (abs / 1000)
        + "."
        + (fraction < 100 ? (fraction < 10 ? "00" : "0") : "")
        + fraction;
  }
}
