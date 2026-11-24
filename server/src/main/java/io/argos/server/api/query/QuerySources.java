/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.query;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.Role;
import io.argos.server.config.ArgosProperties;
import io.argos.server.ops.retention.RetentionService;
import io.argos.server.storage.query.TableLevel;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@link SourceSelector} with the server's inputs: the effective retentions (13 §8: {@code
 * argos.retention.*} with the values set through {@code PUT /admin/settings/retention} in place, so
 * that a change applies to the raw-retention boundaries of the next request without a restart,
 * ADR-0010 C-238 (3)), the clock, and the debug header {@value #DEBUG_HEADER} (14 §3: tests and
 * consistency checks may force {@code raw}, {@code 1m} or {@code 1h}; honoured for ADMIN sessions
 * only, ignored for every other request).
 *
 * <p>The table families: {@link #transactions()} ({@code xlog}, {@code app_stat_*}, {@code
 * service_stat_*}), {@link #instanceTransactions()} ({@code xlog}, {@code instance_stat_1m/1h},
 * ADR-0010 C-212 (1)), {@link #transactionsMinuteOnly()} ({@code error_stat_1m}: no hour rollup),
 * {@link #metrics()} ({@code metric}, {@code metric_*}), {@link #statistics()} ({@code sql_stat_*},
 * {@code ext_stat_*}).
 */
@Component
public class QuerySources {

  /** The debug header of 14 §3. */
  public static final String DEBUG_HEADER = "X-Argos-Debug-Source";

  /**
   * How long the retention values stored through the API are reused before they are read again (a
   * {@code PUT /admin/settings/retention} of this server invalidates them at once, {@link
   * #retentionChanged()}; the wait only covers a change by another process such as {@code
   * argos-admin}).
   */
  static final Duration OVERRIDES_TTL = Duration.ofSeconds(10);

  private static final Logger LOG = LoggerFactory.getLogger(QuerySources.class);

  private final ArgosProperties.Retention retention;
  private final Clock clock;
  private final Supplier<Map<String, Integer>> overrides;
  private final AtomicLong generation = new AtomicLong();
  private volatile Cached cached;
  private volatile Map<String, Integer> lastRead = Map.of();

  /** The values stored through the API and when they were read ({@link System#nanoTime()}). */
  private record Cached(Map<String, Integer> days, long readNanos) {}

  /** Configuration retentions only (tests and contexts without the run-time retention). */
  public QuerySources(ArgosProperties properties, Clock clock) {
    this(properties, clock, Map::of);
  }

  /**
   * The server's sources: the raw and rollup retentions are the effective ones of {@link
   * RetentionService} (values set through {@code PUT /admin/settings/retention} win over {@code
   * argos.retention.*}; ADR-0010 C-238 (3)).
   */
  @Autowired
  public QuerySources(
      ArgosProperties properties, Clock clock, ObjectProvider<RetentionService> retention) {
    this(properties, clock, overridesOf(retention.getIfAvailable()));
  }

  /**
   * Sources whose retentions are {@code argos.retention.*} with {@code overrides} in place: API
   * field ({@code xlog_days}, {@code metric_raw_days}, {@code stat_raw_days}, {@code
   * rollup_1m_days}, {@code rollup_1h_days}) to whole days.
   */
  QuerySources(ArgosProperties properties, Clock clock, Supplier<Map<String, Integer>> overrides) {
    this.retention = properties.retention();
    this.clock = clock;
    this.overrides = overrides;
  }

  /** The values of {@code service} that come from the settings table (not the configuration). */
  private static Supplier<Map<String, Integer>> overridesOf(RetentionService service) {
    if (service == null) {
      return Map::of;
    }
    return () -> {
      RetentionService.Effective e = service.effective();
      Map<String, Integer> out = new HashMap<>();
      e.sources()
          .forEach(
              (field, source) -> {
                if ("settings".equals(source)) {
                  out.put(field, e.values().get(field));
                }
              });
      return Map.copyOf(out);
    };
  }

  /**
   * Forgets the retention values read from the settings table: the next plan reads them again
   * (called after {@code PUT /admin/settings/retention}).
   */
  public void retentionChanged() {
    generation.incrementAndGet();
    cached = null;
  }

  /**
   * The retention values stored through the API, read at most every {@link #OVERRIDES_TTL}; when
   * they cannot be read, the last values read (none at first: the configuration applies). A read
   * that overlapped {@link #retentionChanged()} is used once but not kept.
   */
  private Map<String, Integer> overrides() {
    Cached c = cached;
    long now = System.nanoTime();
    if (c != null && now - c.readNanos() < OVERRIDES_TTL.toNanos()) {
      return c.days();
    }
    long before = generation.get();
    try {
      Map<String, Integer> days = overrides.get();
      Cached read = new Cached(days == null ? Map.of() : Map.copyOf(days), now);
      lastRead = read.days();
      if (generation.get() == before) {
        cached = read;
      }
      return read.days();
    } catch (RuntimeException e) {
      LOG.debug("Stored retention values unavailable: {}", e.getClass().getSimpleName());
      return lastRead;
    }
  }

  /** The configured value, or the value stored through the API under {@code field}. */
  private static Duration effective(Map<String, Integer> overrides, String field, Duration conf) {
    Integer days = overrides.get(field);
    return days == null ? conf : Duration.ofDays(days);
  }

  /**
   * The effective raw retention of {@code xlog} (the {@code retention_exceeded} boundary of the
   * XLog, the transaction search, the trace view and the baselines; ADR-0010 C-238 (3)).
   */
  public Duration xlogRetention() {
    return effective(overrides(), "xlog_days", retention.xlog());
  }

  /** The effective retention of {@code profile_step} (a profile older than it is expired). */
  public Duration profileRetention() {
    return effective(overrides(), "profile_days", retention.profile());
  }

  /** The effective raw retention of {@code sql_stat_raw} and {@code ext_stat_raw}. */
  public Duration statRetention() {
    return effective(overrides(), "stat_raw_days", retention.stat());
  }

  /** {@code xlog} with {@code app_stat_1m/1h} and {@code service_stat_1m/1h}. */
  public SourceSelector.Family transactions() {
    return family("transactions", "xlog_days", retention.xlog(), true);
  }

  /**
   * {@code xlog} with {@code instance_stat_1m/1h}: per-instance transactions (the dashboard with an
   * instance filter or exclusion, its instance tiles, the instance comparison; ADR-0010 C-212 (1)).
   */
  public SourceSelector.Family instanceTransactions() {
    return family("transactions-instance", "xlog_days", retention.xlog(), true);
  }

  /** {@code xlog} with {@code error_stat_1m} (no hour rollup). */
  public SourceSelector.Family transactionsMinuteOnly() {
    return family("transactions-minute", "xlog_days", retention.xlog(), false);
  }

  /** {@code metric} with {@code metric_1m/1h}. */
  public SourceSelector.Family metrics() {
    return family("metrics", "metric_raw_days", retention.metric(), true);
  }

  /** {@code sql_stat_raw}/{@code ext_stat_raw} with their 1m/1h rollups. */
  public SourceSelector.Family statistics() {
    return family("statistics", "stat_raw_days", retention.stat(), true);
  }

  private SourceSelector.Family family(String name, String rawField, Duration raw, boolean hourly) {
    Map<String, Integer> o = overrides();
    return new SourceSelector.Family(
        name,
        new SourceSelector.Retention(
            effective(o, rawField, raw),
            effective(o, "rollup_1m_days", retention.rollup1m()),
            effective(o, "rollup_1h_days", retention.rollup1h())),
        hourly);
  }

  /** The current time of the plans. */
  public Instant now() {
    return clock.instant();
  }

  /**
   * Plans a range of the current request.
   *
   * @param from range start
   * @param to range end (exclusive)
   * @param step the {@code step} parameter, null or blank for automatic
   * @param maxRange the endpoint's longest range
   * @param family the table family
   * @param request the HTTP request (debug header), may be null
   */
  public SourcePlan plan(
      Instant from,
      Instant to,
      String step,
      Duration maxRange,
      SourceSelector.Family family,
      HttpServletRequest request) {
    return SourceSelector.plan(
        new SourceSelector.Request(
            from,
            to,
            SourceSelector.parseStep(step),
            maxRange,
            family,
            clock.instant(),
            forcedLevel(request, ArgosPrincipal.current()).orElse(null)));
  }

  /**
   * The level forced by {@value #DEBUG_HEADER}: only for an ADMIN principal; empty without the
   * header or for anyone else.
   *
   * @throws ApiException 400 {@code invalid_request} for an ADMIN with an unknown value
   */
  static Optional<TableLevel> forcedLevel(
      HttpServletRequest request, Optional<ArgosPrincipal> principal) {
    if (request == null) {
      return Optional.empty();
    }
    String value = request.getHeader(DEBUG_HEADER);
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    if (principal.isEmpty() || !principal.get().hasRole(Role.ADMIN)) {
      return Optional.empty();
    }
    return switch (value.strip().toLowerCase(Locale.ROOT)) {
      case "raw" -> Optional.of(TableLevel.RAW);
      case "1m" -> Optional.of(TableLevel.MINUTE);
      case "1h" -> Optional.of(TableLevel.HOUR);
      default ->
          throw new ApiException(ErrorCode.INVALID_REQUEST, "Unknown debug source")
              .detail("x-argos-debug-source", "unsupported_value");
    };
  }
}
