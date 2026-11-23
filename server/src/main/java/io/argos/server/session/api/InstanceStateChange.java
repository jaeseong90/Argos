/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.session.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * One instance state transition (12 §4.2), published through Spring's {@code
 * ApplicationEventPublisher} on the thread that decided it (a session stream, a data call or the
 * {@code argos-session-monitor} thread), synchronously: listeners must not block (the realtime bus
 * and the alert engine queue their work).
 *
 * <p>{@code reasons}: for {@code WARN} the WARN conditions ({@code clock_skew}, {@code drops},
 * {@code old_version}, {@code module_disabled}, {@code kill_switch}, {@code config_drift}, {@code
 * context_leak}); for {@code STALE} and {@code DOWN} {@code session_lost} (no heartbeat) or {@code
 * agent_bye} (the 5 minute grace after an {@code AgentBye} ended); for {@code UP} empty.
 *
 * @param instanceId {@code instances.id}
 * @param appId {@code apps.id}
 * @param env the application's environment
 * @param from the previous state
 * @param to the new state
 * @param reasons why (see above), never null
 * @param at server time of the transition
 */
public record InstanceStateChange(
    int instanceId,
    int appId,
    String env,
    InstanceState from,
    InstanceState to,
    Set<String> reasons,
    Instant at) {

  /** Validates and copies. */
  public InstanceStateChange {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    Objects.requireNonNull(at, "at");
    reasons = reasons == null ? Set.of() : Set.copyOf(reasons);
  }
}
