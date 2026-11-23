/* SPDX-License-Identifier: Apache-2.0 */

/**
 * PostgreSQL access to {@code instances} (11 §5, 12 §4.2-§4.3, ADR-0010 C-128, C-146): instance
 * identity and metadata and the {@link io.argos.server.session.SessionStore} over {@code apps},
 * {@code instances} and {@code agent_tokens.last_used_at} (WO-21); the instance state columns and
 * the {@code events} rows of transitions, {@code AgentBye} and the server's start and stop ({@link
 * io.argos.server.storage.postgres.instance.InstanceStateRepository}, WO-22) and the daily archive
 * and purge of silent instances ({@link
 * io.argos.server.storage.postgres.instance.InstanceRetentionRepository}, 12 §4.3, WO-22).
 *
 * <p>Owner: WO-21 (lane collector), then WO-22.
 */
package io.argos.server.storage.postgres.instance;
