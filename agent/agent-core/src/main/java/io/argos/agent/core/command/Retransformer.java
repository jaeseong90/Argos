/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.command;

import io.argos.agent.core.guard.RetransformLimiter;
import io.argos.agent.core.log.AgentLogger;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Retransforms the loaded classes of an {@code instrumentation.custom-methods} change (WO-17; 10
 * §12.2 step 4, §6.10 "동적 추가", F-A15, SEC-25, T-AG-046). Runs on {@code argos-command} (the apply
 * path, one change at a time); never on an application thread.
 *
 * <ol>
 *   <li>The new value's {@link CustomMethodPolicy} chooses among {@code
 *       Instrumentation.getAllLoadedClasses()} (forbidden prefixes, generated classes and items
 *       over {@code max-classes-per-pattern} left out, {@code isModifiableClass} only); the classes
 *       the value in force matches are added, so that a class a removed item matched is
 *       retransformed too (its custom advice goes away, WO-18).
 *   <li>{@link RetransformLimiter#tryAcquire}: more than {@value RetransformLimiter#MAX_CLASSES}
 *       classes, or less than a minute after the last accepted retransformation, refuses the change
 *       ({@code retransform_limit}): nothing is installed or retransformed, the value in force
 *       stays. A refused change does not use up the minute; a change that touches no class neither.
 *   <li>The new policy is installed ({@link CustomMethodPolicy#install}, read by WO-18's matcher
 *       during the retransformation) and the classes are retransformed in one call; when that call
 *       fails, class by class, skipping the ones that fail.
 * </ol>
 *
 * The custom module (WO-18, {@code io.argos.agent.instrumentation.custom}) reads the installed
 * policy in its matchers, so the retransformation adds the {@code METHOD} step advice to the
 * methods of the new items and removes it from the methods of removed items. The {@code disabled}
 * WARN of an item over the limit is written after the policy is installed: a refused change
 * installs nothing and logs no such WARN.
 */
public final class Retransformer {
  /** Self-diagnostics extras keys (16 §5.6 "자가 진단 보고"). */
  public static final String EXTRA_REQUESTS = "retransform_requests";

  public static final String EXTRA_REJECTED = "retransform_rejected";
  public static final String EXTRA_CLASSES = "retransformed_classes";
  public static final String EXTRA_FAILED = "retransform_failed_classes";
  public static final String EXTRA_IGNORED = "custom_methods_items_ignored";
  public static final String EXTRA_DISABLED = "custom_methods_items_disabled";

  /** The outcome of {@link #apply}. */
  public static final class Result {
    private final boolean applied;
    private final int classes;
    private final String refusal;

    private Result(boolean applied, int classes, String refusal) {
      this.applied = applied;
      this.classes = classes;
      this.refusal = refusal;
    }

    /** True when the new value may take effect (its policy is installed). */
    public boolean applied() {
      return applied;
    }

    /** Classes retransformed ({@code ConfigApplyResult.retransformed_classes}). */
    public int classes() {
      return classes;
    }

    /** {@code "retransform_limit: ..."} of a refused change, else null. */
    public String refusal() {
      return refusal;
    }
  }

  private final Instrumentation instrumentation;
  private final RetransformLimiter limiter;
  private final LongSupplier nanoClock;
  private final AtomicLong requests = new AtomicLong();
  private final AtomicLong rejected = new AtomicLong();
  private final AtomicLong classesTotal = new AtomicLong();
  private final AtomicLong failedTotal = new AtomicLong();

  /**
   * @param instrumentation the JVM instrumentation (null: nothing is retransformed, the policy is
   *     still installed)
   * @param limiter the 50 classes / one per minute limit
   * @param nanoClock {@code System::nanoTime} (the INFO line's milliseconds)
   */
  public Retransformer(
      Instrumentation instrumentation, RetransformLimiter limiter, LongSupplier nanoClock) {
    this.instrumentation = instrumentation;
    this.limiter = Objects.requireNonNull(limiter, "limiter");
    this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
  }

  /**
   * Makes {@code next} (the policy of the new {@code instrumentation.custom-methods}) take effect
   * in place of {@code previous} (see the class comment).
   */
  public Result apply(CustomMethodPolicy previous, CustomMethodPolicy next) {
    requests.incrementAndGet();
    long start = nanoClock.getAsLong();
    List<Class<?>> classes = new ArrayList<Class<?>>();
    List<CustomMethodPolicy.Item> disabled = new ArrayList<CustomMethodPolicy.Item>();
    if (instrumentation != null) {
      Class<?>[] loaded = instrumentation.getAllLoadedClasses();
      CustomMethodPolicy.Modifiable modifiable =
          new CustomMethodPolicy.Modifiable() {
            @Override
            public boolean test(Class<?> type) {
              return instrumentation.isModifiableClass(type);
            }
          };
      CustomMethodPolicy.Selection chosen = next.select(loaded, modifiable);
      disabled.addAll(chosen.newlyDisabled());
      IdentityHashMap<Class<?>, Boolean> seen = new IdentityHashMap<Class<?>, Boolean>();
      for (Class<?> type : chosen.classes()) {
        seen.put(type, Boolean.TRUE);
        classes.add(type);
      }
      if (previous != null) {
        for (Class<?> type : previous.select(loaded, modifiable).classes()) {
          if (!seen.containsKey(type)) {
            seen.put(type, Boolean.TRUE);
            classes.add(type);
          }
        }
      }
    }
    RetransformLimiter.Decision decision = limiter.tryAcquire(classes.size());
    if (!decision.allowed()) {
      rejected.incrementAndGet();
      String refusal = refusal(decision, classes.size());
      AgentLogger.warn(
          "custom methods change refused (retransform_limit): {} class(es) would be"
              + " retransformed, at most {} per request and one request per {} s",
          classes.size(),
          RetransformLimiter.MAX_CLASSES,
          TimeUnit.MILLISECONDS.toSeconds(RetransformLimiter.MIN_INTERVAL_MILLIS));
      return new Result(false, 0, refusal);
    }
    CustomMethodPolicy.install(next);
    // Only now: a refused change installs nothing, so its over-limit items disable nothing in
    // force and must not be reported as disabled (WO-17 lane A2 note, fixed in WO-18).
    next.warnDisabled(disabled);
    int done = retransform(classes);
    classesTotal.addAndGet(done);
    long ms = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, nanoClock.getAsLong() - start));
    AgentLogger.info(
        "custom methods change: {} class(es) retransformed in {} ms ({} item(s) active)",
        done,
        ms,
        next.activeItems().size());
    return new Result(true, done, null);
  }

  /** Self-diagnostics counters of this retransformer and the policy in force. */
  public Map<String, Object> diagnostics() {
    Map<String, Object> out = new LinkedHashMap<String, Object>();
    CustomMethodPolicy policy = CustomMethodPolicy.current();
    out.put(EXTRA_IGNORED, Long.valueOf(policy.ignoredCount()));
    out.put(EXTRA_DISABLED, Long.valueOf(policy.disabledCount()));
    out.put(EXTRA_REQUESTS, Long.valueOf(requests.get()));
    out.put(EXTRA_REJECTED, Long.valueOf(rejected.get()));
    out.put(EXTRA_CLASSES, Long.valueOf(classesTotal.get()));
    out.put(EXTRA_FAILED, Long.valueOf(failedTotal.get()));
    return out;
  }

  private int retransform(List<Class<?>> classes) {
    if (classes.isEmpty() || instrumentation == null) {
      return 0;
    }
    try {
      instrumentation.retransformClasses(classes.toArray(new Class<?>[0]));
      return classes.size();
    } catch (UnmodifiableClassException | RuntimeException | LinkageError e) {
      // One class refused the batch: retry class by class (a class that fails keeps its bytes).
      int done = 0;
      int failed = 0;
      for (Class<?> type : classes) {
        try {
          instrumentation.retransformClasses(type);
          done++;
        } catch (UnmodifiableClassException | RuntimeException | LinkageError single) {
          failed++;
        }
      }
      failedTotal.addAndGet(failed);
      AgentLogger.warn(
          "custom methods change: {} of {} class(es) could not be retransformed ({})",
          failed,
          classes.size(),
          e.getClass());
      return done;
    }
  }

  /** The {@code RejectedKey.message} of a refused change. */
  String refusal(RetransformLimiter.Decision decision, int classes) {
    if (decision == RetransformLimiter.Decision.TOO_MANY_CLASSES) {
      return RetransformLimiter.REASON
          + ": "
          + classes
          + " classes would be retransformed, at most "
          + RetransformLimiter.MAX_CLASSES
          + " per request";
    }
    long seconds = (limiter.millisUntilNext() + 999L) / 1000L;
    return RetransformLimiter.REASON
        + ": at most one retransformation per minute, the next one in "
        + seconds
        + " s";
  }
}
