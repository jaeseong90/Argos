/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.retention;

import io.argos.server.audit.Actor;
import io.argos.server.bootstrap.PhaseOrder;
import io.argos.server.bootstrap.StartupPhase;
import org.springframework.core.Ordered;

/**
 * Start step {@code clickhouse-retention} ({@code CLICKHOUSE_MIGRATION}, after {@code
 * clickhouse-migration}): applies the effective retention to the existing tables ({@link
 * RetentionService#applyEffective}, ADR-0010 C-99: the migration runner only renders the retention
 * when it creates a table). A ClickHouse failure here does not refuse the start: the schema is
 * already migrated and the old TTLs stay valid; the step logs one WARN and the next start or {@code
 * argos-admin retention apply} retries.
 */
public class RetentionApplyPhase implements StartupPhase, Ordered {

  /** After the migration step of the same phase (which has the default lowest precedence). */
  static final int ORDER = Ordered.LOWEST_PRECEDENCE;

  private final RetentionService retention;

  public RetentionApplyPhase(RetentionService retention) {
    this.retention = retention;
  }

  @Override
  public PhaseOrder order() {
    return PhaseOrder.CLICKHOUSE_MIGRATION;
  }

  @Override
  public String name() {
    return "clickhouse-retention";
  }

  @Override
  public int getOrder() {
    return ORDER;
  }

  @Override
  public void run() {
    retention.applyEffective(Actor.system());
  }
}
