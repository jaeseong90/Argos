/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.management;

import io.argos.server.config.ServerBuild;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * Gauge {@code argos_build_info{version, commit}} = 1 (17 §11.1): which build a Prometheus series
 * belongs to. Every meter of the server also carries {@code application="argos-server"} ({@code
 * management.metrics.tags.application}); the JVM and process meters come from Spring Boot.
 */
@Component
public class BuildInfoMetrics implements MeterBinder {

  /** Micrometer name of the gauge. */
  public static final String NAME = "argos.build.info";

  private final ServerBuild build;

  public BuildInfoMetrics(ServerBuild build) {
    this.build = build;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder(NAME, () -> 1)
        .description("Version and commit of the Argos server (value always 1)")
        .tag("version", build.version())
        .tag("commit", build.commit())
        .strongReference(true)
        .register(registry);
  }
}
