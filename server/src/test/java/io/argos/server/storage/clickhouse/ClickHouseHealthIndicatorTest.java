/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.storage.clickhouse;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.config.ArgosProperties;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * The {@code clickhouse} readiness contributor without a database (17 §3.5): nothing happens when
 * it is created, and a check that cannot even build its client is DOWN without details. The real
 * checks (UP, DOWN while ClickHouse is paused, UP again) are {@code ClickHouseReadinessIT}.
 */
@Tag("req:F-S20")
class ClickHouseHealthIndicatorTest {

  private static ArgosProperties properties(Map<String, String> settings) {
    return new Binder(new MapConfigurationPropertySource(settings))
        .bindOrCreate("argos", ArgosProperties.class);
  }

  @Test
  @DisplayName("the contributor is named clickhouse and stays idle until the first check")
  void idleUntilChecked() {
    assertThat(ClickHouseHealthIndicator.BEAN_NAME).isEqualTo("clickhouseHealthIndicator");
    // A password file that does not exist: creating the bean must not read it, a check does.
    ClickHouseHealthIndicator indicator =
        new ClickHouseHealthIndicator(
            properties(
                Map.of(
                    "argos.clickhouse.url", "http://127.0.0.1:1",
                    "argos.clickhouse.reader.password-file", "does-not-exist/reader.password")));
    try {
      assertThat(indicator.check())
          .startsWith(
              "IllegalStateException: argos.clickhouse.reader.password-file names a file that"
                  + " cannot be read");
    } finally {
      indicator.destroy();
    }
    assertThat(indicator.check()).isEqualTo("IllegalStateException: The server is shutting down");
  }

  @Test
  @DisplayName("a check without a usable password is DOWN, without details, and does not throw")
  void downWithoutDetails() {
    ClickHouseHealthIndicator indicator =
        new ClickHouseHealthIndicator(properties(Map.of("argos.clickhouse.url", "http://h:1")));
    try {
      Health health = indicator.health();
      assertThat(health.getStatus()).isEqualTo(Status.DOWN);
      assertThat(health.getDetails()).isEmpty();
      assertThat(indicator.check()).startsWith("IllegalStateException: No password for");
    } finally {
      indicator.destroy();
    }
  }
}
