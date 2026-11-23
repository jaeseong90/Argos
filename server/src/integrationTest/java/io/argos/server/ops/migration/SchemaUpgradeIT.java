/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.migration;

import io.argos.server.testing.ServerTestEnvironment;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-ST-052 path 2: "V001 + data" then the next version (V004 and the four-step M001 and M002
 * fixtures: a daily and a monthly backfill), with the 1/1000 generator profile ({@code tiny}). The
 * 1/100 run with the 10-minute limit per script is {@link SchemaUpgradeNightlyIT} (T-SV-002).
 */
@Tag("req:N-22")
@Tag("req:D-03")
@Tag("req:D-12")
class SchemaUpgradeIT {

  private static ServerTestEnvironment env;
  private static final MigrationTimings TIMINGS =
      new MigrationTimings(
          "path-2-tiny", "T-ST-052 path 2: V001 + generator data (tiny, 1/1000) -> next version");

  @BeforeAll
  static void start() {
    env = ServerTestEnvironment.start();
  }

  @AfterAll
  static void stop() {
    if (env != null) {
      env.close();
    }
    TIMINGS.write();
  }

  @Test
  @DisplayName(
      "T-ST-052 V001 + tiny data -> V004 (ADD COLUMN), M001 (4-step 1m replacement, daily"
          + " backfill) and M002 (4-step 1h replacement, monthly backfill): counts kept, rollups"
          + " equal per minute and per hour, D-12 before and after, rollup_alias switched, second"
          + " start no-op")
  void upgradeWithData() throws Exception {
    SchemaUpgradeScenario.run(env, "tiny", TIMINGS);
  }
}
