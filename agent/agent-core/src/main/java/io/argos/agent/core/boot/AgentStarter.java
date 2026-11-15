/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.ArgosThread;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.ConfigInputs;
import io.argos.agent.core.config.ConfigLoader;
import io.argos.agent.core.config.LoadedConfig;
import io.argos.agent.core.export.AgentInfo;
import io.argos.agent.core.export.Exporters;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.guard.SelfDiagnosticsCollector;
import io.argos.agent.core.guard.SelfDiagnosticsFile;
import io.argos.agent.core.guard.SelfProtection;
import io.argos.agent.core.guard.TransformErrorListener;
import io.argos.agent.core.log.AgentLogger;
import io.argos.agent.core.masking.Masker;
import io.argos.agent.core.metric.MetricDiagnostics;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.instrumentation.api.InstrumentationModule;
import io.argos.agent.instrumentation.api.SafeMatchers;
import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * Core start sequence (10 §4.3 P3-P8), called by {@code AgentBootstrap} in the agent class loader
 * on the premain thread:
 *
 * <ol>
 *   <li>P3: the {@code argos-logger} thread starts and the shutdown hook is registered (it flushes
 *       the logger even when the start fails, WO-05 C-1);
 *   <li>P4: {@code ConfigLoader} (yml, {@code -Dargos.*}, {@code ARGOS_*}, token, release gate),
 *       then the log destination ({@code log.dir}, ADR-0009 C-76) and the configuration's warnings
 *       and errors;
 *   <li>P5: the recording runtime ({@code TraceRuntime}: tracer, queues, active registry), the
 *       tracer installed, capture modes, module states from {@code instrumentation.disabled};
 *   <li>P6: the agent builder and {@code installOn} (transformation recorder, error listener with
 *       {@link SelfProtection}, {@code instrumentation.exclude-classes});
 *   <li>P7: agent threads ({@code argos-sender}, {@code argos-watch}); premain does not wait;
 *   <li>P8: one INFO banner: version, commit, protocol, app and env, configuration hash, masked
 *       token, exporter, modules.
 * </ol>
 *
 * <p>Nothing transport-related runs on the premain thread (the exporter is created by {@code
 * argos-sender}, C-79). A failure turns the kill switch off and the application continues without
 * instrumentation (T-AG-044); the failure is written to the log file before premain returns, or,
 * without a log file, as the single stderr line {@code argos: agent disabled: <reason>} (ADR-0009
 * C-78). {@code debug.transform-timing=true} logs the time of every step and the agent class
 * loader's statistics (C-79).
 */
public final class AgentStarter {
  /** Time the shutdown hook may spend exporting what is left (10 §10.2). */
  static final long SHUTDOWN_EXPORT_MILLIS = 1000;

  /** Time the shutdown hook gives the logger to write what is queued. */
  static final long SHUTDOWN_LOG_MILLIS = 500;

  /** The ERROR of a failed start (T-AG-044). */
  static final String START_FAILED =
      "Argos agent failed to start; the application continues without instrumentation";

  /** Self-diagnostics file and transform list period (seconds, {@code argos-watch}). */
  static final int SELF_DIAGNOSTICS_PERIOD_SECONDS = 2;

  private AgentStarter() {}

  /**
   * {@link #start} for {@code AgentBootstrap} (another class loader): a public no-argument
   * constructor and a {@link Consumer} call instead of {@code Method.invoke}, which spins method
   * handles for this signature on JDK 18+ (ADR-0009 C-79). The argument array is {@code
   * {Instrumentation, File, String, AgentControl, Long}}.
   */
  public static final class Handoff implements Consumer<Object[]> {
    @Override
    public void accept(Object[] arguments) {
      start(
          (Instrumentation) arguments[0],
          (File) arguments[1],
          (String) arguments[2],
          (AgentControl) arguments[3],
          ((Long) arguments[4]).longValue());
    }
  }

  /** The parts of a started agent the shutdown hook stops; filled step by step. */
  static final class Running {
    volatile WatchThread watch;
    volatile Recording recording;
    volatile SelfDiagnosticsFile selfDiagnostics;
    volatile boolean failed;
  }

