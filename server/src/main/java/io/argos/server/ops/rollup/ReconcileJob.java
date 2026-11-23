/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.rollup;

import io.argos.server.config.ArgosProperties;
import io.argos.server.ops.management.MeterNames;
import io.argos.server.ops.migration.MigrationSource;
import io.argos.server.storage.postgres.ops.AdminDatabase;
import io.argos.server.storage.postgres.ops.AdminDatabase.AdminDatabaseException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;

/**
 * The nightly consistency check (F-S22, D-14, 12 §9.1): at {@code argos.ops.reconcile.cron} (Spring
 * cron, server time zone, default {@code 0 0 3 * * *}; there is no off switch) it checks the
 * previous UTC day with {@link ReconcileService} (rollup lock: one run at a time over every node
 * and the {@code argos-admin} commands; a day already checked by another node is skipped), rebuilds
 * the mismatching rollups and records the result in {@code reconcile_results}.
 *
 * <p>Gauge {@value #GAUGE} ({@code argos_reconcile_mismatch_minutes{app}}): per application, the
 * D-03 minutes (xlog against {@code app_stat_1m}, {@code service_stat_1m}, {@code
 * instance_stat_1m}, summed over the three) that did not match on the first check of the last run,
 * before the rebuild; 0 for every other application of {@code argos.apps} (at most {@link
 * #MAX_GAUGE_APPS}). The value stays until the next run, so an alert sees that a day needed a
 * repair; whether the repair worked is in {@code reconcile_results}.
 *
 * <p>The job runs on its own thread ({@code argos-reconcile}), so a long check does not delay the
 * other scheduled jobs. A failure is logged at WARN and the next night runs again.
 */
public final class ReconcileJob implements InitializingBean, DisposableBean {

  /** Micrometer name of the gauge ({@code argos_reconcile_mismatch_minutes}). */
  public static final String GAUGE = MeterNames.RECONCILE_MISMATCH_MINUTES;

  /** Most applications the gauge lists. */
  public static final int MAX_GAUGE_APPS = 10_000;

  private static final Logger LOG = LoggerFactory.getLogger(ReconcileJob.class);

  private final ArgosProperties properties;
  private final MigrationSource source;
  private final Clock clock;
  private final MultiGauge gauge;
  private final String cron;
  private final AtomicBoolean running = new AtomicBoolean();
  private ThreadPoolTaskScheduler scheduler;

  /**
   * @param properties the server configuration
   * @param source the bundled migration files
   * @param clock UTC clock (the previous day, retention)
   * @param meters the server's registry
   */
  public ReconcileJob(
      ArgosProperties properties, MigrationSource source, Clock clock, MeterRegistry meters) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.source = Objects.requireNonNull(source, "source");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.cron = properties.ops().reconcile().cron();
    this.gauge =
        MultiGauge.builder(GAUGE)
            .description(
                "D-03 minutes of the last nightly consistency check that did not match before the"
                    + " rebuild (12 §9.1)")
            .register(meters);
  }

  /**
   * Schedules the job (a cron that is not a valid expression never reaches here: {@code
   * ConfigValidation} refuses the start).
   */
  @Override
  public void afterPropertiesSet() {
    if (cron == null || !CronExpression.isValidExpression(cron)) {
      LOG.warn(
          "The nightly consistency check is not scheduled: argos.ops.reconcile.cron is invalid");
      return;
    }
    ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
    s.setPoolSize(1);
    s.setThreadNamePrefix("argos-reconcile-");
    s.setDaemon(true);
    s.setWaitForTasksToCompleteOnShutdown(false);
    s.initialize();
    // scheduled() catches and logs every failure; the future carries nothing to check.
    var unused = s.schedule(this::scheduled, new CronTrigger(cron, ZoneId.systemDefault()));
    scheduler = s;
  }

  @Override
  public void destroy() {
    if (scheduler != null) {
      scheduler.shutdown();
    }
  }

  private void scheduled() {
    try {
      run();
    } catch (RuntimeException e) {
      LOG.warn("The nightly consistency check failed; it runs again next night: {}", e.toString());
    }
  }

  /**
   * The cron run: checks the previous UTC day unless a scheduled run (of this or another node)
   * already recorded it.
   *
   * @return the report, empty when the run was skipped or failed (logged)
   */
  public Optional<ReconcileReport> run() {
    return check(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).minusDays(1), true);
  }

  /**
   * Checks {@code day} now as the job does (trigger {@code scheduled}, rebuild on, gauge), also
   * when it was checked before (the "run now" hook of 18 §19 for tests and operations).
   *
   * @return the report, empty when the run was skipped or failed (logged)
   */
  public Optional<ReconcileReport> runDay(LocalDate day) {
    return check(day, false);
  }

  private Optional<ReconcileReport> check(LocalDate day, boolean oncePerDay) {
    if (!running.compareAndSet(false, true)) {
      LOG.info("The consistency check of {} is skipped: a run is in progress", day);
      return Optional.empty();
    }
    try {
      Outcome<ReconcileReport> outcome =
          ReconcileService.run(
              properties, source, day, ReconcileReport.SCHEDULED, true, oncePerDay, clock);
      if (outcome instanceof Outcome.Done<ReconcileReport> done) {
        updateGauge(done.value());
        return Optional.of(done.value());
      }
      Outcome.Refused<ReconcileReport> refused = (Outcome.Refused<ReconcileReport>) outcome;
      switch (refused.reason()) {
        case BUSY, ALREADY_DONE ->
            LOG.info("The consistency check of {} is skipped: {}", day, refused.message());
        default ->
            LOG.warn(
                "The consistency check of {} failed; it runs again next night: {}",
                day,
                refused.message());
      }
      return Optional.empty();
    } finally {
      running.set(false);
    }
  }

  private void updateGauge(ReconcileReport report) {
    Map<Integer, Integer> values = new TreeMap<>();
    try (AdminDatabase db = AdminDatabase.open(properties.postgres())) {
      for (int app : db.appIds(MAX_GAUGE_APPS)) {
        values.put(app, 0);
      }
    } catch (AdminDatabaseException e) {
      LOG.warn("The application list of the consistency gauge is unavailable: {}", e.getMessage());
    }
    for (Map.Entry<Integer, Integer> e : report.d03MinutesByApp().entrySet()) {
      if (values.containsKey(e.getKey()) || values.size() < MAX_GAUGE_APPS) {
        values.put(e.getKey(), e.getValue());
      }
    }
    List<MultiGauge.Row<?>> rows = new ArrayList<>();
    for (Map.Entry<Integer, Integer> e : values.entrySet()) {
      rows.add(MultiGauge.Row.of(Tags.of("app", Integer.toString(e.getKey())), e.getValue()));
    }
    gauge.register(rows, true);
  }
}
