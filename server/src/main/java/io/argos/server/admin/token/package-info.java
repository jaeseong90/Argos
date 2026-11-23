/* SPDX-License-Identifier: Apache-2.0 */

/**
 * The agent token service (WO-26; 12 §7.1, 16 §2, SEC-01 to SEC-06): issuance, list, rotation,
 * revocation, the expiry job and the token expiry gauges. The token text exists only in the {@link
 * io.argos.server.admin.token.IssuedToken} returned once to the caller; PostgreSQL keeps its
 * SHA-256 and 14-character prefix ({@code io.argos.server.storage.postgres.token.AgentTokenStore}).
 *
 * <p>Owner: WO-26 (lane A).
 */
package io.argos.server.admin.token;
