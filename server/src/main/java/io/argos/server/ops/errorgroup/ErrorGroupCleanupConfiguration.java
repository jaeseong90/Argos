/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.errorgroup;

import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.postgres.ops.ErrorGroupCleanupRepository;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The nightly error group clean-up ({@link ErrorGroupCleanupJob}, 13 §12.4, ADR-0010 C-255 (5)).
 */
@Configuration(proxyBeanMethods = false)
public class ErrorGroupCleanupConfiguration {

  /**
   * The job, scheduled at {@code argos.ops.error-group-cleanup.cron} unless {@code
   * argos.ops.error-group-cleanup.enabled=false}. A context without a clock bean gets the UTC
   * system clock.
   */
  @Bean
  public ErrorGroupCleanupJob errorGroupCleanupJob(
      ArgosProperties properties,
      ErrorGroupCleanupRepository repository,
      ObjectProvider<Clock> clock) {
    return new ErrorGroupCleanupJob(
        properties, repository::deleteUnseenSince, clock.getIfAvailable(Clock::systemUTC));
  }
}
