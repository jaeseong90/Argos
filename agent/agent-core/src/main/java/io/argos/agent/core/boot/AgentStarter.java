/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.ArgosThread;
import io.argos.agent.bootstrap.ContextStore;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.ConfigKeys;
import io.argos.agent.core.config.HttpSettings;
import io.argos.agent.core.export.AgentInfo;
import io.argos.agent.core.export.ExportWorker;
import io.argos.agent.core.export.ExporterContext;
import io.argos.agent.core.export.Exporters;
import io.argos.agent.core.export.TransactionQueue;
import io.argos.agent.core.log.AgentLogger;
import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.trace.Transaction;
import io.argos.agent.core.trace.TxIdGenerator;
import io.argos.agent.instrumentation.api.InstrumentationModule;
import io.argos.agent.instrumentation.api.SafeMatchers;
import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Core start sequence (10 §4.3 P3-P8), called by {@code AgentBootstrap} in the agent class loader
 * on the premain thread. Everything slow (exporter creation, I/O) happens on agent threads; premain
 * only installs the transformer. A failure is logged, the kill switch is turned off and the
 * application continues without instrumentation (T-AG-044).
 */
public final class AgentStarter {
  /** Time the shutdown hook may spend exporting what is left (10 §10.2). */
  static final long SHUTDOWN_EXPORT_MILLIS = 1000;

  private AgentStarter() {}

  /**
   * Runs P3-P8; never throws.
   *
   * @param inst the JVM instrumentation
   * @param agentJar the agent jar
   * @param agentArgs {@code -javaagent:jar=<args>} (not used: configuration comes from {@code
   *     -Dargos.*} and {@code ARGOS_*})
   * @param control bootstrap capability (tracer, kill switch, module states)
   * @param premainStartNanos {@code System.nanoTime()} at the start of premain
   */
  public static void start(
      Instrumentation inst,
      File agentJar,
      String agentArgs,
      AgentControl control,
      long premainStartNanos) {
    long p3 = System.nanoTime();
    AgentInfo info;
    try {
      info = AgentInfo.fromJar(agentJar);
    } catch (Throwable t) {
      // Fail-closed (T-BLD-010): a packaged jar whose manifest cannot be read is a release build.
      info = AgentInfo.unreadable(agentJar);
    }
    AgentConfig config;
    try {
      // T-BLD-010: a release build ignores argos.test.* (the jar's Implementation-Version; only a
      // -SNAPSHOT version enables the test hooks, a missing or unreadable one does not).
      config = AgentConfig.fromSystem(info.isReleaseBuild());
    } catch (Throwable t) {
      config = AgentConfig.of(java.util.Collections.<String, String>emptyMap());
    }
    String logDestination;
    try {
      logDestination = AgentLogger.init(config, agentJar);
    } catch (Throwable t) {
      logDestination = "unavailable (" + t + ")";
    }
    try {
      run(inst, agentJar, agentArgs, control, config, info, logDestination, premainStartNanos, p3);
    } catch (Throwable t) {
      control.setEnabled(false);
      AgentLogger.error(
          "Argos agent failed to start; the application continues without instrumentation", t);
    }
  }

