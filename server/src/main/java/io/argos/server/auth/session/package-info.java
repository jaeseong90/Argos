/* SPDX-License-Identifier: Apache-2.0 */

/**
 * UI sessions (WO-24; 12 §7.2, 16 §7.1-§7.7): cookies, login, refresh rotation, the session record
 * check of every request, CSRF, the password-change gate and the invalidation hooks.
 *
 * <p>Hooks for later work orders:
 *
 * <ul>
 *   <li>WO-22 (WebSocket): subscribe to {@link io.argos.server.auth.session.SessionRevokedEvent};
 *       check the handshake with {@link io.argos.server.auth.session.SessionAuthenticator}, {@link
 *       io.argos.server.auth.session.SessionCookies#ACCESS_COOKIE} and {@link
 *       io.argos.server.auth.session.PublicOrigin}.
 *   <li>WO-23, WO-26 (REST): nothing to do but {@code @RequiresRole}; an endpoint that takes no
 *       session cookie (agent token on {@code POST /events}) joins {@link
 *       io.argos.server.auth.session.CsrfFilter}'s list.
 *   <li>WO-30 (UI): {@code X-Argos-CSRF} from the {@code argos_csrf} cookie; refreshes serialised
 *       across tabs with the Web Locks API, and on a 401 the original request retried once before a
 *       refresh: the server has no grace period for a second use of a refresh token ({@link
 *       io.argos.server.auth.session.RefreshRotation}).
 * </ul>
 *
 * <p>Owner: WO-24.
 */
package io.argos.server.auth.session;
