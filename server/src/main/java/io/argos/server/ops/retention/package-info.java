/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Retention (13 §8, 17 §9; ADR-0010 C-99, C-112, C-116): {@link
 * io.argos.server.ops.retention.RetentionService} applies the effective ClickHouse retention
 * ({@code MODIFY TTL} without rewriting parts, drops of partitions outside a shortened retention),
 * at the start ({@link io.argos.server.ops.retention.RetentionApplyPhase}), through the API and
 * through {@code argos-admin retention apply}; {@link
 * io.argos.server.ops.retention.AuditPartitionJob} keeps the monthly partitions of {@code
 * audit_logs} and {@code alert_events}.
 *
 * <p>Owner: WO-28.
 */
package io.argos.server.ops.retention;
