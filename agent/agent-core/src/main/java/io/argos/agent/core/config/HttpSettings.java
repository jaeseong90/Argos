/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.core.trace.IgnoreRules;
import io.argos.agent.core.trace.ServiceNames;
import java.util.List;
import java.util.Locale;

/**
 * Reads the HTTP-related settings of 10 §15 that WO-02 implements: the request exclusion rules
 * ({@code instrumentation.ignore-*}), the service name cap and the capture modes used by the
 * servlet helper ({@code capture.remote-ip}, error message length, stack depth). The other capture
 * keys keep their defaults until their modules arrive (WO-12, WO-14).
 */
public final class HttpSettings {
  private HttpSettings() {}

  /**
   * {@code instrumentation.ignore-paths/-extensions/-user-agents/-methods}: an unset key keeps the
   * documented default; a key set to an empty value disables that rule.
   */
  public static IgnoreRules ignoreRules(AgentConfig config) {
    return IgnoreRules.of(
        list(config, ConfigKeys.INSTRUMENTATION_IGNORE_PATHS, IgnoreRules.DEFAULT_PATHS),
        list(config, ConfigKeys.INSTRUMENTATION_IGNORE_EXTENSIONS, IgnoreRules.DEFAULT_EXTENSIONS),
        list(
            config, ConfigKeys.INSTRUMENTATION_IGNORE_USER_AGENTS, IgnoreRules.DEFAULT_USER_AGENTS),
        list(config, ConfigKeys.INSTRUMENTATION_IGNORE_METHODS, IgnoreRules.DEFAULT_METHODS));
  }

  /** {@code instrumentation.service-dictionary-max} (F-A29). */
  public static ServiceNames serviceNames(AgentConfig config) {
    return new ServiceNames(
        config.getInt(
            ConfigKeys.INSTRUMENTATION_SERVICE_DICTIONARY_MAX,
            ServiceNames.DEFAULT_MAX,
            1,
            1_000_000));
  }

  /**
   * Capture modes: {@code capture.remote-ip} (full, masked default, off), two limits and {@code
   * async.step} (executor ASYNC steps, default off).
   */
  public static CaptureConfig capture(AgentConfig config, List<String> warnings) {
    CaptureConfig d = CaptureConfig.DEFAULT;
    int remoteIp = d.remoteIpMode();
    String mode = config.get(ConfigKeys.CAPTURE_REMOTE_IP, null);
    if (mode != null) {
      String normalized = mode.trim().toLowerCase(Locale.ROOT);
      if (normalized.equals("full")) {
        remoteIp = CaptureConfig.REMOTE_IP_FULL;
      } else if (normalized.equals("masked")) {
        remoteIp = CaptureConfig.REMOTE_IP_MASKED;
      } else if (normalized.equals("off")) {
        remoteIp = CaptureConfig.REMOTE_IP_OFF;
      } else {
        warnings.add(
            "invalid value '" + mode + "' for capture.remote-ip (full, masked, off); using masked");
      }
    }
    return new CaptureConfig(
            d.queryStringMode(),
            d.queryStringWhitelist(),
            d.headerWhitelist(),
            remoteIp,
            d.trustedProxies(),
            d.userIdMode(),
            d.userIdSource(),
            d.sqlParamMode(),
            d.sqlParamWhitelistColumns(),
            config.getInt(ConfigKeys.CAPTURE_ERROR_MESSAGE_MAX, d.errorMessageMax(), 0, 65_536),
            config.getInt(ConfigKeys.CAPTURE_STACK_DEPTH, d.stackDepth(), 0, 64))
        .withAsyncStep(config.getBoolean(ConfigKeys.ASYNC_STEP, d.asyncStep()));
  }

  private static List<String> list(AgentConfig config, String key, List<String> defaults) {
    return config.isSet(key) ? config.getList(key) : defaults;
  }
}
