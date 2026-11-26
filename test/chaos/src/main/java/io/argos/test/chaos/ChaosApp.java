/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.test.chaos;

import io.argos.agent.testing.launcher.RunningApp;
import io.argos.agent.testing.launcher.SelfDiagnostics;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * One application of the chaos run with its agent: the {@code apps} row it reports to ({@code
 * name}, {@code appId}), the running JVM ({@link RunningApp}: a local process or a container) and
 * how its agent log is read. The stack's main app (shop-boot3, instance {@code chaos-1}) is one;
 * scenarios that need an agent configured differently (T-CH-005 container with a full log disk,
 * T-CH-006 hosts file, T-CH-007 wrong certificate name) start their own ({@link
 * ChaosStack#startApp}) under their own application, so that their rows and instances never mix
 * with the main app's.
 */
public final class ChaosApp implements AutoCloseable {

  private final String name;
  private final int appId;
  private final RunningApp app;
  private final Supplier<AgentLog> log;
  private final boolean owned;

  ChaosApp(String name, int appId, RunningApp app, Supplier<AgentLog> log, boolean owned) {
    this.name = Objects.requireNonNull(name, "name");
    this.appId = appId;
    this.app = Objects.requireNonNull(app, "app");
    this.log = log != null ? log : () -> AgentLog.read(app.agentLogDir());
    this.owned = owned;
  }

  /** The same application whose agent log is read by {@code reader} (a container's tmpfs). */
  public ChaosApp withLog(Supplier<AgentLog> reader) {
    return new ChaosApp(name, appId, app, reader, owned);
  }

  /** {@code apps.name}. */
  public String name() {
    return name;
  }

  /** {@code apps.id}. */
  public int appId() {
    return appId;
  }

  /** The JVM. */
  public RunningApp app() {
    return app;
  }

  /** The base URL (the load generator's target). */
  public String baseUrl() {
    return app.baseUrl();
  }

  /** How long the JVM took from spawn to readiness. */
  public Duration readyTime() {
    return app.readyTime();
  }

  /** The agent's log so far. */
  public AgentLog agentLog() {
    return log.get();
  }

  /** The agent's counters of its self-diagnostics snapshot (development build, C-95). */
  public Map<String, Long> agentCounters() {
    try {
      SelfDiagnostics d = SelfDiagnostics.read(app.selfDiagnosticsFile());
      return d.available() ? d.counters() : Map.of();
    } catch (RuntimeException e) {
      return Map.of();
    }
  }

  /** Stops the JVM when this run started it for a scenario (the main app is the stack's). */
  @Override
  public void close() {
    if (owned) {
      app.close();
    }
  }

  @Override
  public String toString() {
    return name + " (app " + appId + ")";
  }
}
