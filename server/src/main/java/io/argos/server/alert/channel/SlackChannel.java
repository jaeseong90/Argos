/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import java.util.Map;
import java.util.Objects;

/**
 * A Slack incoming webhook (12 §5.5): {@code {"text": ...}} ({@link ChannelMessage#slackJson()}),
 * with the same destination validation and pinned connection as the generic webhook (SEC-51).
 */
public final class SlackChannel implements NotificationChannel {

  private final DestinationValidator validator;
  private final PinnedHttpClient http;

  public SlackChannel(DestinationValidator validator, PinnedHttpClient http) {
    this.validator = Objects.requireNonNull(validator, "validator");
    this.http = Objects.requireNonNull(http, "http");
  }

  @Override
  public ChannelType type() {
    return ChannelType.SLACK;
  }

  @Override
  public Outcome send(OpenChannel channel, ChannelMessage message) throws DeliveryException {
    Destination destination =
        validator.validate(channel.config().url(), channel.config().allowHttp());
    PinnedHttpClient.HttpOutcome outcome =
        http.post(destination, Map.of(), "application/json", message.slackJson());
    return new Outcome(outcome.status(), outcome.durationMs());
  }
}
