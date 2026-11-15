/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

import java.lang.ref.WeakReference;

/**
 * Handle of one transaction (10 §3). Holds bootstrap types, primitives and strings only, never an
 * application object (A-9, T-AG-004, F-A36): {@link #thread} is weak and {@link #coreRef} is the
 * core's transaction, opaque here (ADR-0003 C-26).
 *
 * <p>Differences from the class sketch of 10 §3, kept from WO-02 (report item D-2, ADR-0010 C-93):
 *
 * <ul>
 *   <li>The {@code ended} flag of 10 §3 is the private CAS state behind {@link #markEnded()} and
 *       {@link #isEnded()}: a plain volatile boolean cannot tell the first of two concurrent {@code
 *       end} calls (async completion racing a servlet exit), and 10 §7.1 requires the second call
 *       to be ignored.
 *   <li>{@link #flags()} carries the {@code xlog.flags} bits of ADR-0007 C-69 ({@link
 *       #FLAG_VIRTUAL_THREAD}, {@link #FLAG_LEAKED}, {@link #FLAG_SUMMARIZED}); the core sets them
 *       through {@link #addFlags(int)}, which only ever adds bits (CAS loop, never a lock).
 * </ul>
 */
public final class TraceContext {
  /** C-69 bit 1: the transaction began on a virtual thread (F-A30); set at construction. */
  public static final int FLAG_VIRTUAL_THREAD = 1;

  /**
   * C-69 bit 2: the agent ended the transaction itself because its context outlived the request
   * ({@code leaked}, 10 §8, §13.1 {@code max-duration-ms}).
   */
  public static final int FLAG_LEAKED = 2;

  /** C-69 bit 4: the step buffer reached {@code sampling.profile.max-steps} (10 §9.1 요약 모드). */
  public static final int FLAG_SUMMARIZED = 4;

  /** Every defined bit; the other bits are reserved (C-69) and never set. */
  public static final int FLAG_MASK = FLAG_VIRTUAL_THREAD | FLAG_LEAKED | FLAG_SUMMARIZED;

  public final long txId;
  public final long traceIdHi;
  public final long traceIdLo;
  public final long spanId;
  public final long parentSpanId;

  /** {@link Kind}. */
  public final int kind;

  public final long startNanos;
  public final long startEpochMs;

  /** Thread that began the transaction (slow stack capture, 10 §13.1). */
  public final WeakReference<Thread> thread;

  public final boolean virtualThread;

  /** Thread CPU time and allocated bytes at begin; -1 when not measurable (D-23). */
  public final long cpuStartNanos;

  public final long allocStartBytes;

  public volatile String serviceName;
  public volatile boolean serviceFixed;
  public volatile int status;
  public volatile boolean error;

  /** Servlet dispatch nesting (10 §7.1). */
  public volatile int dispatchDepth;

  public volatile boolean asyncStarted;
  public volatile boolean streaming;

  public int sqlCount;
  public int extCount;
  public long sqlNanos;
  public long extNanos;

  /** The core's per-transaction state; bootstrap code never reads it. */
  public volatile Object coreRef;

  /** 0 while running, 1 once ended ({@link #markEnded()}); updated through {@link EndState}. */
  volatile int endState;

  /** C-69 flag bits ({@link #flags()}); updated through {@link EndState#FLAGS}. */
  volatile int flags;

  public TraceContext(
      long txId,
      long traceIdHi,
      long traceIdLo,
      long spanId,
      long parentSpanId,
      int kind,
      long startNanos,
      long startEpochMs,
      Thread thread,
      boolean virtualThread,
      long cpuStartNanos,
      long allocStartBytes) {
    this.txId = txId;
    this.traceIdHi = traceIdHi;
    this.traceIdLo = traceIdLo;
    this.spanId = spanId;
    this.parentSpanId = parentSpanId;
    this.kind = kind;
    this.startNanos = startNanos;
    this.startEpochMs = startEpochMs;
    this.thread = new WeakReference<Thread>(thread);
    this.virtualThread = virtualThread;
    this.cpuStartNanos = cpuStartNanos;
    this.allocStartBytes = allocStartBytes;
    this.flags = virtualThread ? FLAG_VIRTUAL_THREAD : 0;
  }

  /**
   * Marks the transaction ended. Returns true for the first caller only (10 §7.1 ENDED: "두 번째 end는
   * 무시").
   */
  public boolean markEnded() {
    return EndState.UPDATER.compareAndSet(this, 0, 1);
  }

  public boolean isEnded() {
    return endState != 0;
  }

  /** The C-69 flag bits set so far ({@link #FLAG_VIRTUAL_THREAD}, ...). */
  public int flags() {
    return flags;
  }

  /**
   * Adds {@code bits} (masked with {@link #FLAG_MASK}) to {@link #flags()}; never clears a bit and
   * never blocks. Safe from any thread.
   */
  public void addFlags(int bits) {
    int add = bits & FLAG_MASK;
    if (add == 0) {
      return;
    }
    while (true) {
      int current = flags;
      if ((current & add) == add || EndState.FLAGS.compareAndSet(this, current, current | add)) {
        return;
      }
    }
  }

  /** True when every bit of {@code bits} is set. */
  public boolean hasFlags(int bits) {
    return (flags & bits) == bits;
  }
}
