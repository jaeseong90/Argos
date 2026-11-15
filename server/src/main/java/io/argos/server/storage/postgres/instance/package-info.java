/* SPDX-License-Identifier: Apache-2.0 */

/**
 * PostgreSQL access to {@code instances} for the collector's minimal session (WO-21; 11 §5, 12
 * §4.3, ADR-0010 C-128, C-146): instance identity and metadata, and the {@link
 * io.argos.server.session.SessionStore} over {@code apps}, {@code instances} and {@code
 * agent_tokens.last_used_at}. The instance state columns are WO-22's.
 *
 * <p>Owner: WO-21 (lane collector).
 */
package io.argos.server.storage.postgres.instance;
