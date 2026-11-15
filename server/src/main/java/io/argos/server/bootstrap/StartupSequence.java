/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.bootstrap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

/**
 * Runs the server start sequence of DSN-12-15: every {@link StartupPhase} bean, synchronously, in
 * the order of {@link PhaseOrder}, while the application context refreshes.
 *
 * <p>When it runs: after all singletons have been created ({@link SmartInitializingSingleton}) and
 * before the context starts its lifecycle beans. The embedded web server binds its ports (HTTP 8080
 * and the management port 8081, C-7) in that later lifecycle step, so no HTTP listener accepts a
 * connection before the PostgreSQL and ClickHouse migrations have finished; the gRPC listener is a
 * {@link PhaseOrder#LISTENERS} step itself (WO-21).
 *
 * <p>Logging: one INFO line per step with its duration, one INFO line per phase without steps, and
 * one INFO line for the whole sequence. A failing step stops the sequence: exactly one ERROR line
 * names the phase, the step, the elapsed time and the reason (the message of a {@link
 * StartupPhaseException}, otherwise the exception class and message, reduced to one line), then
 * {@link StartupAbortedException} aborts the refresh and the process exits with {@link
 * StartupAbortedException#EXIT_CODE}. Steps must not put secret values into their messages ({@link
 * StartupPhase}).
 */
@Component
public class StartupSequence implements SmartInitializingSingleton {

  private static final Logger LOG = LoggerFactory.getLogger(StartupSequence.class);

  /** Upper bound of the reason text in the ERROR line. */
  static final int MAX_REASON_LENGTH = 500;

  private final List<StartupPhase> steps;
  private final AtomicBoolean started = new AtomicBoolean();
  private final List<StepResult> results = Collections.synchronizedList(new ArrayList<>());
  private volatile boolean complete;

  /**
   * Creates the sequence from every {@link StartupPhase} bean of the context.
   *
   * @throws IllegalStateException if two steps share a name
   */
  @Autowired
  public StartupSequence(ObjectProvider<StartupPhase> phases) {
    this(phases.orderedStream().toList());
  }

  /** Creates the sequence from explicit steps (tests). */
  StartupSequence(List<StartupPhase> phases) {
    List<StartupPhase> sorted = new ArrayList<>(phases);
    sorted.sort(
        Comparator.comparing(StartupPhase::order)
            .thenComparing(AnnotationAwareOrderComparator.INSTANCE)
            .thenComparing(StartupPhase::name));
    Set<String> names = new HashSet<>();
    for (StartupPhase step : sorted) {
      if (!names.add(step.name())) {
        throw new IllegalStateException("Duplicate startup step name: " + step.name());
      }
    }
    this.steps = List.copyOf(sorted);
  }

  @Override
  public void afterSingletonsInstantiated() {
    run();
  }

  /**
   * Runs every step once, in order.
   *
   * @throws StartupAbortedException when a step fails (already logged)
   * @throws IllegalStateException when called a second time
   */
  public void run() {
    if (!started.compareAndSet(false, true)) {
      throw new IllegalStateException("The startup sequence runs only once");
    }
    long sequenceStart = System.nanoTime();
    for (PhaseOrder phase : PhaseOrder.values()) {
      List<StartupPhase> inPhase = steps.stream().filter(s -> s.order() == phase).toList();
      if (inPhase.isEmpty()) {
        LOG.info("Startup phase {}: no steps", phase);
        continue;
      }
      for (StartupPhase step : inPhase) {
        runStep(phase, step);
      }
    }
    complete = true;
    LOG.info(
        "Startup sequence completed in {} ms ({} steps); listeners start next",
        millisSince(sequenceStart),
        steps.size());
  }

  private void runStep(PhaseOrder phase, StartupPhase step) {
    long start = System.nanoTime();
    try {
      step.run();
    } catch (Exception | Error e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      long elapsed = millisSince(start);
      String message =
          String.format(
              Locale.ROOT,
              "Startup phase %s step %s failed after %d ms: %s. The server does not start.",
              phase,
              step.name(),
              elapsed,
              reason(e));
      LOG.error(message);
      LOG.debug("Startup step {} failure details", step.name(), e);
      throw new StartupAbortedException(phase, step.name(), message, e);
    }
    long elapsed = millisSince(start);
    results.add(new StepResult(phase, step.name(), elapsed));
    LOG.info("Startup phase {} step {} completed in {} ms", phase, step.name(), elapsed);
  }

  /** The one-line reason of a failed step (no stack trace, bounded length). */
  static String reason(Throwable failure) {
    String text;
    if (failure instanceof StartupPhaseException) {
      text = String.valueOf(failure.getMessage());
    } else {
      String message = failure.getMessage();
      text =
          failure.getClass().getName()
              + (message == null || message.isBlank() ? "" : ": " + message);
    }
    String oneLine = text.replaceAll("[\\r\\n\\t]+", " ").strip();
    return oneLine.length() <= MAX_REASON_LENGTH
        ? oneLine
        : oneLine.substring(0, MAX_REASON_LENGTH) + "...";
  }

  private static long millisSince(long startNanos) {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
  }

  /** True once every step has completed (readiness, WO-20 runtime lane). */
  public boolean isComplete() {
    return complete;
  }

  /** The steps in the order they run (or ran). */
  public List<StartupPhase> steps() {
    return steps;
  }

  /** Durations of the completed steps, in order (start summary, tests). */
  public List<StepResult> results() {
    synchronized (results) {
      return List.copyOf(results);
    }
  }

  /**
   * Duration of one completed step.
   *
   * @param phase the phase of the step
   * @param name the step name
   * @param durationMillis wall-clock duration in milliseconds
   */
  public record StepResult(PhaseOrder phase, String name, long durationMillis) {}
}
