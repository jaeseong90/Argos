/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.rollup;

import io.argos.server.config.ArgosProperties;
import io.argos.server.ops.migration.MigrationSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The nightly consistency check ({@link ReconcileJob}, 12 §9.1). */
@Configuration(proxyBeanMethods = false)
public class ReconcileConfiguration {

  /**
   * The job, scheduled at {@code argos.ops.reconcile.cron}. A context without the migration source,
   * clock or {@link MeterRegistry} beans (a test slice) gets the bundled files, the UTC system
   * clock and a private registry.
   */
  @Bean
  public ReconcileJob reconcileJob(
      ArgosProperties properties,
      ObjectProvider<MigrationSource> source,
      ObjectProvider<Clock> clock,
      ObjectProvider<MeterRegistry> meters) {
    return new ReconcileJob(
        properties,
        source.getIfAvailable(
            () -> MigrationSource.fromClasspath(ReconcileConfiguration.class.getClassLoader())),
        clock.getIfAvailable(Clock::systemUTC),
        meters.getIfAvailable(SimpleMeterRegistry::new));
  }
}
