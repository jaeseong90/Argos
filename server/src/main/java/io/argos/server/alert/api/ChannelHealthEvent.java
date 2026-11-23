/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A change of a notification channel's health (12 §5.5, F-S27; WO-25 plan §3.2), published by the
 * delivery side as a Spring application event: {@code failing = true} after 3 consecutive failed
 * deliveries of the channel, {@code false} on the next successful one. The alert engine's server
 * self rule {@code channel_failure} turns it into a FIRING or RESOLVED alert sent to every other
 * enabled channel ({@link AlertNotification.Recipients#allEnabledExcept}). Listeners must not
 * block.
 *
 * @param channelId {@code notification_channels.id}
 * @param name the channel's name (operator text)
 * @param type {@code email}, {@code slack} or {@code webhook}
 * @param failing whether the channel is failing now
 * @param at server time of the change
 */
public record ChannelHealthEvent(
    UUID channelId, String name, String type, boolean failing, Instant at) {

  /** Validates. */
  public ChannelHealthEvent {
    Objects.requireNonNull(channelId, "channelId");
    Objects.requireNonNull(at, "at");
  }
}
