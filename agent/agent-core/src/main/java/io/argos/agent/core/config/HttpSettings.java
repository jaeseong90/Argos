/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.core.context.PropagationSettings;
import io.argos.agent.core.trace.IgnoreRules;
import io.argos.agent.core.trace.ServiceNames;

/**
 * The HTTP-related settings of 10 §15 as the objects that use them: the request exclusion rules
 * ({@code instrumentation.ignore-*}), the service name cap and the capture modes read by the
 * helpers. Values, defaults and validation come from {@link AgentConfig}.
 */
public final class HttpSettings {
  private HttpSettings() {}

  /**
   * {@code instrumentation.ignore-paths/-extensions/-user-agents/-methods}: an unset key keeps the
   * documented default; a key set to an empty value disables that rule.
   */
  public static IgnoreRules ignoreRules(AgentConfig config) {
    return IgnoreRules.of(
        config.instrumentationIgnorePaths(),
        config.instrumentationIgnoreExtensions(),
        config.instrumentationIgnoreUserAgents(),
        config.instrumentationIgnoreMethods());
  }

  /**
   * {@code propagation.exclude-hosts}, {@code propagation.argos-state.hosts} and {@code
   * propagation.preserve-incoming} (10 §8, §15, SEC-38; WO-14). Local keys only (remote N): read
   * once at start.
   */
  public static PropagationSettings propagation(AgentConfig config) {
    return PropagationSettings.of(
        config.propagationExcludeHosts(),
        config.propagationArgosStateHosts(),
        config.propagationPreserveIncoming());
  }

  /** {@code instrumentation.service-dictionary-max} (F-A29). */
  public static ServiceNames serviceNames(AgentConfig config) {
    return new ServiceNames(config.instrumentationServiceDictionaryMax());
  }

  /** Every {@code capture.*} key and {@code async.step} ({@link AgentConfig#capture()}). */
  public static CaptureConfig capture(AgentConfig config) {
    return config.capture();
  }
}
