/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.query;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.Role;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.query.TableLevel;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * {@link SourceSelector} with the server's inputs: the retentions of {@code argos.retention.*} (13
 * §8), the clock, and the debug header {@value #DEBUG_HEADER} (14 §3: tests and consistency checks
 * may force {@code raw}, {@code 1m} or {@code 1h}; honoured for ADMIN sessions only, ignored for
 * every other request).
 *
 * <p>The table families: {@link #transactions()} ({@code xlog}, {@code app_stat_*}, {@code
 * service_stat_*}), {@link #transactionsMinuteOnly()} ({@code instance_stat_1m}, {@code
 * error_stat_1m}: no hour rollup), {@link #metrics()} ({@code metric}, {@code metric_*}), {@link
 * #statistics()} ({@code sql_stat_*}, {@code ext_stat_*}).
 */
@Component
public class QuerySources {

  /** The debug header of 14 §3. */
  public static final String DEBUG_HEADER = "X-Argos-Debug-Source";

  private final ArgosProperties.Retention retention;
  private final Clock clock;

  public QuerySources(ArgosProperties properties, Clock clock) {
    this.retention = properties.retention();
    this.clock = clock;
  }

  /** {@code xlog} with {@code app_stat_1m/1h} and {@code service_stat_1m/1h}. */
  public SourceSelector.Family transactions() {
    return family("transactions", retention.xlog(), true);
  }

  /** {@code xlog} with {@code instance_stat_1m} or {@code error_stat_1m} (no hour rollup). */
  public SourceSelector.Family transactionsMinuteOnly() {
    return family("transactions-minute", retention.xlog(), false);
  }

  /** {@code metric} with {@code metric_1m/1h}. */
  public SourceSelector.Family metrics() {
    return family("metrics", retention.metric(), true);
  }

  /** {@code sql_stat_raw}/{@code ext_stat_raw} with their 1m/1h rollups. */
  public SourceSelector.Family statistics() {
    return family("statistics", retention.stat(), true);
  }

  private SourceSelector.Family family(String name, Duration raw, boolean hourly) {
    return new SourceSelector.Family(
        name,
        new SourceSelector.Retention(raw, retention.rollup1m(), retention.rollup1h()),
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
