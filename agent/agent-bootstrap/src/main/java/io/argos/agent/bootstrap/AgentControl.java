/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

/**
 * Capability that lets the agent core change bootstrap state that advice code only reads: the
 * installed {@link Tracer}, the kill switch and the module states.
 *
 * <p>The core is defined by the agent class loader, so it cannot call package-private methods of
 * this package (10 §3 marks {@code Argos.install} and {@code ModuleHealth.disable} "core only").
 * {@link AgentBootstrap} creates the only instance and passes it to {@code AgentStarter}; there is
 * no public constructor or static accessor, so application code cannot obtain one.
 */
public final class AgentControl {

  AgentControl() {}

  /** P5: makes {@code tracer} the tracer that advice and helpers see through {@link Argos}. */
  public void installTracer(Tracer tracer) {
    Argos.install(tracer);
  }

  /** Kill switch, first stage ({@code enabled}, F-A15, 10 §12.2). */
  public void setEnabled(boolean enabled) {
    KillSwitch.ENABLED = enabled;
  }

  /** Sets the runtime state of a module ({@link ModuleHealth#ENABLED}, ...). */
  public void setModuleState(int moduleId, int state) {
    ModuleHealth.setState(moduleId, state);
  }

  /** Counts one advice or transform error of a module; returns the new count. */
  public int incrementModuleErrors(int moduleId) {
    return ModuleHealth.incrementErrors(moduleId);
  }

  /** Upper bound of entries per fallback {@link ContextStore} (bounded memory, N-02). */
  public void setContextStoreLimit(int maxEntries) {
    ContextStore.setMaxEntries(maxEntries);
  }
}
