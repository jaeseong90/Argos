/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.boot;

import io.argos.agent.bootstrap.AgentControl;
import io.argos.agent.bootstrap.Tracer;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.LoadedConfig;
import io.argos.agent.core.export.AgentInfo;
import io.argos.agent.core.export.ExportWorker;
import io.argos.agent.core.export.ExporterContext;
import io.argos.agent.core.guard.AgentCounters;
import io.argos.agent.core.guard.SelfDiagnosticsCollector;
import io.argos.agent.core.guard.SelfProtection;
import io.argos.agent.core.trace.TraceRuntime;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.watch.MaxDurationEnforcer;
import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * The recording side as the start sequence sees it (10 §4.3 P5, P7): the tracer to install (advice
 * errors handled by {@link SelfProtection}), the self-diagnostics gauges of 18 §19 and the {@code
 * argos-sender} thread that runs the runtime's after-premain work ({@link
 * TraceRuntime#afterPremain()}), creates the exporter and drains the queues. Built from {@link
 * TraceRuntime#create(AgentConfig, AgentCounters, AgentControl, TracerImpl.ModuleErrors)}; nothing
 * here starts a thread before P7.
 */
final class Recording {
  private final TraceRuntime runtime;
  private final AgentConfig config;
  private final AgentInfo info;
  private final AgentCounters counters;
  private volatile ExportWorker sender;

  private Recording(
      TraceRuntime runtime, AgentConfig config, AgentInfo info, AgentCounters counters) {
    this.runtime = runtime;
    this.config = config;
    this.info = info;
    this.counters = counters;
  }

  /** P5: the runtime of {@code config}; advice errors go to {@code protection}. */
  static Recording create(
      AgentConfig config,
      AgentCounters counters,
      AgentControl control,
      final SelfProtection protection,
      AgentInfo info) {
    TraceRuntime runtime =
        TraceRuntime.create(
            config,
            counters,
            control,
            new TracerImpl.ModuleErrors() {
              @Override
              public void moduleError(int moduleId, Throwable error) {
                protection.adviceError(moduleId, error);
              }
            });
    return new Recording(runtime, config, info, counters);
  }

  Tracer tracer() {
    return runtime.tracer();
  }

  /** The recording runtime ({@code argos-metrics}, WO-16: queues, statistics, active registry). */
  TraceRuntime runtime() {
    return runtime;
  }

  /** The tracer implementation (its pool and Tomcat registries, WO-13), or null. */
  TracerImpl tracerImpl() {
    return runtime.tracerImpl();
  }

  /**
   * The {@code transaction.max-duration-ms} task of {@code argos-watch} over this runtime's active
   * registry (WO-12, 10 §6.7, §13.1).
   */
  MaxDurationEnforcer maxDurationEnforcer() {
    return new MaxDurationEnforcer(
        runtime.activeRegistry(), runtime.tracerImpl(), null, config.transactionMaxDurationMs());
  }

  /** The 18 §19 gauges of the runtime ({@code TraceRuntime.selfValues()}). */
  SelfDiagnosticsCollector.Gauges gauges() {
    return new SelfDiagnosticsCollector.Gauges() {
      @Override
      public Map<String, Long> values() {
        return runtime.selfValues();
      }
    };
  }

  /**
   * P7: starts {@code argos-sender}; the after-premain work and the exporter (the gRPC transport by
   * default, WO-11) run on that thread, never on the premain thread (C-79). {@code loaded} gives
   * the transport the effective token and the configuration hash, {@code modules} the module ids of
   * {@code AgentHello}, {@code diagnostics} the place for its self metrics.
   */
  void startSender(
      File logDirectory,
      LoadedConfig loaded,
      List<String> modules,
      SelfDiagnosticsCollector diagnostics) {
    ExportWorker worker =
        new ExportWorker(
            runtime,
            new ExporterContext(
                config,
                runtime.dictionary(),
                info,
                loaded,
                counters,
                runtime,
                modules,
                diagnostics),
            logDirectory,
            config.senderBatchSize(),
            config.senderFlushIntervalMs());
    sender = worker;
    worker.start();
  }

  /** Stops {@code argos-sender} after exporting what is left for at most {@code millis}. */
  void stopSender(long millis) {
    ExportWorker worker = sender;
    if (worker != null) {
      worker.stop(millis);
    }
  }

  /** Transactions handed to the exporter. */
  long exported() {
    ExportWorker worker = sender;
    return worker == null ? 0 : worker.exported();
  }
}
