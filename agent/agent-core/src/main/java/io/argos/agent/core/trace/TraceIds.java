/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import java.util.SplittableRandom;

/**
 * W3C trace and span ids (10 §9.5, F-A06): {@code newTraceId} is the Unix seconds in the upper 32
 * bits and 96 random bits; span ids are 64 random bits, never 0. The random bits come from a {@link
 * SplittableRandom} per thread (10 §9.5 "스레드별"), created from the JVM-wide seed generator of {@code
 * SplittableRandom} on a thread's first id: no lock, no {@code SecureRandom} (N-30).
 */
public final class TraceIds {
  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private static final ThreadLocal<SplittableRandom> RANDOM = new PerThreadRandom();

  private TraceIds() {}

  /** The random source of the current thread. */
  static SplittableRandom random() {
    return RANDOM.get();
  }

  /** Upper 64 bits of a new trace id: Unix seconds (32 bits) and 32 random bits. */
  public static long newTraceIdHigh(long epochMillis) {
    return traceIdHigh(epochMillis, random());
  }

  /** Lower 64 bits of a new trace id (random, never 0). */
  public static long newTraceIdLow() {
    return nonZero(random());
  }

  /** A new span id: 64 random bits, never 0. */
  public static long newSpanId() {
    return nonZero(random());
  }

  static long traceIdHigh(long epochMillis, SplittableRandom random) {
    long seconds = Math.max(0L, epochMillis / 1000L) & 0xFFFFFFFFL;
    return (seconds << 32) | (random.nextInt() & 0xFFFFFFFFL);
  }

  static long nonZero(SplittableRandom random) {
    long value;
    do {
      value = random.nextLong();
    } while (value == 0L);
    return value;
  }

  /** 16 lower-case hex digits. */
  public static String hex16(long value) {
    char[] out = new char[16];
    write(out, 0, value);
    return new String(out);
  }

  /** 32 lower-case hex digits of the 128-bit id {@code high:low}. */
  public static String hex32(long high, long low) {
    char[] out = new char[32];
    write(out, 0, high);
    write(out, 16, low);
    return new String(out);
  }

  private static void write(char[] out, int offset, long value) {
    long rest = value;
    for (int i = 15; i >= 0; i--) {
      out[offset + i] = HEX[(int) (rest & 0xF)];
      rest >>>= 4;
    }
  }

  /** {@code traceparent} (W3C Trace Context level 1, sampled flag set). */
  public static String traceparent(long traceHigh, long traceLow, long spanId) {
    return "00-" + hex32(traceHigh, traceLow) + "-" + hex16(spanId) + "-01";
  }

  /** Creates a thread's {@link SplittableRandom} from the JVM-wide seed generator. */
  private static final class PerThreadRandom extends ThreadLocal<SplittableRandom> {
    @Override
    protected SplittableRandom initialValue() {
      return new SplittableRandom();
    }
  }
}
