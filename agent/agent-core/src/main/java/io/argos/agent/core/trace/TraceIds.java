/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import java.util.concurrent.ThreadLocalRandom;

/**
 * W3C trace ids (10 §9.5, F-A06): Argos-generated trace ids carry the Unix seconds in their upper
 * 32 bits; span ids are random and never 0.
 */
public final class TraceIds {
  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private TraceIds() {}

  /** Upper 64 bits of a new trace id: Unix seconds (32 bits) and 32 random bits. */
  public static long newTraceIdHigh(long epochMillis) {
    long seconds = epochMillis / 1000L;
    return (seconds << 32) | (ThreadLocalRandom.current().nextInt() & 0xFFFFFFFFL);
  }

  /** Lower 64 bits of a new trace id (random, never 0). */
  public static long newTraceIdLow() {
    return nonZeroRandom();
  }

  public static long newSpanId() {
    return nonZeroRandom();
  }

  private static long nonZeroRandom() {
    long value;
    do {
      value = ThreadLocalRandom.current().nextLong();
    } while (value == 0);
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
    for (int i = 15; i >= 0; i--) {
      out[offset + i] = HEX[(int) (value & 0xF)];
      value >>>= 4;
    }
  }

  /** {@code traceparent} (W3C Trace Context level 1, sampled flag set). */
  public static String traceparent(long traceHigh, long traceLow, long spanId) {
    return "00-" + hex32(traceHigh, traceLow) + "-" + hex16(spanId) + "-01";
  }
}
