/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.retention;

import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import io.argos.server.config.ArgosProperties;
import io.argos.server.ops.migration.SchemaParameters;
import io.argos.server.ops.retention.RetentionApplier.Drop;
import io.argos.server.ops.retention.RetentionApplier.TableState;
import io.argos.server.ops.retention.RetentionPlanner.TtlChange;
import io.argos.server.storage.clickhouse.RollupPartitions;
import io.argos.server.storage.clickhouse.RollupPartitions.StatementException;
import io.argos.server.storage.clickhouse.TableCatalog;
import io.argos.server.storage.postgres.auth.SettingsRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The retention of the ClickHouse tables at run time (WO-28; 13 §8, 17 §9.1, 14 §4.14; ADR-0010
 * C-99, C-112; PM question 3 assumption):
 *
 * <ul>
 *   <li>The effective retention is {@code argos.retention.*} of the configuration with the values
 *       stored by {@code PUT /api/v1/admin/settings/retention} ({@code settings.retention}) in
 *       place: an API value wins over the configuration file.
 *   <li>{@link #applyEffective} (the start step after the ClickHouse migration, and {@link
 *       #update}) gives every governed table the effective TTL ({@link RetentionApplier}); when a
 *       retention became shorter, the partitions outside it are queued and dropped by this
 *       service's own thread ({@code argos-retention}), at most {@link #DROPS_PER_MINUTE} per
 *       minute (17 §9.1 "I/O 급증 방지로 분당 N개").
 *   <li>A change of a table's TTL is recorded as {@code RETENTION_CHANGED} (actor system at the
 *       start, the administrator through the API) with the days before and after; nothing is
 *       written to {@code ch_schema_history}.
 * </ul>
 *
 * The query side reads {@link #effective()} for its raw-retention boundaries ({@code
 * api.query.QuerySources}, refreshed at once after {@code PUT /admin/settings/retention}; ADR-0010
 * C-238 (3), WO-39).
 */
public final class RetentionService implements InitializingBean, DisposableBean {

  /** Partitions of a shortened retention dropped per minute at most. */
  public static final int DROPS_PER_MINUTE = 10;

  /** Most partitions waiting to be dropped (365 days x 20 tables). */
  static final int MAX_PENDING = 10_000;

  /** Timeout of one ClickHouse statement of this service. */
  static final Duration STATEMENT_TIMEOUT = Duration.ofMinutes(10);

  /** Audit target of every retention event. */
  static final Target TARGET = new Target("retention", null, "clickhouse");

  private static final Logger LOG = LoggerFactory.getLogger(RetentionService.class);

  private final ArgosProperties properties;
  private final SettingsRepository settings;
  private final AuditService audit;
  private final Clock clock;
  private final Object lock = new Object();
  private final Deque<Drop> pending = new ArrayDeque<>(); // guarded by lock
  private ThreadPoolTaskScheduler scheduler;