  private static void run(
      Instrumentation inst,
      File agentJar,
      String agentArgs,
      AgentControl control,
      AgentConfig config,
      AgentInfo info,
      String logDestination,
      long premainStartNanos,
      long p3) {
    long p4 = System.nanoTime();
    for (String warning : config.warnings()) {
      AgentLogger.warn("configuration: {}", warning);
    }
    if (agentArgs != null && !agentArgs.trim().isEmpty()) {
      AgentLogger.warn(
          "agent arguments after '-javaagent:...=' are ignored; configure with -Dargos.<key> or"
              + " ARGOS_<KEY>");
    }
    boolean enabled = config.getBoolean(ConfigKeys.ENABLED, true);
    control.setEnabled(enabled);

    // P5: tracer, export queue, module states.
    long p5 = System.nanoTime();
    Dictionary dictionary = new Dictionary();
    final TransactionQueue queue =
        new TransactionQueue(
            config.getInt(ConfigKeys.TRANSACTION_CAPACITY, 20_000, 1, 1_000_000),
            config.getLong(ConfigKeys.BUFFER_MAX_BYTES, 32L * 1024 * 1024, 1024, 1L << 34));
    TracerImpl tracer =
        new TracerImpl(
            dictionary,
            new TracerImpl.Sink() {
              @Override
              public void accept(Transaction tx) {
                queue.offer(tx);
              }
            },
            control,
            new TxIdGenerator(),
            config.getInt(ConfigKeys.MAX_STEPS, 1000, 1, 100_000),
            config.getInt(ConfigKeys.INSTRUMENTATION_ERROR_THRESHOLD, 100, 1, Integer.MAX_VALUE));
    List<String> settingWarnings = new ArrayList<String>();
    tracer.setCapture(HttpSettings.capture(config, settingWarnings));
    tracer.setIgnoreRules(HttpSettings.ignoreRules(config));
    tracer.setServiceNames(HttpSettings.serviceNames(config));
    for (String warning : settingWarnings) {
      AgentLogger.warn("configuration: {}", warning);
    }
    control.installTracer(tracer);
    ClassLoader agentLoader = AgentStarter.class.getClassLoader();
    TestFaults faults = TestFaults.from(config);
    List<InstrumentationModule> modules = ModuleRegistry.load(agentLoader, config, control, faults);

    // P6: agent builder.
    long p6 = System.nanoTime();
    String[] moduleNames = new String[64];
    StringBuilder moduleList = new StringBuilder();
    for (InstrumentationModule module : modules) {
      moduleNames[module.moduleId()] = module.id();
      moduleList.append(moduleList.length() == 0 ? "" : ",").append(module.id());
    }
    String listPath = config.get(ConfigKeys.DEBUG_TRANSFORM_LIST, null);
    String logPath = config.get(ConfigKeys.DEBUG_TRANSFORM_LOG, null);
    final TransformRecorder recorder =
        new TransformRecorder(
            listPath == null ? null : new File(listPath),
            logPath == null ? null : new File(logPath),
            config.getBoolean(ConfigKeys.DEBUG_TRANSFORM_TIMING, false),
            moduleNames);
    SafeMatchers.setErrorSink(
        new SafeMatchers.ErrorSink() {
          @Override
          public void onResolutionError(String typeName, Throwable error) {
            recorder.recordResolutionError(typeName, error);
            if (AgentLogger.isDebugEnabled()) {
              AgentLogger.debug("type resolution failed in a matcher: {}: {}", typeName, error);
            }
          }
        });
    TransformErrorListener errors =
        new TransformErrorListener(
            control,
            config.getInt(ConfigKeys.INSTRUMENTATION_TRANSFORM_ERROR_THRESHOLD, 10, 1, 1_000_000),
            moduleNames,
            recorder);
    if (!modules.isEmpty()) {
      LoaderState loaderState = new LoaderState();
      InstrumentationInstaller.install(
          inst,
          agentLoader,
          modules,
          config.getList(ConfigKeys.INSTRUMENTATION_EXCLUDE_CLASSES),
          recorder,
          errors,
          new HelperInjector(inst, agentLoader, loaderState, faults),
          loaderState,
          faults);
    }

    // P7: agent threads (premain does not wait for them).
    long p7 = System.nanoTime();
    File logDirectory = AgentLogger.directory(config, agentJar);
    final ExportWorker sender =
        new ExportWorker(
            queue,
            new ExporterContext(config, dictionary, info),
            logDirectory,
            config.getInt(ConfigKeys.SENDER_BATCH_SIZE, 500, 1, 100_000),
            config.getInt(ConfigKeys.SENDER_FLUSH_INTERVAL_MS, 100, 1, 60_000));
    sender.start();
    final WatchThread watch = new WatchThread();
    watch.every(
        2,
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
    watch.start();
    Runtime.getRuntime()
        .addShutdownHook(
            new ArgosThread(
                new Runnable() {
                  @Override
                  public void run() {
                    shutdown(watch, sender);
                  }
                },
                "argos-shutdown"));

    // P8: banner.
    long p8 = System.nanoTime();
    AgentLogger.info(
        "Argos agent {} (commit {}, protocol {}) started in {} ms: app={} env={} exporter={}"
            + " modules=[{}] enabled={} jvm={} ({}) log={}",
        info.version(),
        info.commit(),
        info.protocolVersion(),
        millis(p8 - premainStartNanos),
        config.get(ConfigKeys.APP_NAME, "-"),
        config.get(ConfigKeys.APP_ENV, "default"),
        Exporters.configuredName(config),
        moduleList,
        enabled,
        System.getProperty("java.version"),
        System.getProperty("java.vendor"),
        logDestination);
    if (config.getBoolean(ConfigKeys.DEBUG_TRANSFORM_TIMING, false)
        || AgentLogger.isDebugEnabled()) {
      AgentLogger.info(
          "start timing (ms): P0-P2 {}, P3 logger {}, P4 config {}, P5 tracer {}, P6 builder {},"
              + " P7 threads {}",
          millis(p3 - premainStartNanos),
          millis(p4 - p3),
          millis(p5 - p4),
          millis(p6 - p5),
          millis(p7 - p6),
          millis(p8 - p7));
    }
  }

  static void shutdown(WatchThread watch, ExportWorker sender) {
    try {
      watch.stop();
      sender.stop(SHUTDOWN_EXPORT_MILLIS);
      AgentLogger.info("Argos agent stopped: {} transaction(s) exported", sender.exported());
    } catch (Throwable t) {
      AgentLogger.warn("agent shutdown: {}", t.toString());
    } finally {
      AgentLogger.shutdown(500);
    }
  }

  private static String millis(long nanos) {
    long micros = TimeUnit.NANOSECONDS.toMicros(nanos);
    return (micros / 1000) + "." + String.format(java.util.Locale.ROOT, "%03d", micros % 1000);
  }
}
