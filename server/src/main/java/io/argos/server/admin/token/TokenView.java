/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.admin.token;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One agent token as the administration sees it (14 §4.14 {@code GET /admin/apps/{appId}/tokens}):
 * no token and no hash (SEC-02).
 *
 * @param tokenId {@code agent_tokens.id}
 * @param appId the home app
 * @param prefix {@code argos_} and the next 8 characters of the token
 * @param state the state now
 * @param createdBy the name of the issuing user, or null (issued by the server or a command line,
 *     or the user was deleted)
 * @param createdAt issue time
 * @param expiresAt expiry, or null (none)
 * @param graceUntil end of the rotation grace ({@code rotating}), or null
 * @param revokedAt revocation time, or null
 * @param lastUsedAt the last session start (at most one write per 5 minutes), or null
 * @param instancesUsing live agent sessions opened with this token now
 */
public record TokenView(
    UUID tokenId,
    int appId,
    String prefix,
    TokenState state,
    @Nullable String createdBy,
    Instant createdAt,
    @Nullable Instant expiresAt,
    @Nullable Instant graceUntil,
    @Nullable Instant revokedAt,
    @Nullable Instant lastUsedAt,
    int instancesUsing) {}
