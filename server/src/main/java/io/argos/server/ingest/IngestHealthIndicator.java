/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ingest;

import java.util.Objects;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health contributor {@code ingest}, member of the readiness group ({@code application.yml}): DOWN
 * once the ingest dispatcher has stopped on a failure ({@link IngestService#isDispatcherStopped()},
 * 12 §3.9), UP otherwise, also before the dispatcher starts (the {@code migrations} contributor
 * covers the start). While it is DOWN the collector still acknowledges into the WAL, but nothing is
 * loaded into ClickHouse until a restart; the dispatcher's ERROR line and {@code
 * argos_ingest_errors_total{stage="dispatch"}} name the cause. No details in the response.
 */
@Component(IngestHealthIndicator.BEAN_NAME)
public class IngestHealthIndicator implements HealthIndicator {

  /** Bean name; Spring Boot names the contributor {@code ingest} after it. */
  public static final String BEAN_NAME = "ingestHealthIndicator";

  private final IngestService ingest;

  public IngestHealthIndicator(IngestService ingest) {
    this.ingest = Objects.requireNonNull(ingest, "ingest");
  }

  @Override
  public Health health() {
    return ingest.isDispatcherStopped() ? Health.down().build() : Health.up().build();
  }
}
