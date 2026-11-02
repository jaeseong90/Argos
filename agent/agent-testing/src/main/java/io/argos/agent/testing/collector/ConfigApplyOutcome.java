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
 */
public record ConfigApplyOutcome(
    String changeId,
    List<String> appliedKeys,
    Map<String, String> rejected,
    int retransformedClasses) {
  public ConfigApplyOutcome {
    appliedKeys = List.copyOf(appliedKeys);
    rejected = Map.copyOf(rejected);
  }
}
