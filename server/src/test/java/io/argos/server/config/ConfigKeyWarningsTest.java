/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config;

import static io.argos.server.config.ConfigTestSupport.bind;
import static io.argos.server.config.ConfigTestSupport.environment;
import static io.argos.server.config.ConfigTestSupport.file;
import static io.argos.server.config.ConfigTestSupport.newSecret;
import static io.argos.server.config.ConfigTestSupport.validSettings;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/** Warnings of the {@code config-warnings} step: unknown keys (O-R03), plain secrets (17 §6.1). */
@Tag("req:O-R03")
class ConfigKeyWarningsTest {

  @BeforeAll
  static void warmUp() {
    ConfigTestSupport.warmUp();
  }

  @TempDir Path dir;

  private static ConfigKeyWarnings warnings(ConfigurableEnvironment e) {
    ArgosProperties p = bind(e);
    return new ConfigKeyWarnings(e, p, new EffectiveServerConfig(e, p));
  }

  @Test
  @DisplayName("unknown argos.* keys of files and system properties are listed with their source")
  void unknownKeys() {
    Map<String, Object> settings = validSettings(dir);
    settings.put("argos.colector.port", "9100");
    settings.put("argos.retention.xlog-days", "30");
    settings.put("argos.collector.quota.tx-per-second", "100");
    settings.put("argos.agent.auto-register.prod", "false");
    settings.put("argos.metrics.allow-cidrs[0]", "127.0.0.1/32");
    ConfigurableEnvironment e =
        environment(
            true,
            Map.of(),
            file(settings),
            new MapPropertySource(
                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                Map.of("argos.web.publicurl2", "x")));

    assertThat(warnings(e).unknownKeys())
        .hasSize(3)
        .anyMatch(s -> s.startsWith("argos.colector.port ("))
        .anyMatch(s -> s.startsWith("argos.retention.xlog-days ("))
        .anyMatch(s -> s.startsWith("argos.web.publicurl2 (") && s.contains("systemProperties"));
  }

  @Test
  @DisplayName("ARGOS_* variables: relaxed names, short names and installation variables are known")
  void environmentVariables() {
    Map<String, Object> env = new LinkedHashMap<>();
    env.put("ARGOS_COLLECTOR_QUOTA_TX_PER_SECOND", "100");
    env.put("ARGOS_COLLECTOR_QUOTA_TXPERSECOND", "100");
    env.put("ARGOS_METRICS_TOKEN", newSecret());
    env.put("ARGOS_MASTER_KEY_FILE", "/run/secrets/master_key");
    env.put("ARGOS_METRICS_ALLOW_CIDRS_0", "127.0.0.1/32");
    env.put("ARGOS_AGENT_AUTO_REGISTER_PROD", "false");
    env.put("ARGOS_RETENTION_ROLLUP_1M", "90d");
    env.put("ARGOS_PG_URL", "jdbc:postgresql://db/argos");
    env.put("ARGOS_SERVER_MEM", "5g");
    env.put("ARGOS_DEMO_MODE", "false");
    env.put("ARGOS_RETENTION_XLOG", "30d");
    env.put("ARGOS_PG_PASWORD_FILE", "/run/secrets/typo");
    env.put("ARGOS_UNKNOWN_THING", "1");
    env.put("JAVA_HOME", "/opt/java");
    ConfigurableEnvironment e = environment(true, env, file(validSettings(dir)));

    assertThat(warnings(e).unknownKeys())
        .containsExactly(
            "ARGOS_PG_PASWORD_FILE (environment)", "ARGOS_UNKNOWN_THING (environment)");
  }

  @Test
  @DisplayName("relaxed environment names of a key")
  void environmentNames() {
    assertThat(ConfigKeyWarnings.environmentNames("argos.collector.quota.tx-per-second"))
        .containsExactly(
            "ARGOS_COLLECTOR_QUOTA_TXPERSECOND", "ARGOS_COLLECTOR_QUOTA_TX_PER_SECOND");
    assertThat(ConfigKeyWarnings.environmentNames("argos.wal.dir"))
        .containsExactly("ARGOS_WAL_DIR");
  }

  @Test
  @DisplayName("a plain secret in a file is warned once per key; from the environment it is not")
  void plaintextSecrets() {
    String secret = newSecret();
    Map<String, Object> settings = validSettings(dir);
    settings.put("argos.postgres.password", secret);
    settings.put("argos.alert.smtp.password", secret);
    ConfigurableEnvironment e =
        environment(
            true,
            Map.of("ARGOS_METRICS_TOKEN", secret, "ARGOS_MASTER_KEY", secret),
            file(settings));

    assertThat(warnings(e).plaintextSecrets())
        .containsExactly(
            "argos.alert.smtp.password is set as a plain value (file [conf/application.yml]); put"
                + " the secret into a file and set argos.alert.smtp.password-file instead (17"
                + " §6.1)",
            "argos.postgres.password is set as a plain value (file [conf/application.yml]); put the"
                + " secret into a file and set argos.postgres.password-file instead (17 §6.1)")
        .noneMatch(w -> w.contains(secret));
  }
}
