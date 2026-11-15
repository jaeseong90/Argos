/* SPDX-License-Identifier: Apache-2.0 */

/**
 * PostgreSQL access of the agent token lookup (WO-21; 13 §12 {@code agent_tokens}, 12 §7.1, 16
 * §2.2, §2.4): the read by SHA-256 hash behind {@code io.argos.server.collector.auth.TokenCache}
 * and the throttled {@code last_used_at} write. Token issuance, rotation and revocation are
 * WO-26's.
 *
 * <p>Owner: WO-21 (lane collector).
 */
package io.argos.server.storage.postgres.token;
