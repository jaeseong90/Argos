/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import io.argos.server.alert.api.AlertNotification;
import io.argos.server.alert.api.ChannelHealthEvent;
import io.argos.server.alert.api.NotificationSink;
import io.argos.server.config.ArgosProperties;
import io.argos.server.config.TestingHooks;
import io.argos.server.secrets.SecretUnavailableException;
import io.argos.server.storage.postgres.alert.channel.ChannelRepository;
import io.argos.server.storage.postgres.alert.channel.ChannelRow;
import io.argos.server.storage.postgres.alert.channel.DeliveryRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * The delivery side of the alert engine (12 §5.5, F-S27): implements {@link NotificationSink}.
 *
 * <ul>
 *   <li>{@link #submit} never blocks: it queues the notification on {@code argos-notify} (4
 *       threads, 12 §11) unless {@link #MAX_PENDING} tasks are already waiting, in which case the
 *       notification is dropped and counted ({@code result=dropped}).
 *   <li>Each channel gets one {@code notification_deliveries} row per alert event of the
 *       notification. A failed attempt is retried after 1 s, 5 s and 30 s (3 retries; a development
 *       build may shorten them with {@code argos.testing.notify-retry-delays}); after the last one
 *       the rows are {@code failed}. Rows record the attempt count, status, HTTP status (or SMTP
 *       reply code) and an error code, never a response body.
 *   <li>3 consecutive failed deliveries make the channel {@code failing} and publish {@link
 *       ChannelHealthEvent}{@code (failing=true)}; the next successful delivery publishes {@code
 *       failing=false}. The engine turns those into the {@code channel_failure} self alert sent to
 *       the other channels.
 *   <li>Meters: {@code argos_notifications_total{channel,result}} ({@code sent}, {@code failed},
 *       {@code retried}, {@code dropped}) and {@code argos_notification_failures_total{channel}};
 *       the {@code channel} label is the channel type.
 * </ul>
 *
 * Log lines name the channel id, type and error code only: never a URL, recipient, payload or key.
 */
@Component
public class Notifier implements NotificationSink {

  private static final Logger LOG = LoggerFactory.getLogger(Notifier.class);

  /** Delivery tasks (queued notifications, channel deliveries and waiting retries) at most. */
  public static final int MAX_PENDING = 1_000;

  /** Threads of {@code argos-notify} (12 §11). */
  public static final int THREADS = 4;

  /** Consecutive failed deliveries that make a channel failing (12 §5.5). */
  public static final int FAILING_AFTER = 3;

  /** The waits before the 3 retries (12 §5.5). */
  public static final List<Duration> RETRY_DELAYS =
      List.of(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30));

  private final ChannelRepository channels;
  private final DeliveryRepository deliveries;
  private final ChannelVault vault;
  private final ChannelSenders senders;
  private final ApplicationEventPublisher events;
  private final MeterRegistry meters;
  private final Clock clock;
  private final List<Duration> retryDelays;
  private final ScheduledThreadPoolExecutor executor;
  private final AtomicInteger pending = new AtomicInteger();
  private final AtomicLong lastDropWarning = new AtomicLong();

  @Autowired
  public Notifier(
      ChannelRepository channels,
      DeliveryRepository deliveries,
      ChannelVault vault,
      ChannelSenders senders,
      ApplicationEventPublisher events,
      MeterRegistry meters,
      Clock clock,
      TestingHooks hooks) {
    this(
        channels,
        deliveries,
        vault,
        senders,
        events,
        meters,
        clock,
        retryDelays(
            hooks.active().map(ArgosProperties.Testing::notifyRetryDelays).orElse(List.of())));
  }

  /** A notifier with explicit retry delays (tests). */
  public Notifier(
      ChannelRepository channels,
      DeliveryRepository deliveries,
      ChannelVault vault,
      ChannelSenders senders,
      ApplicationEventPublisher events,
      MeterRegistry meters,
      Clock clock,
      List<Duration> retryDelays) {
    this.channels = Objects.requireNonNull(channels, "channels");
    this.deliveries = Objects.requireNonNull(deliveries, "deliveries");
    this.vault = Objects.requireNonNull(vault, "vault");
    this.senders = Objects.requireNonNull(senders, "senders");
    this.events = Objects.requireNonNull(events, "events");
    this.meters = Objects.requireNonNull(meters, "meters");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.retryDelays = List.copyOf(retryDelays);
    AtomicInteger n = new AtomicInteger();
    this.executor =
        new ScheduledThreadPoolExecutor(
            THREADS,
            task -> {
              Thread t = new Thread(task, "argos-notify-" + n.incrementAndGet());
              t.setDaemon(true);
              return t;
            });
    executor.setRemoveOnCancelPolicy(true);
    executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
  }

  @Override
  public void submit(AlertNotification notification) {
    if (notification == null || notification.recipients().isEmpty()) {
      return;
    }
    if (!admit()) {
      counter("all", "dropped").increment();
      warnDropped();
      return;
    }
    try {
      executor.execute(() -> dispatch(notification));
    } catch (RuntimeException e) {
      pending.decrementAndGet();
      counter("all", "dropped").increment();
    }
  }

  /** Tasks waiting or running now. */
  public int pending() {
    return pending.get();
  }

  /** The retry delays in use. */
  public List<Duration> retryDelays() {
    return retryDelays;
  }

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
    try {
      executor.awaitTermination(2, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** Takes one pending slot unless {@link #MAX_PENDING} are taken (never exceeds the bound). */
  private boolean admit() {
    while (true) {
      int current = pending.get();
      if (current >= MAX_PENDING) {
        return false;
      }
      if (pending.compareAndSet(current, current + 1)) {
        return true;
      }
    }
  }

  /** Resolves the channels of a notification and starts one delivery per channel. */
  private void dispatch(AlertNotification n) {
    try {
      List<UUID> targets =
          n.recipients().allEnabledExcept() != null
              ? channels.enabledIdsExcept(n.recipients().allEnabledExcept())
              : n.recipients().channelIds();
      ChannelMessage message = ChannelMessage.of(n);
      Instant now = clock.instant();
      for (UUID channelId : targets) {
        Optional<ChannelRow> row = channels.find(channelId, now);
        if (row.isEmpty() || !row.get().enabled()) {
          continue;
        }
        String type = row.get().type();
        if (!admit()) {
          counter(type, "dropped").increment();
          warnDropped();
          continue;
        }
        List<UUID> rows;
        try {
          rows = deliveries.insertPending(n.eventIds(), channelId, now);
        } catch (RuntimeException e) {
          pending.decrementAndGet();
          LOG.warn(
              "Notification delivery to channel {} not recorded: {}",
              channelId,
              e.getClass().getSimpleName());
          continue;
        }
        Delivery delivery = new Delivery(channelId, type, message, rows, 1);
        executor.execute(() -> attempt(delivery));
      }
    } catch (RuntimeException e) {
      LOG.warn("Notification dispatch failed: {}", e.getClass().getSimpleName());
    } finally {
      pending.decrementAndGet();
    }
  }

  /** One attempt of a delivery; schedules the next one or records the end. */
  private void attempt(Delivery d) {
    boolean rescheduled = false;
    try {
      Instant now = clock.instant();
      Optional<ChannelRow> row = channels.find(d.channelId(), now);
      if (row.isEmpty() || !row.get().enabled()) {
        deliveries.recordAttempt(
            d.rows(), d.attempt() - 1, "dropped", null, "channel_disabled", now);
        counter(d.type(), "dropped").increment();
        return;
      }
      Integer status = null;
      String error;
      try {
        NotificationChannel.OpenChannel open = vault.open(row.get(), now);
        NotificationChannel.Outcome outcome = senders.of(open.type()).send(open, d.message());
        deliveries.recordAttempt(
            d.rows(), d.attempt(), "sent", outcome.statusCode(), null, clock.instant());
        counter(d.type(), "sent").increment();
        succeeded(row.get());
        return;
      } catch (DeliveryException e) {
        error = e.code();
        status = e.statusCode();
      } catch (DestinationBlockedException e) {
        error = "destination_blocked";
      } catch (SecretUnavailableException e) {
        error = "secret_unavailable";
      } catch (RuntimeException e) {
        error = "internal_error";
      }
      Instant end = clock.instant();
      if (d.attempt() <= retryDelays.size()) {
        deliveries.recordAttempt(d.rows(), d.attempt(), "pending", status, error, end);
        counter(d.type(), "retried").increment();
        Delivery next =
            new Delivery(d.channelId(), d.type(), d.message(), d.rows(), d.attempt() + 1);
        Duration wait = retryDelays.get(d.attempt() - 1);
        ScheduledFuture<?> unused =
            executor.schedule(() -> attempt(next), wait.toMillis(), TimeUnit.MILLISECONDS);
        rescheduled = true;
        return;
      }
      deliveries.recordAttempt(d.rows(), d.attempt(), "failed", status, error, end);
      counter(d.type(), "failed").increment();
      Counter.builder("argos.notification.failures")
          .description("Notification deliveries that failed after every retry")
          .tag("channel", d.type())
          .register(meters)
          .increment();
      LOG.warn(
          "Notification delivery to channel {} ({}) failed after {} attempts: {}",
          d.channelId(),
          d.type(),
          d.attempt(),
          error);
      failed(row.get(), end);
    } catch (RuntimeException e) {
      LOG.warn(
          "Notification delivery to channel {} could not be recorded: {}",
          d.channelId(),
          e.getClass().getSimpleName());
    } finally {
      if (!rescheduled) {
        pending.decrementAndGet();
      }
    }
  }

  private void succeeded(ChannelRow row) {
    Instant at = clock.instant();
    channels
        .recordSuccess(row.id(), at)
        .filter(change -> "failing".equals(change.before()))
        .ifPresent(
            change -> {
              LOG.info("Notification channel {} ({}) delivers again", row.id(), row.type());
              events.publishEvent(
                  new ChannelHealthEvent(row.id(), row.name(), row.type(), false, at));
            });
  }

  private void failed(ChannelRow row, Instant at) {
    channels
        .recordFailure(row.id(), at, FAILING_AFTER)
        .filter(change -> !"failing".equals(change.before()) && "failing".equals(change.after()))
        .ifPresent(
            change -> {
              LOG.warn(
                  "Notification channel {} ({}) is failing after {} consecutive failed deliveries",
                  row.id(),
                  row.type(),
                  change.consecutiveFailures());
              events.publishEvent(
                  new ChannelHealthEvent(row.id(), row.name(), row.type(), true, at));
            });
  }

  private Counter counter(String channel, String result) {
    return Counter.builder("argos.notifications")
        .description("Notification deliveries by channel type and result")
        .tag("channel", channel)
        .tag("result", result)
        .register(meters);
  }

  private void warnDropped() {
    long now = System.nanoTime();
    long last = lastDropWarning.get();
    if (now - last > TimeUnit.MINUTES.toNanos(1) && lastDropWarning.compareAndSet(last, now)) {
      LOG.warn("Notifications are dropped: more than {} deliveries are waiting", MAX_PENDING);
    }
  }

  private static List<Duration> retryDelays(List<Duration> configured) {
    if (configured.isEmpty()) {
      return RETRY_DELAYS;
    }
    if (configured.size() != RETRY_DELAYS.size()) {
      LOG.warn(
          "argos.testing.notify-retry-delays is ignored: it needs exactly {} values",
          RETRY_DELAYS.size());
      return RETRY_DELAYS;
    }
    LOG.warn("argos.testing.notify-retry-delays: notification retries wait {}", configured);
    return List.copyOf(configured);
  }

  /** One delivery of a message to one channel, at its {@code attempt}. */
  private record Delivery(
      UUID channelId, String type, ChannelMessage message, List<UUID> rows, int attempt) {}
}
