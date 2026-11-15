/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.bootstrap.PhaseOrder;
import io.argos.server.config.ArgosProperties;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/** The Flyway settings of 13 §13.1 and the one-line refusal text. */
@Tag("req:N-22")
class PostgresMigrationPhaseTest {

  @Test
  @DisplayName(
      "T-ST-052 Flyway: owner data source, db/pg, schema argos, validate on migrate, no baseline,"
          + " no clean, no ignored validation patterns")
  void flywaySettings() {
    Configuration c =
        PostgresMigrationPhase.flyway(new SimpleDriverDataSource()).getConfiguration();

    assertThat(c.getLocations()).extracting(Object::toString).containsExactly("classpath:db/pg");
    assertThat(c.getSchemas()).containsExactly("argos");
    assertThat(c.getDefaultSchema()).isEqualTo("argos");
    assertThat(c.isValidateOnMigrate()).isTrue();
    assertThat(c.isValidateMigrationNaming()).isTrue();
    assertThat(c.isBaselineOnMigrate()).isFalse();
    assertThat(c.isCleanDisabled()).isTrue();
    assertThat(c.isOutOfOrder()).isFalse();
    assertThat(c.getIgnoreMigrationPatterns()).isEmpty();
    assertThat(c.isPlaceholderReplacement()).isFalse();
    assertThat(c.getTable()).isEqualTo("flyway_schema_history");
  }

  @Test
  @DisplayName("the phase is POSTGRES_MIGRATION, before the runtime pool step")
  void phase() {
    ArgosProperties properties =
        new Binder(new MapConfigurationPropertySource(java.util.Map.of()))
            .bindOrCreate("argos", ArgosProperties.class);
    PostgresMigrationPhase phase = new PostgresMigrationPhase(properties);

    assertThat(phase.order()).isEqualTo(PhaseOrder.POSTGRES_MIGRATION);
    assertThat(phase.name()).isEqualTo("postgres-flyway");
    assertThat(phase.getOrder()).isLessThan(100);
  }

  @Test
  @DisplayName("Flyway messages become one line without advice and web links")
  void summary() {
    FlywayException e =
        new FlywayException(
            "Validate failed: Migrations have failed validation\n"
                + "Migration checksum mismatch for migration version 001\n"
                + "-> Applied to database : 1234\n"
                + "-> Resolved locally    : 5678\n"
                + "Either revert the changes to the migration, or run repair to update the schema"
                + " history.\n"
                + "Need more flexibility with validation rules? Learn more: https://example.invalid/x");

    assertThat(PostgresMigrationPhase.summary(e))
        .isEqualTo(
            "Validate failed: Migrations have failed validation Migration checksum mismatch for"
                + " migration version 001 -> Applied to database : 1234 -> Resolved locally : 5678");
  }
}
