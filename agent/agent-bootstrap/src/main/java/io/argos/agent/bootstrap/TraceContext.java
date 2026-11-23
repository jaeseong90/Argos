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

  /**
   * Container dispatches of the request (F-A28, 11 {@code dispatch_count}): 1 for the REQUEST
   * dispatch, plus one per FORWARD, INCLUDE, ERROR or ASYNC dispatch that reached a servlet while
   * the transaction ran. Written by the servlet helper on the thread that runs the dispatch.
   */
  public volatile int dispatchCount;

  /**
   * The dispatch counted last in {@link #dispatchCount}: the identity hash code of its request
   * object (an int, never the object, A-9), valid while {@link #countedDispatchDepth} is above 0. A
   * servlet that hands the same request object on to another servlet (Jasper's {@code JspServlet}
   * and the generated page) is still that dispatch and does not count again. Written by the servlet
   * helper on the thread that runs the dispatch.
   */
  public volatile int countedDispatchRequest;

  /**
   * The {@link #dispatchDepth} of the servlet call that counted {@link #countedDispatchRequest}; 0
   * when none. Cleared when the depth drops below it, so that a later dispatch with the same
   * request object (the next ASYNC or ERROR dispatch) counts again.
   */
  public volatile int countedDispatchDepth;

  /**
   * True while the request is in servlet async mode (10 §7.1 ASYNC_PENDING): {@code startAsync} was
   * called and no ASYNC dispatch has joined since. A response flush in this state ends a streaming
   * response (10 §6.7, F-A34).
   */
  public volatile boolean asyncStarted;

  /** F-A34: the response streams (101 upgrade, first flush after {@code startAsync}). */
  public volatile boolean streaming;

  /**
   * F-A34 {@code timed_out}: the agent ended the transaction at {@code transaction.max-duration-ms}
   * (10 §6.7, §13.1).
   */
  public volatile boolean timedOut;

  /**
   * An {@code AsyncListener.onTimeout} was seen (10 §6.7): the end keeps the status the container
   * sets afterwards, or 500 when that status stays below 400.
   */
  public volatile boolean asyncTimedOut;

  /**
   * The output stream or writer the container's response facade handed out while the transaction
   * ran synchronously on the request thread (WO-12 addition to 10 §3, ADR-0010 C-127): a weak
   * reference, never a strong one (A-9). Written and read by the servlet helpers on the request
   * thread only. A synchronous request records its stream here instead of in a shared store; {@code
   * startAsync} and the suspending exit link it to the response facade, so that its first flush in
   * async mode still ends a streaming response (10 §6.7).
   */
  public volatile WeakReference<Object> responseStream;

  /**
   * SQL executions inside the transaction (11 {@code sql_count}) and their time, execute plus fetch
   * (11 {@code sql_time_ms}, 13 "exec + fetch 합"); written by the JDBC helper (WO-13).
   */
  public int sqlCount;

  /**
   * External calls of the transaction (11 {@code ext_count}) and their time (11 {@code
   * ext_time_ms}); written only through {@link #addExternalCall(long)} (WO-14), which is safe from
   * any thread: a WebClient call ends on a Netty event loop while the request thread runs.
   */
  public volatile int extCount;

  public long sqlNanos;

  /** See {@link #extCount}. */
  public volatile long extNanos;

  /**
   * The {@code tracestate} header of the incoming request after the W3C checks (at most 512
   * characters and 32 entries), forwarded on outgoing calls (10 §8); null when there was none or it
   * was dropped. WO-14 addition to 10 §3.
   */
  public final String incomingTracestate;

  /**
   * The app id of the {@code argos=<app>:<inst>:<svc>} entry of the incoming {@code tracestate} (11
   * {@code caller_app_id}); 0 when absent or malformed. WO-14 addition to 10 §3.
   */
  public final int callerAppId;

  /**
   * Connection acquisition wait of the transaction: the sum of its outermost {@code
   * DataSource.getConnection()} times (11 {@code TransactionRecord.dbc_wait_ms}, F-A03); written by
   * the JDBC helper. WO-13 addition to 10 §3.
   */
  public long dbcNanos;

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
    this(
        txId,
        traceIdHi,
        traceIdLo,
        spanId,
        parentSpanId,
        kind,
        startNanos,
        startEpochMs,
        thread,
        virtualThread,
        cpuStartNanos,
        allocStartBytes,
        null,
        0);
  }

  /**
   * A context with the incoming propagation data of 10 §8 ({@link #incomingTracestate}, {@link
   * #callerAppId}); WO-14.
   */
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
      long allocStartBytes,
      String incomingTracestate,
      int callerAppId) {
    this.incomingTracestate = incomingTracestate;
    this.callerAppId = callerAppId;
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

  /**
   * Adds one external call of {@code elapsedNanos} (negative counts as 0) to {@link #extCount} and
   * {@link #extNanos}: lock-free, safe from any thread, never blocks (WO-14). The two values are
   * not updated as one; a record built at the same moment may see the count before the time.
   */
  public void addExternalCall(long elapsedNanos) {
    EndState.EXT_COUNT.incrementAndGet(this);
    if (elapsedNanos > 0) {
      EndState.EXT_NANOS.addAndGet(this, elapsedNanos);
    }
  }

  /** True when every bit of {@code bits} is set. */
  public boolean hasFlags(int bits) {
    return (flags & bits) == bits;
  }
}
