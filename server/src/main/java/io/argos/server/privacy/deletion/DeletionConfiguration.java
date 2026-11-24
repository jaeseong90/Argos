/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.privacy.deletion;

import io.argos.server.audit.AuditService;
import io.argos.server.config.ArgosProperties;
import io.argos.server.privacy.salt.UserIdHasher;
import io.argos.server.storage.postgres.privacyjob.PrivacyJobRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The privacy deletion job runner ({@link DeletionJobService}, 13 §9). */
@Configuration(proxyBeanMethods = false)
public class DeletionConfiguration {

  /**
   * The runner. A context without a {@link UserIdHasher} (a test slice) deletes plain user ids
   * only; without a clock or registry bean it gets the UTC clock and a private registry.
   */
  @Bean
  public DeletionJobService deletionJobService(
      ArgosProperties properties,
      PrivacyJobRepository jobs,
      ObjectProvider<UserIdHasher> hasher,
      AuditService audit,
      ObjectProvider<Clock> clock,
      ObjectProvider<MeterRegistry> meters) {
    return new DeletionJobService(
        properties,
        jobs,
        hasher.getIfAvailable(() -> (app, id) -> Optional.empty()),
        audit,
        clock.getIfAvailable(Clock::systemUTC),
        meters.getIfAvailable(SimpleMeterRegistry::new));
  }
}
