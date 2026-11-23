/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.token;

import io.argos.server.alert.metric.SelfProvider;
import io.argos.server.storage.postgres.token.AgentTokenStore;
import io.argos.server.storage.postgres.token.AgentTokenStore.StoredToken;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The token expiry job (12 §7.1, 16 §2.3 "만료"): every {@value #PERIOD_SECONDS} s it sweeps the
 * expired tokens ({@link TokenService#expireDue}: {@code EXPIRED}, cache entry dropped, live
 * sessions closed) and refreshes the expiry gauges.
 *
 * <ul>
 *   <li>{@value #SECONDS_GAUGE}{@code {token_prefix}} ({@code argos_agent_token_expiry_seconds}, 16
 *       §2.3, 12 §7.1): seconds until each authenticating token stops authenticating (its expiry,
 *       or the end of its rotation grace when earlier). Tokens without an expiry have no series.
 *   <li>{@value SelfProvider#TOKEN_GAUGE} ({@code argos_agent_token_expiry_days}): the smallest of
 *       those values in days, read by the alert template {@code cert_token_expiry} (01 appendix A:
 *       warning at 30 days, critical at 7); no value (NaN) when no token expires.
 * </ul>
 *
 * A database failure skips the run (logged without detail) and the next one retries.
 */
@Component
public class TokenExpiryJob {

  /** Seconds between runs. */
  public static final long PERIOD_SECONDS = 60;

  /** Micrometer name of the per-token gauge ({@code argos_agent_token_expiry_seconds}). */
  public static final String SECONDS_GAUGE = "argos.agent.token.expiry.seconds";

  private static final Logger LOG = LoggerFactory.getLogger(TokenExpiryJob.class);

  private final TokenService tokens;
  private final AgentTokenStore store;
  private final Clock clock;
  private final MultiGauge perToken;
  private final AtomicReference<Double> minDays = new AtomicReference<>(Double.NaN);

  public TokenExpiryJob(
      TokenService tokens, AgentTokenStore store, Clock clock, MeterRegistry meters) {
    this.tokens = tokens;
    this.store = store;
    this.clock = clock;
    this.perToken =
        MultiGauge.builder(SECONDS_GAUGE)
            .description(
                "Seconds until an agent token stops authenticating (its expiry or rotation grace)")
            .register(meters);
    Gauge.builder(SelfProvider.TOKEN_GAUGE, minDays, AtomicReference::get)
        .description("Days until the first agent token stops authenticating (NaN: none expires)")
        .register(meters);
  }

  /** One run: the sweep, then the gauges. */
  @Scheduled(fixedDelayString = "PT60S", initialDelayString = "PT60S")
  public void run() {
    try {
      tokens.expireDue();
    } catch (DataAccessException | IllegalStateException e) {
      LOG.warn("Agent token expiry sweep failed: {}", e.getClass().getSimpleName());
    }
    try {
      refreshGauges();
    } catch (DataAccessException e) {
      LOG.warn("Agent token expiry gauges not refreshed: {}", e.getClass().getSimpleName());
    }
  }

  /** Recomputes both gauges from the authenticating tokens. */
  public void refreshGauges() {
    Instant now = clock.instant();
    Map<String, Double> byPrefix = new LinkedHashMap<>();
    double min = Double.NaN;
    for (StoredToken t : store.activeWithEnd(now)) {
      Instant end = t.expiresAt();
      if (t.graceUntil() != null && (end == null || t.graceUntil().isBefore(end))) {
        end = t.graceUntil();
      }
      if (end == null) {
        continue;
      }
      double seconds = Math.max(0, Duration.between(now, end).toMillis() / 1000.0);
      // Two tokens may share a prefix (16 §2.1 allows it): the series keeps the earlier end.
      byPrefix.merge(t.prefix(), seconds, Math::min);
      min = Double.isNaN(min) ? seconds : Math.min(min, seconds);
    }
    List<MultiGauge.Row<?>> rows = new ArrayList<>();
    byPrefix.forEach(
        (prefix, seconds) -> rows.add(MultiGauge.Row.of(Tags.of("token_prefix", prefix), seconds)));
    perToken.register(rows, true);
    minDays.set(Double.isNaN(min) ? Double.NaN : min / 86_400.0);
  }
}
