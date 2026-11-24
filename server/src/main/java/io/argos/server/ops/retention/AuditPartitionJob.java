/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.retention;

import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.postgres.ops.AuditPartitionRepository;
import io.argos.server.storage.postgres.ops.AuditPartitionRepository.PartitionAction;
import io.argos.server.storage.postgres.ops.AuditPartitionRepository.PartitionException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronTrigger;

/**
 * The monthly partitions of {@code audit_logs} and {@code alert_events} (17 §9.3: daily at 04:30
 * server time; 13 §12; SEC-82; ADR-0010 C-116). Each run, for both tables: the current and the next
 * two months exist (V001 has no default partition, so a missing month would refuse inserts), and
 * whole months older than the retention are dropped:
 *
 * <ul>
 *   <li>{@code audit_logs}: {@code argos.retention.audit} (365 days, at least 90: configuration
 *       check and the database function, C-116), with the audit retention account {@code
 *       argos.postgres.audit.*} ({@code argos_audit}); without its password the audit side is off
 *       (one INFO line at the start, nothing is ever dropped and the next months are not created:
 *       the installation must configure it, as the Compose installation does);
 *   <li>{@code alert_events}: {@code argos.retention.alert-events} (365 days), runtime account.
 * </ul>
 *
 * The job also runs {@link #FIRST_RUN_DELAY} after the start, so a server that was down at 04:30
 * still creates the next months. Failures are logged at WARN; the next run retries.
 */
public final class AuditPartitionJob implements InitializingBean, DisposableBean {

  /** Spring cron of the daily run (server time zone). */
  public static final String CRON = "0 30 4 * * *";

  /** Months created ahead of the current one. */
  public static final int MONTHS_AHEAD = 2;

  /** Delay of the run after the start. */
  static final Duration FIRST_RUN_DELAY = Duration.ofMinutes(2);

  private static final Logger LOG = LoggerFactory.getLogger(AuditPartitionJob.class);

  private final ArgosProperties properties;
  private final AuditPartitionRepository partitions;
  private final Clock clock;
  private ThreadPoolTaskScheduler scheduler;

  public AuditPartitionJob(
      ArgosProperties properties, AuditPartitionRepository partitions, Clock clock) {
    this.properties = properties;
    this.partitions = partitions;
    this.clock = clock;
  }

  /**
   * What one run did.
   *
   * @param audit the actions on {@code audit_logs}, or null when the audit side is off or failed
   * @param alertEvents the actions on {@code alert_events}, or null when it failed
   * @param auditError the failure of the audit side (one line), or null
   */
  public record Result(
      List<PartitionAction> audit, List<PartitionAction> alertEvents, String auditError) {}

  /** The drop bound of a retention: {@code now - retention}, never inside the current month. */
  static Instant dropBefore(Instant now, Duration retention) {
    Instant bound = now.minus(retention);
    Instant monthStart =
        LocalDate.ofInstant(now, ZoneOffset.UTC)
            .withDayOfMonth(1)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant();
    return bound.isAfter(monthStart) ? monthStart : bound;
  }

  /** One run at the clock's now. */
  public Result run() {
    Instant now = clock.instant();
    List<PartitionAction> alert = null;
    try {
      alert =
          partitions.maintainAlertEvents(
              MONTHS_AHEAD, dropBefore(now, properties.retention().alertEvents()));
      log("alert_events", alert);
    } catch (PartitionException e) {
      LOG.warn("alert_events partition maintenance failed: {}", e.getMessage());
    }
    List<PartitionAction> audit = null;
    String auditError = null;
    if (partitions.auditConfigured()) {
      try {
        audit =
            partitions.maintainAudit(MONTHS_AHEAD, dropBefore(now, properties.retention().audit()));
        log("audit_logs", audit);
      } catch (PartitionException e) {
        auditError = e.getMessage();
        LOG.warn("audit_logs partition maintenance failed: {}", e.getMessage());
      }
    }
    return new Result(audit, alert, auditError);
  }

  private static void log(String table, List<PartitionAction> actions) {
    long created = actions.stream().filter(a -> "created".equals(a.action())).count();
    long dropped = actions.stream().filter(a -> "dropped".equals(a.action())).count();
    if (created > 0 || dropped > 0) {
      LOG.info("{} partitions: {} created, {} dropped", table, created, dropped);
    }
  }

  @Override
  public void afterPropertiesSet() {
    if (!partitions.auditConfigured()) {
      LOG.info(
          "The audit retention job is off: no password for argos.postgres.audit (user {});"
              + " audit_logs months are neither created ahead nor dropped",
          partitions.auditUser());
    }
    ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
    s.setPoolSize(1);
    s.setThreadNamePrefix("argos-partitions-");
    s.setDaemon(true);
    s.setWaitForTasksToCompleteOnShutdown(false);
    s.initialize();
    var unusedFirst =
        s.getScheduledExecutor()
            .schedule(this::scheduled, FIRST_RUN_DELAY.toSeconds(), TimeUnit.SECONDS);
    var unusedDaily = s.schedule(this::scheduled, new CronTrigger(CRON, ZoneId.systemDefault()));
    scheduler = s;
  }

  private void scheduled() {
    try {
      run();
    } catch (RuntimeException e) {
      LOG.warn("Partition maintenance failed: {}", e.getClass().getSimpleName());
    }
  }

  @Override
  public void destroy() {
    if (scheduler != null) {
      scheduler.shutdown();
    }
  }
}
