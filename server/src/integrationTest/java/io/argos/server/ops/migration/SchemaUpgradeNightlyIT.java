/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import io.argos.server.testing.ServerTestEnvironment;
import io.argos.storage.clickhouse.MemoryBudget;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * T-SV-002 (nightly, ADR-0010 C-100): "V001 + 1/100 generator data" ({@code ci} profile), then the
 * next-version fixture scripts; every script and step at most 10 minutes (13 §13.2), timing table
 * in {@code server/build/reports/migration/timings.md}. Runs only with {@code
 * -Pserver.nightly=true}; the nightly job is WO-50.
 */
@Tag("req:N-22")
@Tag("req:D-12")
@Tag("slow")
@EnabledIfSystemProperty(
    named = "argos.server.nightly",
    matches = "true",
    disabledReason = "nightly test (T-SV-002, ADR-0010 C-100): run with -Pserver.nightly=true")
class SchemaUpgradeNightlyIT {

  private static ServerTestEnvironment env;
  private static final MigrationTimings TIMINGS =
      new MigrationTimings(
          "nightly-ci",
          "T-SV-002: V001 + generator data (ci, 1/100) -> next version, each <= 10 min");

  @BeforeAll
  static void start() {
    env =
        ServerTestEnvironment.builder()
            .clickHouseMemoryLimit(4 * MemoryBudget.GIB)
            .startupTimeout(Duration.ofMinutes(5))
            .start();
  }

  @AfterAll
  static void stop() {
    if (env != null) {
      env.close();
    }
    TIMINGS.write();
  }

  @Test
  @Timeout(value = 60, unit = java.util.concurrent.TimeUnit.MINUTES)
  @DisplayName(
      "T-SV-002 V001 + 1/100 data -> next-version scripts: each script and step within 10 minutes")
  void upgradeWithOnePercentData() throws Exception {
    SchemaUpgradeScenario.run(env, "ci", TIMINGS);
  }
}
