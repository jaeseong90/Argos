/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.heavy.startup;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Runs the {@link StartupStep}s in the context refresh's lifecycle phase {@value #PHASE}: after
 * every singleton exists and every SmartInitializingSingleton has run (so the cache, AOP and meter
 * infrastructure is complete), and before the web server's lifecycle (phase DEFAULT_PHASE - 2048 in
 * Spring Boot 3.x) binds the port. Every class the steps load therefore counts towards the startup
 * the benchmark measures (18 §7.4: process start to port binding; 01 §7.3: "Started ... in").
 * Records, per step, the classes loaded and the time taken, and the JVM's loaded class count when
 * the app has started (N-04, N-27 baseline: shop-heavy must load at least {@value #CLASS_TARGET}
 * classes, 18 §4.1).
 *
 * <p>SAMPLE_HEAVY_WARMUP=false skips the steps (the libraries are then initialized on first use
 * only), for comparing the two startup costs.
 */
@Component
public class StartupReport implements SmartLifecycle, ApplicationListener<ApplicationStartedEvent> {

  /** Lifecycle phase of the steps: before Spring Boot's web server start (DEFAULT_PHASE - 2048). */
  public static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 3072;

  /** Loaded classes shop-heavy must reach at startup (18 §4.1: "classes 30,000+"). */
  public static final int CLASS_TARGET = 30_000;

  private static final Logger log = LoggerFactory.getLogger(StartupReport.class);

  private final ObjectProvider<StartupStep> steps;
  private final boolean enabled;
  private final ClassLoadingMXBean classes = ManagementFactory.getClassLoadingMXBean();

  private volatile boolean running;
  private volatile List<Map<String, Object>> results = Collections.emptyList();
  private volatile long loadedAtStarted = -1;
  private volatile long totalLoadedAtStarted = -1;
  private volatile long stepsMs;

  public StartupReport(
      ObjectProvider<StartupStep> steps, @Value("${sample.heavy.warmup:true}") boolean enabled) {
    this.steps = steps;
    this.enabled = enabled;
  }

  @Override
  public int getPhase() {
    return PHASE;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public void stop() {
    running = false;
  }

  /** Runs the steps once (a context refresh starts the lifecycle once). */
  @Override
  public void start() {
    if (!running && results.isEmpty()) {
      runSteps();
    }
    running = true;
  }

  private void runSteps() {
    if (!enabled) {
      log.info("Heavy startup steps skipped (SAMPLE_HEAVY_WARMUP=false)");
      return;
    }
    List<Map<String, Object>> done = new ArrayList<>();
    long allStart = System.nanoTime();
    for (StartupStep step : steps.orderedStream().toList()) {
      long before = classes.getTotalLoadedClassCount();
      long start = System.nanoTime();
      Map<String, Object> summary;
      try {
        summary = step.run();
      } catch (Exception e) {
        throw new IllegalStateException("Heavy startup step '" + step.name() + "' failed", e);
      }
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("step", step.name());
      result.put("libraries", step.libraries());
      result.put("classesLoaded", classes.getTotalLoadedClassCount() - before);
      result.put("ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
      result.put("result", summary);
      done.add(Collections.unmodifiableMap(result));
    }
    stepsMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - allStart);
    results = Collections.unmodifiableList(done);
    log.info(
        "Heavy startup steps: {} steps in {} ms, {} classes loaded so far",
        done.size(),
        stepsMs,
        classes.getLoadedClassCount());
  }

  @Override
  public void onApplicationEvent(ApplicationStartedEvent event) {
    loadedAtStarted = classes.getLoadedClassCount();
    totalLoadedAtStarted = classes.getTotalLoadedClassCount();
    if (loadedAtStarted < CLASS_TARGET) {
      log.warn(
          "shop-heavy loaded {} classes at startup, below the target of {}",
          loadedAtStarted,
          CLASS_TARGET);
    }
  }

  /** Whether the steps run at startup (SAMPLE_HEAVY_WARMUP). */
  public boolean enabled() {
    return enabled;
  }

  /** Loaded classes when the context had refreshed (the "Started ... in" moment), -1 before. */
  public long loadedAtStarted() {
    return loadedAtStarted;
  }

  /** Summary for GET /api/runtime and GET /api/heavy/startup. */
  public Map<String, Object> describe() {
    Map<String, Object> info = new LinkedHashMap<>();
    info.put("heavyWarmup", enabled ? (results.isEmpty() ? "pending" : "ok") : "off");
    info.put("startupLoadedClasses", loadedAtStarted);
    info.put("startupTotalLoadedClasses", totalLoadedAtStarted);
    info.put("startupClassTarget", CLASS_TARGET);
    info.put("startupClassTargetMet", loadedAtStarted >= CLASS_TARGET);
    info.put("heavyStepsMs", stepsMs);
    info.put("heavySteps", results);
    return info;
  }
}
