/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.argos.server.alert.api.AlertNotification;
import io.argos.server.alert.api.ChannelHealthEvent;
import io.argos.server.storage.postgres.alert.channel.ChannelRepository;
import io.argos.server.storage.postgres.alert.channel.ChannelRow;
import io.argos.server.storage.postgres.alert.channel.DeliveryRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/** The Notifier's retries, delivery rows, channel health and bounds with stubbed storage. */
@Tag("req:F-S27")
class NotifierTest {

  private static final List<Duration> DELAYS =
      List.of(Duration.ofMillis(60), Duration.ofMillis(120), Duration.ofMillis(180));

  private final ChannelRepository channels = mock(ChannelRepository.class);
  private final DeliveryRepository deliveries = mock(DeliveryRepository.class);
  private final ChannelVault vault = mock(ChannelVault.class);
  private final ChannelSenders senders = mock(ChannelSenders.class);
  private final NotificationChannel webhook = mock(NotificationChannel.class);
  private final List<Object> events = new CopyOnWriteArrayList<>();
  private final ApplicationEventPublisher publisher = events::add;
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final List<Instant> attempts = new CopyOnWriteArrayList<>();
  private Notifier notifier;

  private Notifier notifier() {
    notifier =
        new Notifier(
            channels, deliveries, vault, senders, publisher, meters, Clock.systemUTC(), DELAYS);
    return notifier;
  }

  @AfterEach
  void stop() {
    if (notifier != null) {
      notifier.shutdown();
    }
  }

  private ChannelRow channel(UUID id, String status) {
    ChannelRow row =
        new ChannelRow(
            id,
            "ops-" + id,
            "webhook",
            "v1:x",
            "{}",
            true,
            status,
            0,
            null,
            null,
            Instant.now(),
            Instant.now(),
            1);
    when(channels.find(eq(id), any())).thenReturn(Optional.of(row));
    when(vault.open(eq(row), any()))
        .thenReturn(
            new NotificationChannel.OpenChannel(
                id,
                row.name(),
                ChannelType.WEBHOOK,
                ChannelConfig.url("https://x.test/h", false),
                List.of("s")));
    return row;
  }

  private static AlertNotification notification(UUID channel) {
    AlertNotification sample = ChannelMessageTest.sample();
    return new AlertNotification(
        sample.group(),
        sample.kind(),
        sample.items(),
        sample.omitted(),
        sample.title(),
        sample.body(),
        AlertNotification.Recipients.of(List.of(channel)),
        sample.eventIds());
  }

  private double count(String result) {
    return meters.find("argos.notifications").tag("result", result).counters().stream()
        .mapToDouble(io.micrometer.core.instrument.Counter::count)
        .sum();
  }

  @Test
  @DisplayName(
      "T-SV-034a a failing delivery is retried 3 times after the configured waits, then its rows"
          + " are failed with the status and error code")
  void retries() throws Exception {
    UUID id = UUID.randomUUID();
    channel(id, "ok");
    List<UUID> rows = List.of(UUID.randomUUID());
    when(deliveries.insertPending(anyList(), eq(id), any())).thenReturn(rows);
    when(senders.of(ChannelType.WEBHOOK)).thenReturn(webhook);
    when(webhook.send(any(), any()))
        .thenAnswer(
            inv -> {
              attempts.add(Instant.now());
              throw new DeliveryException("http_status", 503);
            });
    when(channels.recordFailure(eq(id), any(), anyInt()))
        .thenReturn(Optional.of(new ChannelRepository.HealthChange("ok", "ok", 1)));

    notifier().submit(notification(id));

    await().atMost(5, TimeUnit.SECONDS).until(() -> count("failed") == 1.0);
    assertThat(attempts).hasSize(4);
    for (int i = 1; i < attempts.size(); i++) {
      Duration gap = Duration.between(attempts.get(i - 1), attempts.get(i));
      assertThat(gap).as("wait before retry %d", i).isGreaterThanOrEqualTo(DELAYS.get(i - 1));
    }
    verify(deliveries)
        .recordAttempt(eq(rows), eq(1), eq("pending"), eq(503), eq("http_status"), any());
    verify(deliveries)
        .recordAttempt(eq(rows), eq(2), eq("pending"), eq(503), eq("http_status"), any());
    verify(deliveries)
        .recordAttempt(eq(rows), eq(3), eq("pending"), eq(503), eq("http_status"), any());
    verify(deliveries)
        .recordAttempt(eq(rows), eq(4), eq("failed"), eq(503), eq("http_status"), any());
    assertThat(count("retried")).isEqualTo(3.0);
    assertThat(
            meters.find("argos.notification.failures").tag("channel", "webhook").counter().count())
        .isEqualTo(1.0);
    assertThat(events).isEmpty();
    await().atMost(2, TimeUnit.SECONDS).until(() -> notifier.pending() == 0);
  }

