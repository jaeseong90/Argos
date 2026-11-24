/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.admin.tokens;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.admin.token.IssuedToken;
import io.argos.server.admin.token.TokenView;
import io.argos.server.api.openapi.ArgosUntrusted;
import io.argos.server.auth.Sensitive;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.jspecify.annotations.Nullable;

/**
 * Request and response bodies of the agent token API (14 §4.14). Field names are the API's
 * snake_case; times are ISO-8601 UTC in milliseconds (14 §2.4). The token itself appears in exactly
 * two responses, once each (issuance and rotation), and in no {@code toString()} ({@link
 * Sensitive}, T-SEC-48b).
 */
final class TokenDtos {

  private TokenDtos() {}

  static @Nullable Instant millis(@Nullable Instant instant) {
    return instant == null ? null : instant.truncatedTo(ChronoUnit.MILLIS);
  }

  /**
   * One token of {@code GET /admin/apps/{appId}/tokens}: no token, no hash (SEC-02). {@code state}
   * is 14's {@code active|rotating|revoked} and {@code expired} (16 §2.2).
   */
  record AgentTokenView(
      @Schema(requiredMode = RequiredMode.REQUIRED, format = "uuid") @JsonProperty("token_id")
          String tokenId,
      @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("app_id") int appId,
      @Schema(
              requiredMode = RequiredMode.REQUIRED,
              description = "argos_ and the next 8 characters of the token")
          String prefix,
      @Schema(
              requiredMode = RequiredMode.REQUIRED,
              allowableValues = {"active", "rotating", "revoked", "expired"})
          String state,
      @Schema(description = "Name of the issuing user; null when the server or a command issued it")
          @ArgosUntrusted
          @JsonProperty("created_by")
          @Nullable String createdBy,
      @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("created_at") Instant createdAt,
      @Schema(description = "null: the token does not expire") @JsonProperty("expires_at")
          @Nullable Instant expiresAt,
      @Schema(description = "End of the rotation grace of a rotating token")
          @JsonProperty("grace_until")
          @Nullable Instant graceUntil,
      @JsonProperty("revoked_at") @Nullable Instant revokedAt,
      @JsonProperty("last_used_at") @Nullable Instant lastUsedAt,
      @Schema(
              requiredMode = RequiredMode.REQUIRED,
              description = "Live agent sessions opened with this token")
          @JsonProperty("instances_using")
          int instancesUsing) {

    static AgentTokenView of(TokenView v) {
      return new AgentTokenView(
          v.tokenId().toString(),
          v.appId(),
          v.prefix(),
          v.state().apiName(),
          v.createdBy(),
          millis(v.createdAt()),
          millis(v.expiresAt()),
          millis(v.graceUntil()),
          millis(v.revokedAt()),
          millis(v.lastUsedAt()),
          v.instancesUsing());
    }
  }

  /** {@code POST /admin/apps/{appId}/tokens}: the expiry, 1 to 3650 days, default 365. */
  record IssueTokenRequest(
      @Schema(
              minimum = "1",
              maximum = "3650",
              description =
                  "Days until the token expires. Optional; the server uses 365 when omitted.")
          @JsonProperty("expires_in_days")
          @Nullable Integer expiresInDays) {}

  /**
   * {@code POST /admin/tokens/{tokenId}/rotate}: the grace of the old token, 1 to 30, default 7.
   */
  record RotateTokenRequest(
      @Schema(
              minimum = "1",
              maximum = "30",
              description =
                  "Days the old token stays valid. Optional; the server uses 7 when omitted.")
          @JsonProperty("grace_days")
          @Nullable Integer graceDays) {}

  /** {@code POST /admin/tokens/{tokenId}/revoke}. */
  record RevokeTokenRequest(
      @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = 512) @Nullable String reason) {}

  /**
   * The issued token, shown once (16 §2.1): issuance and rotation. For a rotation, {@code
   * rotated_token_id} and {@code grace_until} name the old token, now {@code rotating}.
   */
  record IssuedTokenResponse(
      @Schema(requiredMode = RequiredMode.REQUIRED, format = "uuid") @JsonProperty("token_id")
          String tokenId,
      @Schema(requiredMode = RequiredMode.REQUIRED, description = "Shown once, never again")
          @Sensitive
          String token,
      @Schema(requiredMode = RequiredMode.REQUIRED) String prefix,
      @Schema(requiredMode = RequiredMode.REQUIRED) @JsonProperty("app_id") int appId,
      @Schema(description = "null: the token does not expire") @JsonProperty("expires_at")
          @Nullable Instant expiresAt,
      @Schema(format = "uuid", description = "Rotation only: the rotated token")
          @JsonProperty("rotated_token_id")
          @Nullable String rotatedTokenId,
      @Schema(description = "Rotation only: when the rotated token stops authenticating")
          @JsonProperty("grace_until")
          @Nullable Instant graceUntil,
      @Schema(
              description =
                  "The collector address (host:port) agents should use, from the server setting"
                      + " argos.collector.public-address; null when it is not set (the client then"
                      + " uses the host it reached the server on, port 9100)")
          @JsonProperty("collector_address")
          @Nullable String collectorAddress) {

    static IssuedTokenResponse of(IssuedToken t, @Nullable String collectorAddress) {
      return new IssuedTokenResponse(
          t.tokenId().toString(),
          t.token(),
          t.prefix(),
          t.appId(),
          millis(t.expiresAt()),
          t.rotatedTokenId() == null ? null : t.rotatedTokenId().toString(),
          millis(t.graceUntil()),
          collectorAddress);
    }

    /** The prefix only, never the token. */
    @Override
    public String toString() {
      return "IssuedTokenResponse[tokenId=" + tokenId + ", prefix=" + prefix + "]";
    }
  }
}
