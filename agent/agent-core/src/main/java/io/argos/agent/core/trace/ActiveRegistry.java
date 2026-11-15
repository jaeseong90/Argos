/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.trace;

import io.argos.agent.bootstrap.TraceContext;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * The transactions in progress (10 §8 "가상 스레드", §11 {@code ActiveSnapshotter}, §13.1 {@code
 * SlowStackWatcher}, 18 §19 {@code active_registry_size}). Keyed by the {@link TraceContext}, never
 * by the thread: a context holds its thread only weakly ({@link TraceContext#thread}), so the
 * registry pins no thread, virtual or not (DSN-10-13).
 *
 * <p>{@link #add} at {@code begin} and {@link #remove} at {@code end} run on application threads: a
 * {@link ConcurrentHashMap} with identity keys ({@code TraceContext} does not override {@code
 * equals}) and an atomic size reservation, lock-free and never waiting. The registry holds at most
 * {@link #capacity()} contexts (default {@value #DEFAULT_CAPACITY}, about 1 MB); a context it
 * cannot hold is counted ({@link #rejected()}) and recorded as usual, it is only invisible to the
 * active snapshot and the slow-stack watcher. A context whose end never comes is ended by the agent
 * ({@code transaction.max-duration-ms}, WO-19, C-69 {@code leaked}) and removed. {@link #forEach}
 * and {@link #find} run on agent threads.
 */
public final class ActiveRegistry {
  /** Default upper bound of contexts in progress. */
  public static final int DEFAULT_CAPACITY = 16_384;

  private final int capacity;
  private final ConcurrentHashMap<TraceContext, Boolean> contexts;
  private final AtomicInteger size = new AtomicInteger();
  private final AtomicLong rejected = new AtomicLong();

  /** A registry holding at most {@code capacity} contexts. */
  public ActiveRegistry(int capacity) {
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity < 1");
    }
    this.capacity = capacity;
    this.contexts = new ConcurrentHashMap<TraceContext, Boolean>(Math.min(capacity, 256));
  }

  /** Upper bound of {@link #size()}. */
  public int capacity() {
    return capacity;
  }

  /** Registers {@code context}; false when the registry is full (counted, never blocks). */
  public boolean add(TraceContext context) {
    Objects.requireNonNull(context, "context");
    while (true) {
      int current = size.get();
      if (current >= capacity) {
        rejected.incrementAndGet();
        return false;
      }
      if (size.compareAndSet(current, current + 1)) {
        break;
      }
    }
    if (contexts.putIfAbsent(context, Boolean.TRUE) != null) {
      size.decrementAndGet();
      return false;
    }
    return true;
  }

  /** Removes {@code context}; true when it was registered. */
  public boolean remove(TraceContext context) {
    Objects.requireNonNull(context, "context");
    if (contexts.remove(context) != null) {
      size.decrementAndGet();
      return true;
    }
    return false;
  }

  /** Contexts registered now (18 §19 {@code active_registry_size}). */
  public int size() {
    return Math.max(0, size.get());
  }

  /** Contexts not registered because the registry was full. */
  public long rejected() {
    return rejected.get();
  }

  /** Visits every registered context (agent threads: snapshots, slow stacks, max duration). */
  public void forEach(Consumer<TraceContext> visitor) {
    Objects.requireNonNull(visitor, "visitor");
    for (TraceContext context : contexts.keySet()) {
      visitor.accept(context);
    }
  }

  /** The registered context of {@code txId} ({@code THREAD_STACK}, 10 §12.1), or null. */
  public TraceContext find(long txId) {
    for (TraceContext context : contexts.keySet()) {
      if (context.txId == txId) {
        return context;
      }
    }
    return null;
  }
}
