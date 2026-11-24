/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The agent audit path (ADR-0010 C-158 (1)): rate limit, suppressed count, bounded queue. */
@Tag("req:SEC-80")
class AsyncAuditDispatcherTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** A clock the test moves. */
  static final class StepClock extends Clock {
    private volatile Instant now = Instant.parse("2026-11-24T00:00:00Z");

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private static void await(List<?> list, int size) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (list.size() < size && System.nanoTime() < deadline) {
      Thread.sleep(10);
    }
  }

  private static void submit(AsyncAuditDispatcher d, String ip) {
    d.submit(
        AuditEvent.AGENT_AUTH_FAILED,
        ip,
        new Actor(Actor.Type.AGENT, "argos_abcdefgh", "argos_abcdefgh", ip, null),
        new Target("token", "argos_abcdefgh", null),
        AuditResult.failure("token_unknown"),
        null);
  }

  @Test
  @DisplayName(
      "T-SV-044f one record per event and client address a minute; the suppressed count goes"
          + " into the next window's record, other addresses are independent")
  void rateLimit() throws InterruptedException {
    List<AuditRecord> written = new CopyOnWriteArrayList<>();
    StepClock clock = new StepClock();
    DefaultAuditService audit =
        new DefaultAuditService(List.of(written::add), new SimpleMeterRegistry());
    AsyncAuditDispatcher d = new AsyncAuditDispatcher(audit, 1, new SimpleMeterRegistry(), clock);
    try {
      for (int i = 0; i < 21; i++) {
        submit(d, "10.0.0.7");
      }
      submit(d, "10.0.0.8");
      await(written, 2);
      Thread.sleep(100);
      assertThat(written).hasSize(2);
      assertThat(written)
          .extracting(AuditRecord::ip)
          .containsExactlyInAnyOrder("10.0.0.7", "10.0.0.8");
      assertThat(written.get(0).actorType()).isEqualTo("agent");
      assertThat(written.get(0).result()).isEqualTo("failure");
      assertThat(written.get(0).reason()).isEqualTo("token_unknown");
      clock.advance(Duration.ofSeconds(61));
      submit(d, "10.0.0.7");
      await(written, 3);
      AuditRecord third = written.get(2);
      assertThat(third.ip()).isEqualTo("10.0.0.7");
      assertThat(JSON.readTree(third.afterJson()).path("suppressed").asLong()).isEqualTo(20);
    } finally {
      d.destroy();
    }
  }

  @Test
  @DisplayName(
      "T-SV-044f a window that ended with suppressed events is summarised by the sweep with the"
          + " count")
  void sweepSummary() throws InterruptedException {
    List<AuditRecord> written = new CopyOnWriteArrayList<>();
    StepClock clock = new StepClock();
    DefaultAuditService audit =
        new DefaultAuditService(List.of(written::add), new SimpleMeterRegistry());
    AsyncAuditDispatcher d = new AsyncAuditDispatcher(audit, 1, new SimpleMeterRegistry(), clock);
    try {
      for (int i = 0; i < 5; i++) {
        submit(d, "10.0.0.9");
      }
      await(written, 1);
      clock.advance(Duration.ofSeconds(61));
      d.sweep();
      await(written, 2);
      assertThat(written).hasSize(2);
      assertThat(JSON.readTree(written.get(1).afterJson()).path("suppressed").asLong())
          .isEqualTo(4);
      d.sweep();
      Thread.sleep(100);
      assertThat(written).as("an idle key is forgotten, no second summary").hasSize(2);
    } finally {
      d.destroy();
    }
  }

  @Test
  @DisplayName(
      "T-SV-044f submit never blocks: with the writer stuck, a full queue drops and counts"
          + " argos_audit_dropped_total{reason=queue_full}")
  void neverBlocks() throws InterruptedException {
    CountDownLatch release = new CountDownLatch(1);
    AuditSink stuck =
        r -> {
          try {
            release.await(30, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        };
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    DefaultAuditService audit = new DefaultAuditService(List.of(stuck), registry);
    AsyncAuditDispatcher d = new AsyncAuditDispatcher(audit, 1, registry, Clock.systemUTC());
    try {
      int n = AsyncAuditDispatcher.QUEUE_CAPACITY + 500;
      long start = System.nanoTime();
      for (int i = 0; i < n; i++) {
        submit(d, "192.0.2." + (i % 250) + "-" + i);
      }
      long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertThat(elapsedMs).as("%d submits with the writer stuck", n).isLessThan(5_000);
      double dropped =
          registry
              .get(AsyncAuditDispatcher.DROPPED_METER)
              .tag("reason", "queue_full")
              .counter()
              .count();
      assertThat(dropped).isGreaterThanOrEqualTo(n - AsyncAuditDispatcher.QUEUE_CAPACITY - 1.0);
    } finally {
      release.countDown();
      d.destroy();
    }
  }
}
