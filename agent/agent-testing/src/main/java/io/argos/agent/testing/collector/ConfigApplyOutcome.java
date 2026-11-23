/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.collector;

import java.util.List;
import java.util.Map;

/**
 * The agent's answer to a {@link FakeCollector#pushConfig} ({@code ConfigApplyResult}, 11 §5, 10
 * §12.2).
 *
 * @param changeId the push's change id
 * @param appliedKeys keys the agent applied
 * @param rejected rejected keys and their reject code ({@code NOT_ALLOWED}, {@code UNKNOWN_KEY},
 *     ...)
 * @param retransformedClasses classes retransformed for the change
 * @param effectiveConfigHash {@code effective_config_hash} after the change (empty when the agent
 *     sent none)
 * @param durationMs {@code duration_ms}: the agent's own apply time
 * @param messages {@code RejectedKey.message} by rejected key (keys with an empty message are left
 *     out)
 * @param roundTripMs measured by the collector: from sending the {@code ConfigPush} to receiving
 *     this result (the agent-side contribution to N-25, together with {@code durationMs})
 */
public record ConfigApplyOutcome(
    String changeId,
    List<String> appliedKeys,
    Map<String, String> rejected,
    int retransformedClasses,
    String effectiveConfigHash,
    long durationMs,
    Map<String, String> messages,
    long roundTripMs) {
  public ConfigApplyOutcome {
    appliedKeys = List.copyOf(appliedKeys);
    rejected = Map.copyOf(rejected);
    effectiveConfigHash = effectiveConfigHash == null ? "" : effectiveConfigHash;
    messages = Map.copyOf(messages);
  }

  /** The reject message of {@code key}, or empty when the key was not rejected with one. */
  public String message(String key) {
    return messages.getOrDefault(key, "");
  }
}