  /**
   * @param properties the server configuration
   * @param settings the settings table (API values)
   * @param audit the audit service
   * @param clock UTC clock
   */
  public RetentionService(
      ArgosProperties properties, SettingsRepository settings, AuditService audit, Clock clock) {
    this.properties = properties;
    this.settings = settings;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * The effective retention with the origin of each value.
   *
   * @param values effective days by API field
   * @param sources {@code settings} (set through the API) or {@code config} by API field
   * @param config the values of the configuration by API field
   */
  public record Effective(
      RetentionSettings values, Map<String, String> sources, RetentionSettings config) {}

  /**
   * The outcome of an apply.
   *
   * @param applied the TTL statements ran ({@code false}: ClickHouse failed, see {@code error})
   * @param changes the TTL changes made
   * @param queuedDrops partitions queued for dropping
   * @param error a short operator-facing reason when not applied, else null
   */
  public record ApplyOutcome(
      boolean applied, List<TtlChange> changes, int queuedDrops, String error) {

    public ApplyOutcome {
      changes = List.copyOf(changes);
    }
  }

  /** The values of the configuration ({@code argos.retention.*}). */
  public RetentionSettings configured() {
    return RetentionSettings.of(properties.retention());
  }

  /** The effective retention (configuration with the API values in place). */
  public Effective effective() {
    RetentionSettings config = configured();
    Map<String, Integer> overrides = settings.retentionOverrides();
    Map<String, Integer> known = new LinkedHashMap<>();
    Map<String, String> sources = new LinkedHashMap<>();
    for (String f : RetentionSettings.FIELDS) {
      if (overrides.containsKey(f)) {
        known.put(f, overrides.get(f));
      }
      sources.put(f, overrides.containsKey(f) ? "settings" : "config");
    }
    RetentionSettings values;
    try {
      values = config.with(known);
    } catch (IllegalArgumentException e) {
      values = config;
    }
    return new Effective(values, sources, config);
  }

  /**
   * Applies the effective retention as {@code actor} (start step: {@link Actor#system()}).
   *
   * @return what changed; never throws for a ClickHouse failure (logged at WARN, {@code applied}
   *     false)
   */
  public ApplyOutcome applyEffective(Actor actor) {
    return apply(effective().values(), actor, null);
  }

  /**
   * Stores new values ({@code PUT /admin/settings/retention}) and applies them.
   *
   * @param changes API field to whole days; absent fields keep their effective value
   * @param confirm the administrator confirmed a shortening
   * @param userId the administrator
   * @param actor the audit actor
   * @return the new effective retention and the apply outcome
   * @throws IllegalArgumentException for an unknown field or a value out of range
   * @throws ShorteningNotConfirmedException when a value becomes shorter without {@code confirm}
   */
  public UpdateResult update(
      Map<String, Integer> changes, boolean confirm, UUID userId, Actor actor) {
    Effective before = effective();
    RetentionSettings next = before.values().with(changes);
    List<String> shortened = before.values().shortenedIn(next);
    if (!shortened.isEmpty() && !confirm) {
      throw new ShorteningNotConfirmedException(shortened);
    }
    Map<String, Integer> stored = new LinkedHashMap<>(settings.retentionOverrides());
    stored.putAll(changes);
    settings.saveRetentionOverrides(stored, userId);
    List<String> changed = before.values().changedIn(next);
    Map<String, Object> b = new LinkedHashMap<>();
    Map<String, Object> a = new LinkedHashMap<>();
    for (String f : changed) {
      b.put(f, before.values().get(f));
      a.put(f, next.get(f));
    }
    ApplyOutcome outcome = apply(next, actor, changed.isEmpty() ? null : new Settings(b, a));
    return new UpdateResult(effective(), outcome, shortened);
  }

  /**
   * The result of {@link #update}.
   *
   * @param effective the effective retention after the update
   * @param outcome the apply outcome
   * @param shortened the fields that became shorter
   */
  public record UpdateResult(Effective effective, ApplyOutcome outcome, List<String> shortened) {

    public UpdateResult {
      shortened = List.copyOf(shortened);
    }
  }

  /** A shortening was asked without {@code confirm: true}. */
  public static final class ShorteningNotConfirmedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<String> fields;

    ShorteningNotConfirmedException(List<String> fields) {
      super("Shortening a retention needs confirm: true (" + String.join(", ", fields) + ")");
      this.fields = List.copyOf(fields);
    }

    /** The fields that would become shorter. */
    public List<String> fields() {
      return fields;
    }
  }

  /** The API change recorded with the TTL changes. */
  private record Settings(Map<String, Object> before, Map<String, Object> after) {}

