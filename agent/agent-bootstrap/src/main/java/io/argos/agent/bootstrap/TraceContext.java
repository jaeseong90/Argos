/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

import java.lang.ref.WeakReference;

/**
 * Handle of one transaction (10 §3). Holds bootstrap types, primitives and strings only, never an
 * application object (A-9, T-AG-004, F-A36): {@link #thread} is weak and {@link #coreRef} is the
 * core's transaction, opaque here.
 */
public final class TraceContext {
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
}
