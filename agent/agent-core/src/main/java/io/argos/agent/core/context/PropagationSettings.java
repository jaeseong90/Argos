/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.context;

import java.util.List;

/**
 * The {@code propagation.*} settings of 10 §15 as the tracer applies them (10 §8, F-A32, SEC-38):
 * immutable, replaced as a whole. Defaults: no excluded host, no host gets the {@code argos} {@code
 * tracestate} entry, an application's own outgoing {@code traceparent} is kept.
 */
public final class PropagationSettings {
  /** The documented defaults. */
  public static final PropagationSettings DEFAULTS =
      new PropagationSettings(HostGlobs.NONE, HostGlobs.NONE, true);

  private final HostGlobs excludeHosts;
  private final HostGlobs argosStateHosts;
  private final boolean preserveIncoming;

  public PropagationSettings(
      HostGlobs excludeHosts, HostGlobs argosStateHosts, boolean preserveIncoming) {
    this.excludeHosts = excludeHosts == null ? HostGlobs.NONE : excludeHosts;
    this.argosStateHosts = argosStateHosts == null ? HostGlobs.NONE : argosStateHosts;
    this.preserveIncoming = preserveIncoming;
  }

  /**
   * From the configuration lists ({@code propagation.exclude-hosts}, {@code argos-state.hosts}).
   */
  public static PropagationSettings of(
      List<String> excludeHosts, List<String> argosStateHosts, boolean preserveIncoming) {
    return new PropagationSettings(
        HostGlobs.of(excludeHosts), HostGlobs.of(argosStateHosts), preserveIncoming);
  }

  /** {@code propagation.exclude-hosts}: hosts that get neither header. */
  public HostGlobs excludeHosts() {
    return excludeHosts;
  }

  /** {@code propagation.argos-state.hosts}: hosts that get the {@code argos} entry. */
  public HostGlobs argosStateHosts() {
    return argosStateHosts;
  }

  /** {@code propagation.preserve-incoming}: keep an outgoing {@code traceparent} the app set. */
  public boolean preserveIncoming() {
    return preserveIncoming;
  }
}