  @Test
  @DisplayName(
      "T-SV-034b the third consecutive failed delivery makes the channel failing and publishes"
          + " ChannelHealthEvent(failing=true); the next success publishes failing=false")
  void health() throws Exception {
    UUID id = UUID.randomUUID();
    channel(id, "ok");
    when(deliveries.insertPending(anyList(), eq(id), any())).thenReturn(List.of(UUID.randomUUID()));
    when(senders.of(ChannelType.WEBHOOK)).thenReturn(webhook);
    AtomicInteger failures = new AtomicInteger();
    when(channels.recordFailure(eq(id), any(), eq(Notifier.FAILING_AFTER)))
        .thenAnswer(
            inv -> {
              int n = failures.incrementAndGet();
              String before = n > Notifier.FAILING_AFTER ? "failing" : "ok";
              String after = n >= Notifier.FAILING_AFTER ? "failing" : "ok";
              return Optional.of(new ChannelRepository.HealthChange(before, after, n));
            });
    when(webhook.send(any(), any())).thenThrow(new DeliveryException("connect_failed"));
    notifier();
    for (int i = 0; i < 3; i++) {
      notifier.submit(notification(id));
    }
    await().atMost(5, TimeUnit.SECONDS).until(() -> count("failed") == 3.0);
    assertThat(events)
        .singleElement()
        .isInstanceOfSatisfying(
            ChannelHealthEvent.class,
            e -> {
              assertThat(e.channelId()).isEqualTo(id);
              assertThat(e.failing()).isTrue();
              assertThat(e.type()).isEqualTo("webhook");
            });

    doReturn(new NotificationChannel.Outcome(200, 5)).when(webhook).send(any(), any());
    when(channels.recordSuccess(eq(id), any()))
        .thenReturn(Optional.of(new ChannelRepository.HealthChange("failing", "ok", 0)));
    notifier.submit(notification(id));
    await().atMost(5, TimeUnit.SECONDS).until(() -> events.size() == 2);
    assertThat(((ChannelHealthEvent) events.get(1)).failing()).isFalse();
    assertThat(count("sent")).isEqualTo(1.0);
  }

  @Test
  @DisplayName(
      "T-SV-034c a self alert goes to every enabled channel except the failing one, one row per"
          + " alert event and channel")
  void allEnabledExcept() throws Exception {
    UUID failing = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    channel(other, "ok");
    when(channels.enabledIdsExcept(failing)).thenReturn(List.of(other));
    when(deliveries.insertPending(anyList(), eq(other), any()))
        .thenReturn(List.of(UUID.randomUUID()));
    when(senders.of(ChannelType.WEBHOOK)).thenReturn(webhook);
    when(webhook.send(any(), any())).thenReturn(new NotificationChannel.Outcome(204, 3));
    when(channels.recordSuccess(eq(other), any()))
        .thenReturn(Optional.of(new ChannelRepository.HealthChange("ok", "ok", 0)));
    AlertNotification sample = ChannelMessageTest.sample();
    notifier()
        .submit(
            new AlertNotification(
                sample.group(),
                AlertNotification.Kind.SELF,
                sample.items(),
                0,
                "channel failing",
                "body",
                AlertNotification.Recipients.allEnabledExcept(failing),
                sample.eventIds()));
    await().atMost(5, TimeUnit.SECONDS).until(() -> count("sent") == 1.0);
    verify(deliveries).insertPending(eq(sample.eventIds()), eq(other), any());
    verify(deliveries, never()).insertPending(anyList(), eq(failing), any());
    verify(deliveries).recordAttempt(anyList(), eq(1), eq("sent"), eq(204), isNull(), any());
  }

  @Test
  @DisplayName(
      "submit never blocks: beyond the pending bound notifications are dropped and counted")
  void bounded() throws Exception {
    UUID id = UUID.randomUUID();
    channel(id, "ok");
    when(deliveries.insertPending(anyList(), eq(id), any())).thenReturn(List.of(UUID.randomUUID()));
    when(senders.of(ChannelType.WEBHOOK)).thenReturn(webhook);
    CountDownLatch release = new CountDownLatch(1);
    when(webhook.send(any(), any()))
        .thenAnswer(
            inv -> {
              release.await(10, TimeUnit.SECONDS);
              return new NotificationChannel.Outcome(200, 1);
            });
    when(channels.recordSuccess(eq(id), any())).thenReturn(Optional.empty());
    notifier();
    AlertNotification n = notification(id);
    long start = System.nanoTime();
    List<Integer> seen = new ArrayList<>();
    for (int i = 0; i < Notifier.MAX_PENDING + 50; i++) {
      notifier.submit(n);
      seen.add(notifier.pending());
    }
    assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
    assertThat(seen).allMatch(p -> p <= Notifier.MAX_PENDING);
    assertThat(count("dropped")).isGreaterThanOrEqualTo(50.0);
    release.countDown();
  }
}
