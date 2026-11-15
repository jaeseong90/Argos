/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.agent.bootstrap.CaptureConfig;
import io.argos.agent.core.trace.IgnoreRules;
import io.argos.agent.core.trace.ServiceNames;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** HttpSettings: 10 §15 keys of the request exclusion rules, service cap and capture modes. */
class HttpSettingsTest {

  @Test
  @DisplayName("unset keys keep the documented defaults")
  void defaults() {
    AgentConfig config = AgentConfig.of(new HashMap<String, String>());
    IgnoreRules rules = HttpSettings.ignoreRules(config);
    assertThat(rules.reason("GET", "/actuator/health", null)).isEqualTo("path");
    assertThat(rules.reason("OPTIONS", "/x", null)).isEqualTo("method");
    CaptureConfig capture = HttpSettings.capture(config);
    assertThat(capture.remoteIpMode()).isEqualTo(CaptureConfig.REMOTE_IP_MASKED);
    assertThat(capture.errorMessageMax()).isEqualTo(1024);
    assertThat(config.warnings()).isEmpty();
    ServiceNames names = HttpSettings.serviceNames(config);
    for (int i = 0; i < ServiceNames.DEFAULT_MAX; i++) {
      names.admit("GET /" + i);
    }
    assertThat(names.admit("GET /new")).isEqualTo(ServiceNames.OTHER);
  }

  @Test
  @DisplayName("explicit values replace the defaults; an empty list disables a rule")
  void explicitValues() {
    Map<String, String> values = new HashMap<String, String>();
    values.put("instrumentation.ignore-paths", "/internal/**, /ping");
    values.put("instrumentation.ignore-methods", "");
    values.put("instrumentation.ignore-user-agents", "kube-probe/*");
    values.put("instrumentation.service-dictionary-max", "1");
    values.put("capture.remote-ip", "OFF");
    values.put("capture.error-message-max-length", "16");
    AgentConfig config = AgentConfig.of(values);
    IgnoreRules rules = HttpSettings.ignoreRules(config);
    assertThat(rules.reason("GET", "/ping", null)).isEqualTo("path");
    assertThat(rules.reason("GET", "/internal/x/y", null)).isEqualTo("path");
    assertThat(rules.reason("GET", "/actuator/health", null)).isNull();
    assertThat(rules.reason("OPTIONS", "/x", null)).isNull();
    assertThat(rules.reason("GET", "/x.css", null)).isEqualTo("extension");
    assertThat(rules.reason("GET", "/x", "kube-probe/1.30")).isEqualTo("user_agent");
    CaptureConfig capture = HttpSettings.capture(config);
    assertThat(capture.remoteIpMode()).isEqualTo(CaptureConfig.REMOTE_IP_OFF);
    assertThat(capture.errorMessageMax()).isEqualTo(16);
    ServiceNames names = HttpSettings.serviceNames(config);
    assertThat(names.admit("a")).isEqualTo("a");
    assertThat(names.admit("b")).isEqualTo(ServiceNames.OTHER);
  }

  @Test
  @DisplayName("an invalid capture.remote-ip value falls back to masked with a warning")
  void invalidRemoteIpMode() {
    Map<String, String> values = new HashMap<String, String>();
    values.put("capture.remote-ip", "partial");
    AgentConfig config = AgentConfig.of(values);
    CaptureConfig capture = HttpSettings.capture(config);
    assertThat(capture.remoteIpMode()).isEqualTo(CaptureConfig.REMOTE_IP_MASKED);
    assertThat(config.warnings()).singleElement().asString().contains("capture.remote-ip");
  }
}
