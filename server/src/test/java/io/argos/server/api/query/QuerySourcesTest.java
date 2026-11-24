/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.api.error.ApiException;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.AuthTestProperties;
import io.argos.server.auth.Role;
import io.argos.server.storage.query.TableLevel;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** The debug header of 14 §3: ADMIN only, ignored for everybody else. */
@Tag("req:DSN-14-06")
class QuerySourcesTest {

  private static Optional<ArgosPrincipal> principal(Role role) {
    return Optional.of(
        new ArgosPrincipal(UUID.randomUUID(), "u", UUID.randomUUID(), role, Set.of(), false));
  }

  private static MockHttpServletRequest request(String value) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(QuerySources.DEBUG_HEADER, value);
    return request;
  }

  @Test
  @DisplayName("T-API-003a X-Argos-Debug-Source forces the level for ADMIN only")
  void adminOnly() {
    assertThat(QuerySources.forcedLevel(request("1m"), principal(Role.ADMIN)))
        .contains(TableLevel.MINUTE);
    assertThat(QuerySources.forcedLevel(request("RAW"), principal(Role.ADMIN)))
        .contains(TableLevel.RAW);
    assertThat(QuerySources.forcedLevel(request("1h"), principal(Role.ADMIN)))
        .contains(TableLevel.HOUR);
    assertThat(QuerySources.forcedLevel(request("1m"), principal(Role.OPERATOR))).isEmpty();
    assertThat(QuerySources.forcedLevel(request("1m"), principal(Role.VIEWER))).isEmpty();
    assertThat(QuerySources.forcedLevel(request("1m"), Optional.empty())).isEmpty();
    assertThat(QuerySources.forcedLevel(new MockHttpServletRequest(), principal(Role.ADMIN)))
        .isEmpty();
    assertThat(QuerySources.forcedLevel(null, principal(Role.ADMIN))).isEmpty();
    // A non-admin's unknown value is ignored too; only an admin is told it is wrong.
    assertThat(QuerySources.forcedLevel(request("5m"), principal(Role.VIEWER))).isEmpty();
    assertThatThrownBy(() -> QuerySources.forcedLevel(request("5m"), principal(Role.ADMIN)))
        .isInstanceOf(ApiException.class);
  }

  @Test
  @DisplayName(
      "T-ST-040g the raw and rollup retentions of every family are the effective ones: values"
          + " stored through PUT /admin/settings/retention win over argos.retention.*, a change"
          + " applies to the next plan after retentionChanged() without a restart (C-238 (3))")
  void effectiveRetention() {
    AtomicReference<Map<String, Integer>> stored = new AtomicReference<>(Map.of());
    AtomicInteger reads = new AtomicInteger();
    QuerySources sources =
        new QuerySources(
            AuthTestProperties.defaults(),
            Clock.fixed(Instant.parse("2026-11-24T00:00:00Z"), ZoneOffset.UTC),
            () -> {
              reads.incrementAndGet();
              return stored.get();
            });
    // The configuration (12 §10 defaults): xlog 30 d, stat 14 d, metric 14 d, 1m 90 d, 1h 365 d.
    assertThat(sources.transactions().retention())
        .isEqualTo(
            new SourceSelector.Retention(
                Duration.ofDays(30), Duration.ofDays(90), Duration.ofDays(365)));
    assertThat(sources.metrics().retention().raw()).isEqualTo(Duration.ofDays(14));
    assertThat(sources.statistics().retention().raw()).isEqualTo(Duration.ofDays(14));

    stored.set(
        Map.of("xlog_days", 3, "stat_raw_days", 5, "metric_raw_days", 6, "rollup_1m_days", 40));
    // Within the reuse time the stored values are not read again ...
    assertThat(sources.transactions().retention().raw()).isEqualTo(Duration.ofDays(30));
    int before = reads.get();
    // ... a PUT of this server forgets them: the next plan reads and uses them.
    sources.retentionChanged();
    SourceSelector.Retention tx = sources.transactions().retention();
    assertThat(tx.raw()).isEqualTo(Duration.ofDays(3));
    assertThat(tx.minute()).isEqualTo(Duration.ofDays(40));
    assertThat(tx.hour()).isEqualTo(Duration.ofDays(365));
    assertThat(sources.instanceTransactions().retention().raw()).isEqualTo(Duration.ofDays(3));
    assertThat(sources.transactionsMinuteOnly().retention().raw()).isEqualTo(Duration.ofDays(3));
    assertThat(sources.statistics().retention().raw()).isEqualTo(Duration.ofDays(5));
    assertThat(sources.metrics().retention().raw()).isEqualTo(Duration.ofDays(6));
    assertThat(reads.get()).as("one read for all the plans after the change").isEqualTo(before + 1);

    // A plan whose range starts before the new raw retention is retention_exceeded.
    Instant now = sources.now();
    SourcePlan plan =
        sources.plan(
            now.minus(Duration.ofDays(4)),
            now.minus(Duration.ofDays(4)).plus(Duration.ofMinutes(10)),
            null,
            Duration.ofDays(400),
            sources.transactions(),
            null);
    assertThat(plan.retentionExceeded()).isTrue();
  }

  @Test
  @DisplayName(
      "T-ST-040g stored retention values that cannot be read: the last values read are kept,"
          + " the configuration before any read")
  void unreadableStoredValues() {
    AtomicReference<RuntimeException> failure = new AtomicReference<>();
    QuerySources sources =
        new QuerySources(
            AuthTestProperties.defaults(),
            Clock.systemUTC(),
            () -> {
              if (failure.get() != null) {
                throw failure.get();
              }
              return Map.of("xlog_days", 7);
            });
    failure.set(new IllegalStateException("PostgreSQL down"));
    assertThat(sources.transactions().retention().raw()).isEqualTo(Duration.ofDays(30));
    failure.set(null);
    sources.retentionChanged();
    assertThat(sources.transactions().retention().raw()).isEqualTo(Duration.ofDays(7));
    failure.set(new IllegalStateException("PostgreSQL down"));
    sources.retentionChanged();
    assertThat(sources.transactions().retention().raw()).isEqualTo(Duration.ofDays(7));
  }
}
