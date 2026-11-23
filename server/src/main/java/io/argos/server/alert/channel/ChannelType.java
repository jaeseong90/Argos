/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import java.util.Locale;
import java.util.Optional;

/** The channel types of 14 §4.13 ({@code notification_channels.type}). */
public enum ChannelType {
  /** Mail through the configured SMTP server ({@code argos.alert.smtp.*}). */
  EMAIL,
  /** A Slack incoming webhook. */
  SLACK,
  /** A generic HTTPS webhook with the {@code X-Argos-Signature} header. */
  WEBHOOK;

  /** The stored and API value ({@code email}, {@code slack}, {@code webhook}). */
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** The type of a stored or API value. */
  public static Optional<ChannelType> of(String value) {
    if (value == null) {
      return Optional.empty();
    }
    for (ChannelType t : values()) {
      if (t.wire().equals(value.strip().toLowerCase(Locale.ROOT))) {
        return Optional.of(t);
      }
    }
    return Optional.empty();
  }

  /** Whether the target is a URL (webhook, Slack). */
  public boolean urlTarget() {
    return this != EMAIL;
  }
}
