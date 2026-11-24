/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.rollup;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.AuthTestProperties;
import io.argos.server.ops.migration.MigrationSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code argos.ops.reconcile.enabled} (ADR-0010 C-220 (3)): the nightly consistency check is
 * scheduled by default and not at all when the key is false. No database is opened.
 */
@Tag("req:F-S22")
class ReconcileJobScheduleTest {

  private static ReconcileJob job(Map<String, Object> settings) {
    return new ReconcileJob(
        AuthTestProperties.of(settings),
        MigrationSource.fromClasspath(ReconcileJobScheduleTest.class.getClassLoader()),
        Clock.systemUTC(),
        new SimpleMeterRegistry(),
        null);
  }

  @Test
  @DisplayName(
      "T-SV-019a argos.ops.reconcile.enabled: default true schedules the nightly check, false"
          + " leaves it unscheduled")
  void enabledKey() {
    ReconcileJob on = job(Map.of());
    try {
      on.afterPropertiesSet();
      assertThat(on.isScheduled()).isTrue();
    } finally {
      on.destroy();
    }
    ReconcileJob off = job(Map.of("argos.ops.reconcile.enabled", "false"));
    try {
      off.afterPropertiesSet();
      assertThat(off.isScheduled()).isFalse();
    } finally {
      off.destroy();
    }
  }
}
