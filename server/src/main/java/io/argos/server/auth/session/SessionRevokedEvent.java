/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.session;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A session was revoked (16 §7.4): published in-process, once per revoked session, right after the
 * revocation committed and synchronously on the revoking thread. {@link SessionStateCache} drops
 * the session here, so the next request is refused; the WebSocket registry (WO-22) subscribes with
 * {@code @EventListener} and closes the session's connections with code 4401 within one second
 * (SEC-44). Listeners must not block.
 *
 * @param sid the session id: the family's current row, the {@code sid} of its newest access token
 * @param userId the owner
 * @param reason why
 * @param sessionIds every row of the family this revocation covers, {@code sid} included: an older
 *     access token (or a WebSocket authenticated with one) may still carry a rotated row's id
 */
public record SessionRevokedEvent(
    UUID sid, UUID userId, RevocationReason reason, Set<UUID> sessionIds) {

  public SessionRevokedEvent {
    Objects.requireNonNull(sid, "sid");
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(reason, "reason");
    sessionIds = sessionIds == null ? Set.of(sid) : Set.copyOf(sessionIds);
  }
}
