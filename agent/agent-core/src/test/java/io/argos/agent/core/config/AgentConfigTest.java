/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Configuration sources and conversions (10 §15, 02 §7). */
class AgentConfigTest {

  @Test
  @DisplayName("-Dargos.<key> is read, ARGOS_<KEY> wins over it, other properties are ignored")
  void sourcesAndPrecedence() {
    Properties properties = new Properties();
    properties.setProperty("argos.app.name", "shop");
    properties.setProperty("argos.log.max-file-mb", "20");
    properties.setProperty("user.name", "someone");
    Map<String, String> environment = new HashMap<String, String>();
    environment.put("ARGOS_LOG_MAX_FILE_MB", "30");
    environment.put("ARGOS_INSTRUMENTATION_DISABLED", "jdbc, servlet ,");
    AgentConfig config = AgentConfig.load(properties, environment);
    assertThat(config.get(ConfigKeys.APP_NAME, null)).isEqualTo("shop");
    assertThat(config.source(ConfigKeys.APP_NAME)).isEqualTo(AgentConfig.SOURCE_PROPERTY);
    assertThat(config.getInt(ConfigKeys.LOG_MAX_FILE_MB, 10, 1, 1024)).isEqualTo(30);
    assertThat(config.source(ConfigKeys.LOG_MAX_FILE_MB)).isEqualTo(AgentConfig.SOURCE_ENVIRONMENT);
    assertThat(config.getList(ConfigKeys.INSTRUMENTATION_DISABLED))
        .containsExactly("jdbc", "servlet");
    assertThat(config.source(ConfigKeys.LOG_DIR)).isEqualTo(AgentConfig.SOURCE_DEFAULT);
    assertThat(config.warnings()).isEmpty();
    assertThat(ConfigKeys.environmentName("log.max-file-mb")).isEqualTo("ARGOS_LOG_MAX_FILE_MB");
  }

  @Test
  @DisplayName("unknown keys and invalid values are reported; invalid values use the default")
  void warnings() {
    Properties properties = new Properties();
    properties.setProperty("argos.no.such.key", "1");
    properties.setProperty("argos.log.max-files", "many");
    properties.setProperty("argos.enabled", "maybe");
    properties.setProperty("argos.sampling.profile.max-steps", "0");
    properties.setProperty("argos.config", "/etc/argos-agent.yml");
    AgentConfig config = AgentConfig.load(properties, Collections.<String, String>emptyMap());
    assertThat(config.getInt(ConfigKeys.LOG_MAX_FILES, 5, 1, 100)).isEqualTo(5);
    assertThat(config.getBoolean(ConfigKeys.ENABLED, true)).isTrue();
    assertThat(config.getInt(ConfigKeys.MAX_STEPS, 1000, 1, 100_000)).isEqualTo(1000);
    assertThat(config.warnings())
        .anySatisfy(w -> assertThat(w).contains("unknown configuration key 'no.such.key'"))
        .anySatisfy(w -> assertThat(w).contains("invalid value 'many' for log.max-files"))
        .anySatisfy(w -> assertThat(w).contains("invalid value 'maybe' for enabled"))
        .anySatisfy(w -> assertThat(w).contains("invalid value '0' for sampling.profile.max-steps"))
        .anySatisfy(w -> assertThat(w).contains("argos.config is not supported yet"));
  }

  @Test
  @org.junit.jupiter.api.Tag("req:DSN-18-15")
  @DisplayName(
      "T-BLD-010 release builds ignore argos.test.* and argos.testing.* (system properties and"
          + " ARGOS_TEST_*); development builds read argos.test.*")
  void releaseBuildIgnoresTestNamespace() {
    Properties properties = new Properties();
    properties.setProperty("argos.test.faults", "advice-throw:servlet");
    properties.setProperty("argos.test.exporter", "file:/tmp/x");
    properties.setProperty("argos.testing.limits", "1");
    properties.setProperty("argos.app.name", "shop");
    properties.setProperty("argos.debug.transform-log", "/tmp/transform.log");
    Map<String, String> environment = new HashMap<String, String>();
    environment.put("ARGOS_TEST_FAULTS", "advice-throw:jdbc");

    AgentConfig release = AgentConfig.load(properties, environment, true);
    assertThat(release.isSet(ConfigKeys.TEST_FAULTS)).isFalse();
    assertThat(release.isSet(ConfigKeys.TEST_EXPORTER)).isFalse();
    assertThat(release.getList(ConfigKeys.TEST_FAULTS)).isEmpty();
    assertThat(release.get(ConfigKeys.TEST_EXPORTER, null)).isNull();
    // Other settings are unaffected (debug.* is not a test namespace).
    assertThat(release.get(ConfigKeys.APP_NAME, null)).isEqualTo("shop");
    assertThat(release.get(ConfigKeys.DEBUG_TRANSFORM_LOG, null)).isEqualTo("/tmp/transform.log");
    assertThat(release.warnings())
        .singleElement()
        .asString()
        .isEqualTo(
            "test-only settings ignored in a release build (T-BLD-010): [ARGOS_TEST_FAULTS,"
                + " argos.test.exporter, argos.test.faults, argos.testing.limits]");

    AgentConfig development = AgentConfig.load(properties, environment, false);
    // ARGOS_TEST_FAULTS wins over -Dargos.test.faults (environment is the later source).
    assertThat(development.getList(ConfigKeys.TEST_FAULTS)).containsExactly("advice-throw:jdbc");
    assertThat(development.get(ConfigKeys.TEST_EXPORTER, null)).isEqualTo("file:/tmp/x");
    // argos.testing.* is the server's namespace: unknown to the agent in every build.
    assertThat(development.warnings())
        .singleElement()
        .asString()
        .contains("unknown configuration key 'testing.limits'");
    assertThat(AgentConfig.load(properties, environment).getList(ConfigKeys.TEST_FAULTS))
        .containsExactly("advice-throw:jdbc");
    assertThat(ConfigKeys.isTestOnly("test.faults")).isTrue();
    assertThat(ConfigKeys.isTestOnly("testing.x")).isTrue();
    assertThat(ConfigKeys.isTestOnly("tests")).isFalse();
  }

  @Test
  @DisplayName("booleans are case-insensitive; empty values count as unset")
  void conversions() {
    Map<String, String> values = new HashMap<String, String>();
    values.put("enabled", "FALSE");
    values.put("log.dir", "");
    AgentConfig config = AgentConfig.of(values);
    assertThat(config.getBoolean(ConfigKeys.ENABLED, true)).isFalse();
    assertThat(config.get(ConfigKeys.LOG_DIR, "fallback")).isEqualTo("fallback");
    assertThat(config.getList(ConfigKeys.LOG_DIR)).isEmpty();
    assertThat(config.isSet(ConfigKeys.ENABLED)).isTrue();
    assertThat(config.getLong("buffer.max-bytes", 7, 1, 10)).isEqualTo(7);
  }
}
