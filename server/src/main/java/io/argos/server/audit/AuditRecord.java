/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

import java.time.Instant;
import java.util.Objects;

/**
 * One row of {@code audit_logs} as a sink writes it (13 §12 columns; values already cut to the
 * column sizes and masked by {@link DefaultAuditService}). Every sink writes the same record: the
 * PostgreSQL row, the JSON Lines line and the syslog message carry the same {@code ts}.
 *
 * @param ts when the service recorded the event (the server's wall clock, UTC); the PostgreSQL row
 *     stores this value, not the database's {@code now()}
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
 * @param subjectUserId the user id whose personal data was viewed (SEC-81, 128), or null
 * @param subjectTxId the transaction whose personal data was viewed (SEC-81), or null
 */
public record AuditRecord(
    Instant ts,
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
    String afterJson,
    String subjectUserId,
    Long subjectTxId) {

  public AuditRecord {
    Objects.requireNonNull(ts, "ts");
    Objects.requireNonNull(actorType, "actorType");
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(result, "result");
  }
}