  private ApplyOutcome apply(RetentionSettings desired, Actor actor, Settings api) {
    SchemaParameters params;
    try {
      params = SchemaParameters.from(properties);
    } catch (IllegalArgumentException e) {
      return failed(actor, api, "invalid_configuration");
    }
    RetentionApplier applier = new RetentionApplier(params);
    RetentionApplier.Result result;
    try (RollupPartitions.Session sql =
            RollupPartitions.open(properties.clickhouse(), STATEMENT_TIMEOUT);
        TableCatalog.ReaderSession parts =
            TableCatalog.openReader(properties.clickhouse(), Duration.ofSeconds(60))) {
      result = applier.apply(sql, parts, desired, LocalDate.now(clock.withZone(ZoneOffset.UTC)));
    } catch (StatementException | RuntimeException e) {
      LOG.warn(
          "The retention could not be applied to ClickHouse; the next start or argos-admin"
              + " retention apply retries: {}",
          e.getMessage());
      return failed(actor, api, "clickhouse_unavailable");
    }
    int queued = 0;
    synchronized (lock) {
      for (Drop d : result.drops()) {
        if (pending.size() < MAX_PENDING && !pending.contains(d)) {
          pending.addLast(d);
          queued++;
        }
      }
    }
    if (!result.unmanaged().isEmpty()) {
      LOG.warn(
          "Retention left alone for tables without a TTL in days: {}",
          String.join(", ", result.unmanaged()));
    }
    if (!result.changes().isEmpty() || api != null) {
      Map<String, Object> before = new LinkedHashMap<>();
      Map<String, Object> after = new LinkedHashMap<>();
      if (api != null) {
        before.putAll(api.before());
        after.putAll(api.after());
      }
      for (TtlChange c : result.changes()) {
        before.put("ttl_" + c.table(), c.fromDays());
        after.put("ttl_" + c.table(), c.toDays());
      }
      after.put("queued_drops", queued);
      audit.record(
          AuditEvent.RETENTION_CHANGED, actor, TARGET, AuditResult.success(), before, after);
      LOG.info(
          "Retention applied: {} table TTL(s) changed, {} partition(s) queued for dropping",
          result.changes().size(),
          queued);
    }
    return new ApplyOutcome(true, result.changes(), queued, null);
  }

  private ApplyOutcome failed(Actor actor, Settings api, String reason) {
    if (api != null) {
      audit.record(
          AuditEvent.RETENTION_CHANGED,
          actor,
          TARGET,
          AuditResult.failure(reason),
          api.before(),
          api.after());
    }
    return new ApplyOutcome(false, List.of(), 0, reason);
  }

  /** Partitions waiting to be dropped. */
  public int pendingDrops() {
    synchronized (lock) {
      return pending.size();
    }
  }

  /**
   * Drops up to {@code max} queued partitions now (the scheduled run drops {@link
   * #DROPS_PER_MINUTE}).
   *
   * @return partitions dropped
   */
  public int drainDrops(int max) {
    List<Drop> batch = new ArrayList<>();
    synchronized (lock) {
      while (batch.size() < max && !pending.isEmpty()) {
        batch.add(pending.pollFirst());
      }
    }
    if (batch.isEmpty()) {
      return 0;
    }
    int dropped = 0;
    SchemaParameters params = SchemaParameters.from(properties);
    RetentionApplier applier = new RetentionApplier(params);
    try (RollupPartitions.Session sql =
        RollupPartitions.open(properties.clickhouse(), STATEMENT_TIMEOUT)) {
      for (Drop d : batch) {
        applier.drop(sql, d);
        dropped++;
        LOG.info(
            "Dropped partition {} of {} outside its retention ({} rows)",
            d.partitionId(),
            d.table(),
            d.rows());
      }
    } catch (StatementException | RuntimeException e) {
      LOG.warn(
          "Dropping partitions outside the retention failed; retried in a minute: {}",
          e.getMessage());
      synchronized (lock) {
        for (int i = batch.size() - 1; i >= dropped; i--) {
          pending.addFirst(batch.get(i));
        }
      }
    }
    return dropped;
  }

  /** The state of every governed table under the effective retention ({@code GET}). */
  public List<TableState> tables() throws StatementException {
    SchemaParameters params = SchemaParameters.from(properties);
    try (RollupPartitions.Session sql =
            RollupPartitions.open(properties.clickhouse(), Duration.ofSeconds(30));
        TableCatalog.ReaderSession reader =
            TableCatalog.openReader(properties.clickhouse(), Duration.ofSeconds(30))) {
      return new RetentionApplier(params).show(sql, reader, effective().values());
    }
  }

  @Override
  public void afterPropertiesSet() {
    ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
    s.setPoolSize(1);
    s.setThreadNamePrefix("argos-retention-");
    s.setDaemon(true);
    s.setWaitForTasksToCompleteOnShutdown(false);
    s.initialize();
    var unused =
        s.getScheduledExecutor()
            .scheduleWithFixedDelay(this::scheduledDrain, 1, 1, TimeUnit.MINUTES);
    scheduler = s;
  }

  private void scheduledDrain() {
    try {
      drainDrops(DROPS_PER_MINUTE);
    } catch (RuntimeException e) {
      LOG.warn("Retention partition drop run failed: {}", e.getClass().getSimpleName());
    }
  }

  @Override
  public void destroy() {
    if (scheduler != null) {
      scheduler.shutdown();
    }
  }
}