  /**
   * Runs P3-P8; never throws.
   *
   * @param inst the JVM instrumentation
   * @param agentJar the agent jar
   * @param agentArgs {@code -javaagent:jar=<args>}: {@code config=<path>} or nothing
   * @param control bootstrap capability (tracer, kill switch, module states, loader statistics)
   * @param premainStartNanos {@code System.nanoTime()} at the start of premain
   */
  public static void start(
      Instrumentation inst,
      File agentJar,
      String agentArgs,
      AgentControl control,
      long premainStartNanos) {
    StartTimer timer = new StartTimer(premainStartNanos, System.nanoTime(), classCounter(control));
    Running running = new Running();
    try {
      // P3: logger thread (records queue until the destination is known) and the shutdown hook.
      timer.begin(StartTimer.P3);
      AgentLogger.start();
      registerShutdownHook(running);
      AgentInfo info = agentInfo(control, agentJar);

      // P4: configuration (never throws), then the log destination it names.
      timer.begin(StartTimer.P4);
      LoadedConfig loaded =
          ConfigLoader.load(ConfigInputs.forAgent(agentJar, agentArgs, info.isReleaseBuild()));
      AgentConfig config = loaded.config();
      timer.begin(StartTimer.P3);
      String logDestination = AgentLogger.configure(config, agentJar);
      timer.begin(StartTimer.P4);
      reportConfiguration(loaded);
      control.setEnabled(config.enabled());

      run(inst, control, timer, running, loaded, info, logDestination);
    } catch (Throwable t) {
      fail(control, running, t);
    }
  }

  private static void run(
      Instrumentation inst,
      AgentControl control,
      StartTimer timer,
      Running running,
      LoadedConfig loaded,
      AgentInfo info,
      String logDestination) {
    AgentConfig config = loaded.config();

    // P5: recording runtime, tracer, capture modes, module states.
    timer.begin(StartTimer.P5);
    AgentCounters counters = new AgentCounters();
    SelfProtection protection =
        new SelfProtection(
            control,
            counters,
            config.instrumentationErrorThreshold(),
            config.instrumentationTransformErrorThreshold());
    Recording recording = Recording.create(config, counters, control, protection, info);
    running.recording = recording;
    installMasker(config);
    control.setCapture(config.capture());
    control.installTracer(recording.tracer());
    ClassLoader agentLoader = AgentStarter.class.getClassLoader();
    TestFaults faults = TestFaults.from(config);
    List<InstrumentationModule> modules = ModuleRegistry.load(agentLoader, config, control, faults);
    String[] moduleNames = new String[64];
    StringBuilder moduleList = new StringBuilder();
    List<String> moduleIds = new ArrayList<String>(modules.size());
    for (InstrumentationModule module : modules) {
      moduleNames[module.moduleId()] = module.id();
      moduleList.append(moduleList.length() == 0 ? "" : ",").append(module.id());
      moduleIds.add(module.id());
    }
    protection.setModuleNames(moduleNames);

    // P6: agent builder and installOn.
    timer.begin(StartTimer.P6);
    String listPath = config.debugTransformList();
    String logPath = config.debugTransformLog();
    final TransformRecorder recorder =
        new TransformRecorder(
            listPath == null ? null : new File(listPath),
            logPath == null ? null : new File(logPath),
            config.debugTransformTiming(),
            moduleNames);
    SafeMatchers.setErrorSink(new MatcherErrors(recorder, counters));
    TransformErrorListener errors =
        new TransformErrorListener(protection, recorder.errorRecorder());
    if (!modules.isEmpty()) {
      LoaderState loaderState = new LoaderState();
      InstrumentationInstaller.install(
          inst,
          agentLoader,
          modules,
          config.instrumentationExcludeClasses(),
          recorder,
          errors,
          new HelperInjector(inst, agentLoader, loaderState, faults),
          loaderState,
          faults);
    }

    // P7: agent threads (premain does not wait for them).
    timer.begin(StartTimer.P7);
    SelfDiagnosticsCollector diagnostics =
        new SelfDiagnosticsCollector(recording.gauges(), counters, moduleNames);
    final WatchThread watch = new WatchThread();
    watch.every(
        SELF_DIAGNOSTICS_PERIOD_SECONDS,
        new Runnable() {
          @Override
          public void run() {
            recorder.writeIfChanged();
          }
        });
    watch.every(
        5,
        new Runnable() {
          @Override
          public void run() {
            ContextStore.expungeAll();
          }
        });
    // WO-12: transaction.max-duration-ms (10 §6.7, §13.1) on every 1 s tick.
    watch.every(1, recording.maxDurationEnforcer());
    final SelfDiagnosticsFile selfDiagnostics = selfDiagnosticsFile(config, diagnostics);
    if (selfDiagnostics != null) {
      final TracerImpl tracer = recording.tracerImpl();
      watch.every(
          SELF_DIAGNOSTICS_PERIOD_SECONDS,
          new Runnable() {
            @Override
            public void run() {
              writeSelfDiagnostics(selfDiagnostics, tracer);
            }
          });
      running.selfDiagnostics = selfDiagnostics;
    }
    recording.startSender(AgentLogger.logDirectory(), loaded, moduleIds, diagnostics);
    watch.start();
    running.watch = watch;

    // P8: banner.
    timer.begin(StartTimer.P8);
    String[] premainClasses = control.stopPremainRecording();
    double premainMillis = timer.premainMillis();
    diagnostics.premainMillis(premainMillis);
    AgentLogger.info(
        "Argos agent {} (commit {}, protocol {}) started in {} ms: app={} env={} config={}"
            + " token={} exporter={} modules=[{}] enabled={} jvm={} ({}) log={}",
        info.version(),
        info.commit(),
        info.protocolVersion(),
        StartTimer.format(premainMillis),
        config.appName() == null ? "-" : config.appName(),
        config.appEnv(),
        loaded.configHash(),
        loaded.token().isPresent() ? loaded.token().masked() : "none",
        Exporters.configuredName(config),
        moduleList.toString(),
        config.enabled(),
        System.getProperty("java.version"),
        System.getProperty("java.vendor"),
        logDestination);
    if (config.debugTransformTiming()) {
      AgentLogger.info(
          "{}",
          timer.describe(
              control.classLoaderStatistics(),
              premainClasses == null ? -1 : premainClasses.length));
    }
    if (selfDiagnostics != null) {
      selfDiagnostics.writePremainClasses(premainClasses);
    }
    AgentLogger.startCompleted();
  }

