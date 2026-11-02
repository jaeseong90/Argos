/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

/**
 * Kill switch, first stage (F-A15, 10 §12.2): {@code enabled=false} makes every advice return at
 * once ({@link Argos#skip(int)}); transformations stay installed and the agent can be re-enabled.
 * The second stage, {@code -Dargos.agent.disabled=true}, installs nothing (10 §4.3 P0).
 */
public final class KillSwitch {
  /** Read by every advice; written by the core through {@link AgentControl#setEnabled}. */
  @SuppressWarnings("NonFinalStaticField") // a volatile flag by design (10 §3)
  public static volatile boolean ENABLED = true;

  private KillSwitch() {}
}
