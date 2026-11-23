/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.alerts.channels;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.alert.channel.ChannelService;
import io.argos.server.api.openapi.ArgosUntrusted;
import io.argos.server.auth.Sensitive;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.jspecify.annotations.Nullable;

/**
 * Request and response bodies of {@code /alerts/channels*} (14 §4.13). Field names are the API's
 * snake_case, times ISO-8601 UTC in milliseconds (14 §2.4). A channel's target is always masked in
 * a response (SEC-52); a signing secret appears once, in the response that created it. Request and
 * response records are separate (a component schema is either a request or a response).
 */
final class ChannelDtos {

  private ChannelDtos() {}

  static Instant millis(Instant instant) {
    return instant == null ? null : instant.truncatedTo(ChronoUnit.MILLIS);
  }

  /** A channel (14 §4.13 {@code GET /alerts/channels} item). */
  record ChannelView(
      @JsonProperty("channel_id") String channelId,
      @Schema(allowableValues = {"email", "slack", "webhook"}) String type,
      @ArgosUntrusted String name,
      @Schema(description = "The URL (scheme, host and port, rest ****) or masked recipients")
          @JsonProperty("target_masked")
          String targetMasked,
      boolean enabled,
      @Schema(allowableValues = {"ok", "failing"}) String status,
      @JsonProperty("last_success_at") @Nullable Instant lastSuccessAt,
      @JsonProperty("last_failure_at") @Nullable Instant lastFailureAt,
      @JsonProperty("consecutive_failures") int consecutiveFailures,
      @JsonProperty("signing_enabled") boolean signingEnabled,
      @JsonProperty("allow_http") boolean allowHttp,
      @JsonProperty("created_at") Instant createdAt,
      @JsonProperty("updated_at") Instant updatedAt) {

    static ChannelView of(ChannelService.Summary s) {
      return new ChannelView(
          s.id().toString(),
          s.type().wire(),
          s.name(),
          s.targetMasked(),
          s.enabled(),
          s.status(),
          millis(s.lastSuccessAt()),
          millis(s.lastFailureAt()),
          s.consecutiveFailures(),
          s.signingEnabled(),
          s.allowHttp(),
          millis(s.createdAt()),
          millis(s.updatedAt()));
    }
  }

  /** {@code POST /alerts/channels}. */
  record CreateChannelRequest(
      @Schema(
              requiredMode = RequiredMode.REQUIRED,
              allowableValues = {"email", "slack", "webhook"})
          String type,
      @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 128) String name,
      @Schema(
              requiredMode = RequiredMode.REQUIRED,
              description =
                  "The webhook or Slack URL (https; validated, SEC-51), or the e-mail recipients"
                      + " separated by commas")
          @Sensitive
          String target,
      @Schema(
              description =
                  "Webhook only: a signing secret of 32 to 256 printable characters; generated"
                      + " (and returned once) when absent")
          @Sensitive
          String secret,
      @Schema(
              description =
                  "Allow a plain http URL; needs argos.alert.webhook.allow-http=true on the server")
          @JsonProperty("allow_http")
          Boolean allowHttp) {

    @Override
    public String toString() {
      return "CreateChannelRequest[type=" + type + ", name=" + name + "]";
    }
  }

  /** {@code PUT /alerts/channels/{id}}: only the re-entered fields. */
  record UpdateChannelRequest(
      @Schema(maxLength = 128) String name,
      @Schema(description = "A new URL or recipient list (re-entered in full)") @Sensitive
          String target,
      @JsonProperty("allow_http") Boolean allowHttp,
      Boolean enabled) {

    @Override
    public String toString() {
      return "UpdateChannelRequest[name=" + name + ", enabled=" + enabled + "]";
    }
  }

  /** The created channel and its generated signing secret, shown once. */
  record CreatedChannelView(
      ChannelView channel,
      @Schema(
              description =
                  "Webhook: the generated signing secret, shown once (null when the request gave"
                      + " one, and for other types)")
          @JsonProperty("signing_key")
          @Sensitive
          @Nullable String signingKey) {

    @Override
    public String toString() {
      return "CreatedChannelView[" + channel.channelId() + "]";
    }
  }

  /** {@code POST /alerts/channels/{id}/test}: never the response body of the destination. */
  record TestResultView(
      boolean delivered,
      @Schema(description = "HTTP status, or the SMTP reply code; null without an answer")
          @JsonProperty("status_code")
          @Nullable Integer statusCode,
      @JsonProperty("duration_ms") long durationMs,
      @Schema(description = "Error code of a failed send (e.g. timeout, tls_failed, redirect)")
          @Nullable String error) {}

  /** {@code POST /alerts/channels/{id}/signing-key/rotate}: the new secret, shown once. */
  record RotatedKeyView(
      @JsonProperty("signing_key") @Sensitive String signingKey,
      @Schema(description = "The previous key signs too (a second v1= value) until this time")
          @JsonProperty("previous_valid_until")
          Instant previousValidUntil) {

    @Override
    public String toString() {
      return "RotatedKeyView[previousValidUntil=" + previousValidUntil + "]";
    }
  }
}
