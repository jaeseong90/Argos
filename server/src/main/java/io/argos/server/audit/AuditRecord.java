/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

/**
 * One row of {@code audit_logs} as a sink writes it (13 §12 columns; values already cut to the
 * column sizes and masked by {@link DefaultAuditService}). The timestamp is the database's {@code
 * now()} at insert.
 *
 * @param actorType {@code user}, {@code agent} or {@code system} (8)
 * @param actorId actor id (64), or null
 * @param actorName actor name (128), or null
 * @param ip client address literal, or null
 * @param userAgent client user agent (256), or null
 * @param requestId request id (64), or null outside a request
 * @param action the {@link AuditEvent} name (64)
 * @param targetType target type (32), or null
 * @param targetId target id (64), or null
 * @param targetName target name (256), or null
 * @param result {@code success}, {@code failure} or {@code rejected} (8)
 * @param reason reason code (512), or null
 * @param beforeJson masked JSON object, or null
 * @param afterJson masked JSON object, or null
 */
public record AuditRecord(
    String actorType,
    String actorId,
    String actorName,
    String ip,
    String userAgent,
    String requestId,
    String action,
    String targetType,
    String targetId,
    String targetName,
    String result,
    String reason,
    String beforeJson,
    String afterJson) {}
