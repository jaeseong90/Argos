/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.storage.schema.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.storage.clickhouse.ArgosClickHouse;
import io.argos.storage.clickhouse.ArgosClickHouse.StartupOutcome;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The substitution variables of deploy/clickhouse have no default: ClickHouse refuses to start when
 * a password hash (SEC-74) or the memory budget (N-18: no RAM-blind default) is missing. For the
 * passwords only an unset or malformed hash stops ClickHouse; weak or empty passwords must be
 * rejected before hashing (installer docs/17 step 6, server credential policy docs/16 §10.6):
 * ClickHouse accepts the SHA-256 of an empty password.
 */
@Tag("integration")
class ConfigFailFastIT {

  private static final Duration WATCH = Duration.ofSeconds(60);

  @Test
  @Tag("req:SEC-74")
  @DisplayName("T-SEC-74 an unset password hash variable stops ClickHouse at startup")
  void missingPasswordHash() {
    StartupOutcome outcome =
        ArgosClickHouse.builder()
            .environment("ARGOS_CH_READER_PASSWORD_SHA256", "")
            .startExpectingExit(WATCH);

    assertThat(outcome.exited()).isTrue();
    assertThat(outcome.exitCode()).isNotZero();
    assertThat(outcome.errorLog())
        .contains("Env variable is not set: ARGOS_CH_READER_PASSWORD_SHA256")
        .contains("CANNOT_LOAD_CONFIG");
    Evidence.record("config", "unset reader password hash: exit code", outcome.exitCode());
  }

  @Test
  @Tag("req:SEC-74")
  @DisplayName("T-SEC-74 a password hash that is not SHA-256 hex stops ClickHouse at startup")
  void invalidPasswordHash() {
    StartupOutcome outcome =
        ArgosClickHouse.builder()
            .environment("ARGOS_CH_WRITER_PASSWORD_SHA256", "changeme")
            .startExpectingExit(WATCH);

    assertThat(outcome.exited()).isTrue();
    assertThat(outcome.exitCode()).isNotZero();
    assertThat(outcome.errorLog()).contains("argos_writer");
  }

  @Test
  @Tag("req:N-18")
  @DisplayName("N-18 an unset server memory variable stops ClickHouse at startup")
  void missingMemoryBudget() {
    StartupOutcome outcome =
        ArgosClickHouse.builder()
            .environment("ARGOS_CH_MAX_SERVER_MEMORY_USAGE", "")
            .startExpectingExit(WATCH);

    assertThat(outcome.exited()).isTrue();
    assertThat(outcome.exitCode()).isNotZero();
    assertThat(outcome.logs() + outcome.errorLog())
        .contains("Env variable is not set: ARGOS_CH_MAX_SERVER_MEMORY_USAGE");
  }
}
