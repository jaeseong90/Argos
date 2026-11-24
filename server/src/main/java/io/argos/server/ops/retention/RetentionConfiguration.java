/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.retention;

import io.argos.server.audit.AuditService;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.postgres.auth.SettingsRepository;
import io.argos.server.storage.postgres.ops.AuditPartitionRepository;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The retention beans (WO-28): {@link RetentionService} with its start step {@link
 * RetentionApplyPhase}, and the daily audit and alert history partition job {@link
 * AuditPartitionJob}.
 */
@Configuration(proxyBeanMethods = false)
public class RetentionConfiguration {

  /** The run-time retention; a context without a clock bean gets the UTC system clock. */
  @Bean
  public RetentionService retentionService(
      ArgosProperties properties,
      SettingsRepository settings,
      AuditService audit,
      ObjectProvider<Clock> clock) {
    return new RetentionService(
        properties, settings, audit, clock.getIfAvailable(Clock::systemUTC));
  }

  /** The start step that applies the effective retention after the ClickHouse migration. */
  @Bean
  public RetentionApplyPhase retentionApplyPhase(RetentionService retention) {
    return new RetentionApplyPhase(retention);
  }

  /** The daily partition job of {@code audit_logs} and {@code alert_events} (17 §9.3, 04:30). */
  @Bean
  public AuditPartitionJob auditPartitionJob(
      ArgosProperties properties,
      AuditPartitionRepository partitions,
      ObjectProvider<Clock> clock) {
    return new AuditPartitionJob(properties, partitions, clock.getIfAvailable(Clock::systemUTC));
  }
}
