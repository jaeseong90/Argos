/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

import java.io.IOException;
import java.util.jar.Manifest;

/**
 * Capability that lets the agent core change bootstrap state that advice code only reads: the
 * installed {@link Tracer}, the kill switch and the module states.
 *
 * <p>The core is defined by the agent class loader, so it cannot call package-private methods of
 * this package (10 §3 marks {@code Argos.install} and {@code ModuleHealth.disable} "core only").
 * {@link AgentBootstrap} creates the only instance and passes it to {@code AgentStarter}; there is
 * no public constructor or static accessor, so application code cannot obtain one.
 *
 * <p>Addition to 10 §3 (WO-02 report item D-2, ADR-0010 C-93): 10 §3 gives the core package-private
 * entry points ({@code Argos.install}, {@code ModuleHealth.disable}), which a class of another
 * class loader cannot call; this capability object is the only channel from the core to bootstrap
 * state: tracer, kill switch, module states and error counters, capture modes and the fallback
 * store bound.
 */
public final class AgentControl {
  /** The loader of the core, null when the core runs outside the agent jar (unit tests). */
  private final AgentClassLoader loader;

  AgentControl() {
    this(null);
  }

  AgentControl(AgentClassLoader loader) {
    this.loader = loader;
  }

  /**
   * Start-path statistics of the agent class loader (ADR-0009 C-79, 10 §4.3 measurement): {@code
   * {classes defined, class bytes read, nanoseconds reading, nanoseconds defining}}; null when the
   * core was not loaded by the agent class loader (missing, not zero: C-4).
   */
  public long[] classLoaderStatistics() {
    return loader == null ? null : loader.statistics();
  }

  /**
   * Ends the recording of the classes the agent class loader defined on the premain thread (C-79)
   * and returns their names in definition order; null when there is no agent class loader. Called
   * once by the core at the end of the start sequence (P8); the loader then drops the names, so a
   * later call returns an empty array.
   */
  public String[] stopPremainRecording() {
    return loader == null ? null : loader.stopRecording();
  }

  /**
   * The manifest of the agent jar read from the jar the agent class loader keeps open (the start
   * sequence does not open the jar again, C-79); null when there is none or it cannot be read.
   */
  public Manifest agentManifest() {
    if (loader == null) {
      return null;
    }
    try {
      return loader.manifest();
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  /** P5: makes {@code tracer} the tracer that advice and helpers see through {@link Argos}. */
  public void installTracer(Tracer tracer) {
    Argos.install(tracer);
  }

  /** Kill switch, first stage ({@code enabled}, F-A15, 10 §12.2). */
  public void setEnabled(boolean enabled) {
    KillSwitch.ENABLED = enabled;
  }

  /**
   * Sets the runtime state of a module ({@link ModuleHealth#ENABLED}, {@link
   * ModuleHealth#DISABLED_BY_CONFIG}, {@link ModuleHealth#DISABLED_BY_ERRORS}, {@link
   * ModuleHealth#DISABLED_BY_REMOTE}); 10 §3 {@code ModuleHealth.disable(moduleId, reason)}. The
   * reason is logged and reported by the core (WARN once, self-diagnostics, {@code
   * InstanceUpdate}), so it is not stored here.
   */
  public void setModuleState(int moduleId, int state) {
    ModuleHealth.setState(moduleId, state);
  }

  /** Counts one advice or transform error of a module; returns the new count. */
  public int incrementModuleErrors(int moduleId) {
    return ModuleHealth.incrementErrors(moduleId);
  }

  /**
   * Installs the capture modes that {@link Tracer#capture()} and {@link CaptureConfig#current()}
   * return (10 §3 "동적 설정 적용 시 새 객체로 교체"); null restores {@link CaptureConfig#DEFAULT}.
   */
  public void setCapture(CaptureConfig capture) {
    CaptureConfig.install(capture);
  }

  /** Upper bound of entries per fallback {@link ContextStore} (bounded memory, N-02). */
  public void setContextStoreLimit(int maxEntries) {
    ContextStore.setMaxEntries(maxEntries);
  }
}
