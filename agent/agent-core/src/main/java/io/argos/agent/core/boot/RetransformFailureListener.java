/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import io.argos.agent.core.log.AgentLogger;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.bytebuddy.agent.builder.AgentBuilder;

/**
 * Makes a rejected retransformation of already loaded classes visible (ADR-0010 C-257 (2), WO-52).
 *
 * <p>The premain retransformation of the JDK allow-list classes ({@link PremainDiscovery}) runs as
 * one batch ({@code BatchAllocator.ForTotal}). When the JVM rejects it (for example {@code
 * UnsupportedOperationException: class redefinition failed: attempted to change superclass or
 * interfaces}, a {@code VerifyError} or an {@code UnmodifiableClassException}), every class of the
 * batch keeps its bytes and stays uninstrumented. ByteBuddy reports this only to the redefinition
 * listener, which was {@code NoOp}: before C-257 (2) such a failure was silent (21 classes in
 * T-MX-112).
 *
 * <p>This listener writes a WARN with the number of classes that kept their bytes, one
 * representative class name and the JVM's reason. Lines are rate limited to one per minute; the
 * classes of failures in between are counted and reported by the next line. Nothing is retried (the
 * same bytes would be rejected again), the listener never throws into ByteBuddy and its state is
 * three counters. The remote retransformation of custom methods ({@code command.Retransformer})
 * calls {@code Instrumentation.retransformClasses} itself and logs its own failures.
 *
 * <p>{@link InstrumentationInstaller} registers it with {@code with(RedefinitionStrategy.Listener)}
 * after {@code with(RedefinitionStrategy)}, which resets the listener to {@code NoOp}; ByteBuddy
 * keeps the two in a {@code Listener.Compound}. No inner classes (premain class budget, ADR-0009
 * C-79): the clock is {@link #nanoTime()}, which a test overrides.
 */
class RetransformFailureListener implements AgentBuilder.RedefinitionStrategy.Listener {
  /** Minimum time between two WARN lines. */
  static final long INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

  /** Longest JVM reason written (a {@code VerifyError} message can be long). */
  static final int MAX_REASON_CHARS = 300;

  private static final long NEVER = Long.MIN_VALUE;

  private final AtomicLong lastLineNanos = new AtomicLong(NEVER);
  private final AtomicLong pendingClasses = new AtomicLong();
  private final AtomicLong failedClasses = new AtomicLong();

  @Override
  public void onBatch(int index, List<Class<?>> batch, List<Class<?>> types) {
    // Nothing to do before a batch.
  }

  @Override
  public Iterable<? extends List<Class<?>>> onError(
      int index, List<Class<?>> batch, Throwable throwable, List<Class<?>> types) {
    try {
      int size = batch == null ? 0 : batch.size();
      failedClasses.addAndGet(size);
      long pending = pendingClasses.addAndGet(size);
      long now = nanoTime();
      long last = lastLineNanos.get();
      if (last != NEVER && now - last < INTERVAL_NANOS) {
        return Collections.emptyList();
      }
      if (!lastLineNanos.compareAndSet(last, now)) {
        return Collections.emptyList(); // another thread writes the line
      }
      pendingClasses.addAndGet(-pending);
      report(pending, size, representative(batch), reason(throwable));
    } catch (Throwable ignored) {
      // A listener never fails ByteBuddy's redefinition loop.
    }
    return Collections.emptyList();
  }

  @Override
  public void onComplete(
      int amount, List<Class<?>> types, Map<List<Class<?>>, Throwable> failures) {
    // Every failed batch was reported (or counted) by onError.
  }

  /** Classes whose retransformation failed since this listener was created. */
  long failedClasses() {
    return failedClasses.get();
  }

  /** The clock of the rate limit ({@link System#nanoTime()}); tests override it. */
  long nanoTime() {
    return System.nanoTime();
  }

  private static void report(long classes, int batch, String representative, String reason) {
    AgentLogger.warn(
        "retransformation of {} already loaded class(es) failed and they stay uninstrumented"
            + " (this batch: {}, e.g. {}): {}; at most one such line per minute",
        classes,
        batch,
        representative,
        reason);
  }

  private static String representative(List<Class<?>> batch) {
    if (batch == null || batch.isEmpty()) {
      return "-";
    }
    Class<?> first = batch.get(0);
    return first == null ? "-" : first.getName();
  }

  private static String reason(Throwable throwable) {
    if (throwable == null) {
      return "unknown reason";
    }
    String text;
    try {
      text = throwable.toString();
    } catch (RuntimeException e) {
      text = throwable.getClass().getName();
    }
    if (text == null) {
      text = throwable.getClass().getName();
    }
    if (text.length() > MAX_REASON_CHARS) {
      text = text.substring(0, MAX_REASON_CHARS) + "...";
    }
    return text.replace('\n', ' ').replace('\r', ' ');
  }
}