  /**
   * The agent class loader's defined-class count for the step timing (C-79); unknown outside the
   * agent jar (no anonymous class allocation per step, no lambda on the start path).
   */
  static StartTimer.ClassCounter classCounter(final AgentControl control) {
    if (control.classLoaderStatistics() == null) {
      return StartTimer.NO_COUNTER;
    }
    return new StartTimer.ClassCounter() {
      @Override
      public long definedClasses() {
        long[] statistics = control.classLoaderStatistics();
        return statistics == null ? -1 : statistics[0];
      }
    };
  }

  /**
   * P5: publishes the masker of {@code capture.mask-patterns} (16 §6.6 defaults followed by the
   * user patterns). Without user patterns nothing is loaded here: {@link Masker#current()} is the
   * default set, compiled on first use (C-79). The loader already dropped invalid patterns, so a
   * warning here is unusual.
   */
  static void installMasker(AgentConfig config) {
    List<String> patterns = config.captureMaskPatterns();
    if (patterns == null || patterns.isEmpty()) {
      return;
    }
    List<String> warnings = new ArrayList<String>();
    Masker.install(Masker.of(patterns, warnings));
    for (String warning : warnings) {
      AgentLogger.warn("configuration: {}", warning);
    }
  }

  /** Logs the configuration's notes (INFO), warnings (WARN) and errors (ERROR) once (P4). */
  private static void reportConfiguration(LoadedConfig loaded) {
    for (String note : loaded.notes()) {
      AgentLogger.info("configuration: {}", note);
    }
    for (String warning : loaded.warnings()) {
      AgentLogger.warn("configuration: {}", warning);
    }
    for (String error : loaded.errors()) {
      AgentLogger.error("configuration: {}", error);
    }
  }

  /**
   * The agent's identity from the manifest of the jar the agent class loader keeps open (no second
   * open, C-79); the release-build marker fails closed (T-BLD-010, C-89).
   */
  static AgentInfo agentInfo(AgentControl control, File agentJar) {
    Manifest manifest = control.agentManifest();
    if (manifest == null) {
      // Outside a packaged jar (unit tests) or an unreadable manifest: AgentInfo decides.
      return AgentInfo.fromJar(agentJar);
    }
    Attributes main = manifest.getMainAttributes();
    // A missing Implementation-Version is "unknown", which counts as a release build.
    return new AgentInfo(
        attribute(main, "Implementation-Version"),
        attribute(main, "Argos-Commit"),
        attribute(main, "Argos-Protocol-Version"));
  }

