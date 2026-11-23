/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.postgres.alert.channel.ChannelRepository;
import io.argos.server.storage.postgres.alert.channel.DeliveryRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The daily cleanup of the delivery side (ADR-0010 C-111 (2)): {@code notification_deliveries} rows
 * older than the alert history retention ({@code argos.retention.alert-events}, 365 days) are
 * deleted in batches, and webhook signing keys whose rotation grace ended are deleted (16 §8.4).
 * First run 20 minutes after the start; a failure is logged and the next day retries.
 */
@Component
public class DeliveryCleanupJob {

  private static final Logger LOG = LoggerFactory.getLogger(DeliveryCleanupJob.class);

  private final DeliveryRepository deliveries;
  private final ChannelRepository channels;
  private final Clock clock;
  private final Duration retention;

  public DeliveryCleanupJob(
      DeliveryRepository deliveries,
      ChannelRepository channels,
      Clock clock,
      ArgosProperties properties) {
    this.deliveries = deliveries;
    this.channels = channels;
    this.clock = clock;
    this.retention = properties.retention().alertEvents();
  }

  /** What one run deleted. */
  public record Result(long deliveries, int signingKeys) {}

  /** The scheduled run (daily). */
  @Scheduled(fixedDelayString = "PT24H", initialDelayString = "PT20M")
  public void scheduled() {
    run();
  }

  /** One run at the server clock's now. */
  public Result run() {
    Instant now = clock.instant();
    long rows = 0;
    int keys = 0;
    try {
      rows = deliveries.deleteOlderThan(now.minus(retention));
    } catch (RuntimeException e) {
      LOG.warn("Notification delivery cleanup failed: {}", e.getClass().getSimpleName());
    }
    try {
      keys = channels.deleteRetiredKeys(now);
    } catch (RuntimeException e) {
      LOG.warn("Signing key cleanup failed: {}", e.getClass().getSimpleName());
    }
    if (rows > 0 || keys > 0) {
      LOG.info("Deleted {} notification deliveries and {} retired signing keys", rows, keys);
    }
    return new Result(rows, keys);
  }
}
