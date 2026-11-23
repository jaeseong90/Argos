/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.token;

import io.argos.server.auth.Sensitive;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A token just issued (16 §2.1, §2.3): the only object that ever holds the token text, returned
 * once to the issuing caller (the REST response {@code POST /admin/apps/{appId}/tokens}, the
 * rotation, the approval queue, the setup wizard, {@code argos-admin token create}). Its {@code
 * toString()} shows the prefix only.
 *
 * @param tokenId {@code agent_tokens.id} (UUIDv4)
 * @param appId the token's home app
 * @param token {@code argos_} and 43 base64url characters; never logged or stored
 * @param prefix the first 14 characters ({@code argos_} and 8), the only part shown again
 * @param expiresAt expiry, or null (none)
 * @param rotatedTokenId for a rotation: the rotated token, now {@code rotating}; otherwise null
 * @param graceUntil for a rotation: when the rotated token stops authenticating; otherwise null
 */
public record IssuedToken(
    UUID tokenId,
    int appId,
    @Sensitive String token,
    String prefix,
    @Nullable Instant expiresAt,
    @Nullable UUID rotatedTokenId,
    @Nullable Instant graceUntil) {

  /** Validates the required components. */
  public IssuedToken {
    Objects.requireNonNull(tokenId, "tokenId");
    Objects.requireNonNull(token, "token");
    Objects.requireNonNull(prefix, "prefix");
  }

  /** The prefix only, never the token. */
  @Override
  public String toString() {
    return "IssuedToken[tokenId="
        + tokenId
        + ", appId="
        + appId
        + ", prefix="
        + prefix
        + ", expiresAt="
        + expiresAt
        + ", rotatedTokenId="
        + rotatedTokenId
        + "]";
  }
}
