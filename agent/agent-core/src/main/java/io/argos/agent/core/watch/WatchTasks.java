/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.watch;

import io.argos.agent.core.command.SamplingControl;
import io.argos.agent.core.config.AgentConfig;
import io.argos.agent.core.config.AppPackages;
import io.argos.agent.core.config.RuntimeConfig;
import io.argos.agent.core.log.AgentLogger;
import io.argos.agent.core.trace.Sampler;
import io.argos.agent.core.trace.TraceRuntime;
import io.argos.agent.core.trace.TracerImpl;
import io.argos.agent.core.transport.AgentSession;
import io.argos.proto.v1.AgentEventType;

/**
 * The WO-19 tasks of the {@code argos-watch} tick (10 §13.1): the {@link SlowStackWatcher} and the
 * {@link OverheadMonitor} of a recording runtime, and the application packages of its tracer
 * ({@link #installAppPackages}). Built by {@code WatchThread} on its own thread when it starts,
 * never on the premain thread (C-79, T-NFR-30a: none of these classes loads during premain).
 */
public final class WatchTasks {
  private WatchTasks() {}

  /**
   * The slow-stack sampler and the overhead monitor of {@code runtime}, in this order; {@code
   * startConfig} is the configuration at agent start (the running one is read at each tick). With
   * {@code test.overhead-monitor=off} (development builds only, ADR-0010 C-240 (3)) the overhead
   * monitor is left out, so the profile {@code normal-rate} is never halved.
   */
  public static Runnable[] create(final TraceRuntime runtime, final AgentConfig startConfig) {
    installAppPackages(runtime, startConfig);
    SlowStackWatcher stacks =
        new SlowStackWatcher(runtime.activeRegistry(), runtime.tracerImpl(), startConfig);
    if (!overheadMonitorEnabled(startConfig)) {
      AgentLogger.info(
          "the CPU guard is off (argos.test.overhead-monitor=off, development build): the profile"
              + " normal-rate is never halved");
      return new Runnable[] {stacks};
    }
    OverheadMonitor overhead =
        new OverheadMonitor(
            null,
            null,
            Runtime.getRuntime().availableProcessors(),
            new OverheadMonitor.Lowering() {
              @Override
              public void lowered(boolean on) {
                RuntimeConfig running = RuntimeConfig.current();
                AgentConfig config = running == null ? startConfig : running.config();
                SamplingControl.shared(runtime, Sampler.of(config)).overheadLowered(on);
              }
            },
            new OverheadMonitor.Events() {
              @Override
              public void selfCpuHigh(String detail) {
                sessionEvent(detail);
              }
            });
    return new Runnable[] {stacks, overhead};
  }

  /**
   * False when {@code test.overhead-monitor=off}; the loader drops the key in release builds, so a
   * release agent always runs the monitor.
   */
  static boolean overheadMonitorEnabled(AgentConfig config) {
    return !config.testOverheadMonitorOff();
  }

  /**
   * The application packages of {@code error_top_frame_id} (WO-19, C-237 (6)) when nothing
   * installed them yet: the remote wiring does it on {@code argos-sender}, but an agent without the
   * gRPC transport (test exporters, benchmarks) has none. Inferring reads the main jar's manifest
   * once per JVM, so it runs here, on {@code argos-watch} at its start, never on the premain
   * thread.
   */
  static void installAppPackages(TraceRuntime runtime, AgentConfig startConfig) {
    TracerImpl tracer = runtime.tracerImpl();
    if (tracer == null || tracer.appPackagesKnown()) {
      return;
    }
    RuntimeConfig running = RuntimeConfig.current();
    AgentConfig config = running == null ? startConfig : running.config();
    if (config != null) {
      tracer.setAppPackagesIfUnset(AppPackages.of(config).packages());
    }
  }

  /**
   * {@code AgentEvent SELF_CPU_HIGH} to the collector session (11 §4.2); nothing without one (a
   * test exporter, a session not yet established) or in a jar without the transport.
   */
  static void sessionEvent(String detail) {
    try {
      AgentSession session = AgentSession.current();
      if (session != null) {
        session.event(AgentEventType.SELF_CPU_HIGH, "", detail);
      }
    } catch (LinkageError e) {
      // A build without the gRPC transport: the WARN of the monitor is all there is.
    }
  }
}
