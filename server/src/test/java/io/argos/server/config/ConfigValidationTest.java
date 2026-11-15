/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.config;

import static io.argos.server.config.ConfigTestSupport.bind;
import static io.argos.server.config.ConfigTestSupport.environment;
import static io.argos.server.config.ConfigTestSupport.file;
import static io.argos.server.config.ConfigTestSupport.validSettings;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.bootstrap.StartupPhaseException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.ConfigurableEnvironment;

/** The {@code config-validation} start step: constraints, cross-key rules, clear messages. */
@Tag("req:O-R03")
class ConfigValidationTest {

  @TempDir Path dir;

  /** Builds the validator once, outside the timed tests (Hibernate Validator start-up). */
  @BeforeAll
  static void warmUp() {
    ConfigTestSupport.warmUp();
  }

  private List<String> violations(Map<String, Object> settings) {
    ConfigurableEnvironment e = environment(true, Map.of(), file(settings));
    ArgosProperties p = bind(e);
    return new ConfigValidation(
            p, ConfigValidation.defaultValidator(), new EffectiveServerConfig(e, p))
        .violations();
  }

  @Test
  @DisplayName("a minimal installation configuration is valid")
  void valid() {
    assertThat(violations(validSettings(dir))).isEmpty();
  }

  @Test
  @DisplayName("argos.web.public-url and argos.postgres.url are required")
  void required() {
    Map<String, Object> settings = validSettings(dir);
    settings.remove("argos.web.public-url");
    settings.remove("argos.postgres.url");
    assertThat(violations(settings))
        .containsExactly(
            "argos.postgres.url: is required: the JDBC URL of the Argos PostgreSQL database"
                + " (default)",
            "argos.web.public-url: is required: the external URL of the web UI, e.g."
                + " https://argos.example.com (default)");
  }

  @Test
  @DisplayName("violations name the key, the reason and the source, not the value")
  void messages() {
    Map<String, Object> settings = validSettings(dir);
    settings.put("argos.web.public-url", "ftp://user:pw@host/");
    settings.put("argos.collector.port", "0");
    settings.put("argos.collector.limits.max-steps-per-batch", "-1");
    settings.put("argos.wal.warn-ratio", "1.5");
    settings.put("argos.postgres.url", "jdbc:mysql://db/argos");
    List<String> v = violations(settings);
    String source = "(file [conf/application.yml])";
    assertThat(v)
        .contains(
            "argos.collector.port: must be greater than or equal to 1 " + source,
            "argos.collector.limits.max-steps-per-batch: must be greater than 0 " + source,
            "argos.wal.warn-ratio: must be less than or equal to 1.0 " + source,
            "argos.postgres.url: must be a PostgreSQL JDBC URL (jdbc:postgresql://host:5432/argos) "
                + source,
            "argos.web.public-url: must be an absolute http or https URL, e.g."
                + " https://argos.example.com "
                + source);
    assertThat(String.join("\n", v)).doesNotContain("user:pw").doesNotContain("mysql");
  }

  @Test
  @Tag("req:D-13")
  @DisplayName(
      "T-SV-010v argos.collector.max-message-bytes is at most 63 MiB (one WAL record per batch,"
          + " readable by recovery); 63 MiB is accepted")
  void maxMessageBytesUpperBound() {
    Map<String, Object> settings = validSettings(dir);
    settings.put(
        "argos.collector.max-message-bytes",
        Integer.toString(ArgosProperties.Collector.MAX_MESSAGE_BYTES_UPPER + 1));
    assertThat(violations(settings))
        .containsExactly(
            "argos.collector.max-message-bytes: must be less than or equal to 66060288"
                + " (file [conf/application.yml])");
    settings.put(
        "argos.collector.max-message-bytes",
        Integer.toString(ArgosProperties.Collector.MAX_MESSAGE_BYTES_UPPER));
    assertThat(violations(settings)).isEmpty();
  }

  @Test
  @DisplayName("cross-key rules: durations, retention days, thresholds, cron, CIDRs, SMTP")
  void crossKeyRules() {
    Map<String, Object> settings = validSettings(dir);
    settings.put("argos.collector.stale-after", "300s");
    settings.put("argos.retention.xlog", "36h");
    settings.put("argos.alert.group-window", "0s");
    settings.put("argos.wal.segment-bytes", "20GB");
    settings.put("argos.writer.flush-rows", "300000");
    settings.put("argos.auth.password.min-length", "200");
    settings.put("argos.auth.refresh-idle-ttl", "48h");
    settings.put("argos.ops.disk.warn-ratio", "0.95");
    settings.put("argos.ops.reconcile.cron", "0 3 * * *");
    settings.put("argos.metrics.allow-cidrs[0]", "127.0.0.1/32");
    settings.put("argos.metrics.allow-cidrs[1]", "metrics.example.test");
    settings.put("argos.alert.webhook.allow-cidrs[0]", "10.0.0.0/33");
    settings.put("argos.alert.smtp.starttls", "optional");
    settings.put("argos.clickhouse.url", "clickhouse:8123");
    List<String> v = violations(settings);
    assertThat(v)
        .anyMatch(s -> s.startsWith("argos.collector.down-after: must be longer than stale-after"))
        .anyMatch(s -> s.startsWith("argos.retention.xlog: must be a whole number of days"))
        .anyMatch(s -> s.startsWith("argos.alert.group-window: must be a positive duration"))
        .anyMatch(s -> s.startsWith("argos.wal.segment-bytes: must not exceed max-bytes"))
        .anyMatch(s -> s.startsWith("argos.writer.flush-rows: must not exceed queue-rows"))
        .anyMatch(s -> s.startsWith("argos.auth.password.min-length: must not exceed max-length"))
        .anyMatch(s -> s.startsWith("argos.auth.refresh-idle-ttl: must not exceed refresh-max-ttl"))
        .anyMatch(s -> s.startsWith("argos.ops.disk.emergency-ratio: warn-ratio < emergency-ratio"))
        .anyMatch(s -> s.startsWith("argos.ops.reconcile.cron: must be a cron expression"))
        .anyMatch(s -> s.startsWith("argos.metrics.allow-cidrs[1]: is not an IP address range"))
        .anyMatch(s -> s.startsWith("argos.alert.webhook.allow-cidrs[0]: is not an IP address"))
        .anyMatch(s -> s.startsWith("argos.alert.smtp.starttls: optional needs"))
        .anyMatch(s -> s.startsWith("argos.clickhouse.url: must be an http or https URL"))
        .noneMatch(s -> s.startsWith("argos.metrics.allow-cidrs[0]"));
  }

  @Test
  @DisplayName("the start step refuses with one line listing every violation")
  void startStep() {
    Map<String, Object> settings = validSettings(dir);
    settings.remove("argos.web.public-url");
    settings.put("argos.collector.port", "70000");
    ConfigurableEnvironment e = environment(true, Map.of(), file(settings));
    ArgosProperties p = bind(e);
    ConfigValidation step =
        new ConfigValidation(
            p, ConfigValidation.defaultValidator(), new EffectiveServerConfig(e, p));
    assertThatThrownBy(step::run)
        .isInstanceOf(StartupPhaseException.class)
        .hasMessageStartingWith("Invalid configuration: argos.collector.port: must be less than")
        .hasMessageContaining("; argos.web.public-url: is required")
        .satisfies(t -> assertThat(t.getMessage()).doesNotContain("\n"));
  }
}