  private static String attribute(Attributes attributes, String name) {
    String value = attributes.getValue(name);
    return value == null || value.trim().isEmpty() ? AgentInfo.UNKNOWN : value.trim();
  }

  /**
   * One {@code argos-watch} write of the self-diagnostics file with the pool and connector
   * registries of {@code tracer} (WO-13, development file only). Their getters run here, on the
   * agent thread; the registry classes load on the first call, never on the premain thread (C-79).
   */
  static void writeSelfDiagnostics(SelfDiagnosticsFile file, TracerImpl tracer) {
    file.write(
        tracer == null ? null : MetricDiagnostics.members(tracer.pools(), tracer.connectors()));
  }

  /** {@code test.selfdiag-file} (development builds only, C-95), or null. */
  static SelfDiagnosticsFile selfDiagnosticsFile(
      AgentConfig config, SelfDiagnosticsCollector diagnostics) {
    String path = config.testSelfdiagFile();
    return path == null || path.trim().isEmpty()
        ? null
        : new SelfDiagnosticsFile(new File(path.trim()), diagnostics);
  }

  /**
   * A failed start (T-AG-044, C-78): the kill switch goes off, started agent threads stop, the
   * failure reaches the log file before this method returns, or the single stderr line when there
   * is no log file. Never throws.
   */
  static void fail(AgentControl control, Running running, Throwable error) {
    try {
      control.setEnabled(false);
      // The premain class recording ends here too (it would otherwise go on on this thread).
      control.stopPremainRecording();
    } catch (Throwable ignored) {
      // The bootstrap capability cannot fail; nothing else to do.
    }
    running.failed = true;
    stopThreads(running, 0);
    AgentLogger.startFailed(START_FAILED, error);
  }

  private static void registerShutdownHook(final Running running) {
    Runtime.getRuntime()
        .addShutdownHook(
            new ArgosThread(
                new Runnable() {
                  @Override
                  public void run() {
                    shutdown(running);
                  }
                },
                "argos-shutdown"));
  }

  /** The shutdown hook: stops the agent threads, exports what is left, flushes the log. */
  static void shutdown(Running running) {
    try {
      stopThreads(running, SHUTDOWN_EXPORT_MILLIS);
      SelfDiagnosticsFile selfDiagnostics = running.selfDiagnostics;
      if (selfDiagnostics != null && !running.failed) {
        selfDiagnostics.write();
      }
      Recording recording = running.recording;
      if (recording != null && !running.failed) {
        AgentLogger.info("Argos agent stopped: {} transaction(s) exported", recording.exported());
      }
    } catch (Throwable t) {
      AgentLogger.warn("agent shutdown failed: {}", t.getClass().getName());
    } finally {
      AgentLogger.shutdown(SHUTDOWN_LOG_MILLIS);
    }
  }

  private static void stopThreads(Running running, long exportMillis) {
    WatchThread watch = running.watch;
    if (watch != null) {
      try {
        watch.stop();
      } catch (Throwable ignored) {
        // best effort at shutdown or after a failed start
      }
    }
    Recording recording = running.recording;
    if (recording != null) {
      try {
        recording.stopSender(exportMillis);
      } catch (Throwable ignored) {
        // best effort at shutdown or after a failed start
      }
    }
  }

  /** Type resolution failures of the safe matchers (10 §5.1, §14 {@code reason=circularity}). */
  static final class MatcherErrors implements SafeMatchers.ErrorSink {
    private final TransformRecorder recorder;
    private final AgentCounters counters;

    MatcherErrors(TransformRecorder recorder, AgentCounters counters) {
      this.recorder = recorder;
      this.counters = counters;
    }

    @Override
    public void onResolutionError(String typeName, Throwable error) {
      recorder.recordResolutionError(typeName, error);
      if (error instanceof ClassCircularityError) {
        counters.incTransformError("circularity");
      }
      if (AgentLogger.isDebugEnabled()) {
        AgentLogger.debug("type resolution failed in a matcher: {}: {}", typeName, error);
      }
    }
  }
}
