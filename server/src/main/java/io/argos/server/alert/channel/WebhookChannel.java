/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.alert.channel;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/**
 * The generic webhook (12 §5.5, 16 §8.4, SEC-51, SEC-53): the JSON of {@link
 * ChannelMessage#webhookJson()} ({@code schema_version: 1}), signed with {@code X-Argos-Signature}
 * by every valid signing key, posted with {@link PinnedHttpClient} to the address the destination
 * validation pinned.
 */
public final class WebhookChannel implements NotificationChannel {

  private final DestinationValidator validator;
  private final PinnedHttpClient http;
  private final Clock clock;

  public WebhookChannel(DestinationValidator validator, PinnedHttpClient http, Clock clock) {
    this.validator = Objects.requireNonNull(validator, "validator");
    this.http = Objects.requireNonNull(http, "http");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public ChannelType type() {
    return ChannelType.WEBHOOK;
  }

  @Override
  public Outcome send(OpenChannel channel, ChannelMessage message) throws DeliveryException {
    if (channel.signingSecrets().isEmpty()) {
      throw new DeliveryException("no_signing_key");
    }
    Destination destination =
        validator.validate(channel.config().url(), channel.config().allowHttp());
    byte[] body = message.webhookJson();
    String signature = WebhookSignature.header(channel.signingSecrets(), clock.instant(), body);
    PinnedHttpClient.HttpOutcome outcome =
        http.post(
            destination, Map.of(WebhookSignature.HEADER, signature), "application/json", body);
    return new Outcome(outcome.status(), outcome.durationMs());
  }
}
