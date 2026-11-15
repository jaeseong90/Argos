/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.management;

import io.argos.server.bootstrap.StartupSequence;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health contributor {@code migrations}, member of the readiness group ({@code application.yml}; 17
 * §3.5): UP once the start sequence of DSN-12-15 has completed (PostgreSQL and ClickHouse
 * migrations, WAL recovery, listeners), DOWN before. Together with Spring Boot's {@code
 * readinessState} this makes readiness depend on the migrations explicitly, not only on the
 * listener start order (the listeners open after the sequence, so a probe before the end of the
 * migrations finds no listener at all).
 */
@Component
public class MigrationsHealthIndicator implements HealthIndicator {

  private final StartupSequence sequence;

  public MigrationsHealthIndicator(StartupSequence sequence) {
    this.sequence = sequence;
  }

  @Override
  public Health health() {
    return sequence.isComplete() ? Health.up().build() : Health.down().build();
  }
}
