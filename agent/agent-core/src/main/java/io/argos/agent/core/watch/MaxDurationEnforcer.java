/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.watch;

import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.core.trace.ActiveRegistry;
import io.argos.agent.core.trace.TraceClock;
import io.argos.agent.core.trace.TracerImpl;
import java.util.concurrent.TimeUnit;

/**
 * {@code transaction.max-duration-ms} (10 §6.7 "상한", §13.1, F-A34): on every tick of {@code
 * argos-watch} (1 s), ends the transactions of the {@link ActiveRegistry} that have run longer than
 * the limit (default 3,600,000 ms), so that a request whose end never comes (an SSE stream on a
 * container without flush instrumentation, an async request that is never completed, a hung thread)
 * cannot stay open forever.
 *
 * <p>Such a transaction ends with {@code timed_out=true} (11 {@code TransactionRecord.timed_out}),
 * the C-69 {@code leaked} flag (the agent ended it itself, {@code TraceContext#FLAG_LEAKED}) and
 * {@code argos_agent_transactions_leaked_total}; its status is the response status recorded so far,
 * or 599 ({@link TracerImpl#LEAKED_STATUS}) when none was ({@link TracerImpl#endTimedOut}). The
 * flags and the counter are applied only when this end wins the single end ({@code
 * TraceContext#markEnded()}): an end by the instrumentation at the same moment keeps its own record
 * unchanged, and a later end by the instrumentation is ignored. The tracer's end removes it from
 * the registry (and a full registry's unregistered contexts are not seen here, see {@link
 * ActiveRegistry}).
 *
 * <p>Runs on the agent thread only; never touches an application thread. Elapsed time is the
 * difference of {@link TraceClock#nanoTime()} values (D-01), injectable for tests.
 */
public final class MaxDurationEnforcer implements Runnable {
  private final ActiveRegistry registry;
  private final TracerImpl tracer;
  private final TraceClock clock;
  private volatile long maxNanos;

  /**
   * @param tracer the agent's tracer ({@code TraceRuntime.tracerImpl()}), which counts {@code
   *     argos_agent_transactions_leaked_total}
   * @param maxDurationMs {@code transaction.max-duration-ms}
   */
  public MaxDurationEnforcer(
      ActiveRegistry registry, TracerImpl tracer, TraceClock clock, long maxDurationMs) {
    this.registry = registry;
    this.tracer = tracer;
    this.clock = clock == null ? TraceClock.SYSTEM : clock;
    maxDurationMs(maxDurationMs);
  }

  /** A new {@code transaction.max-duration-ms} (configuration change); at least 1 ms. */
  public void maxDurationMs(long value) {
    maxNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1L, value));
  }

  @Override
  public void run() {
    if (registry == null || tracer == null) {
      return;
    }
    final long now = clock.nanoTime();
    final long limit = maxNanos;
    registry.forEach(
        context -> {
          if (!context.isEnded() && now - context.startNanos > limit) {
            end(context, now);
          }
        });
  }

  /**
   * Ends {@code context} as timed out (class comment); true when this call ended it, false when
   * another end came first (then nothing of the context changed).
   */
  boolean end(TraceContext context, long now) {
    return tracer.endTimedOut(context, now);
  }
}
