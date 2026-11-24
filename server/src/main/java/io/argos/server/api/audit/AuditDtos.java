/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.audit;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.argos.server.api.openapi.ArgosUntrusted;
import io.argos.server.storage.postgres.audit.AuditQueryRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Response types of {@code GET /api/v1/admin/audit} (14 §4.14). */
final class AuditDtos {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private AuditDtos() {}

  /**
   * Who did it.
   *
   * @param type user, agent or system
   * @param id user id, token prefix or {@code system}
   * @param name user name (for a failed login: the name that was tried), token prefix or system
   */
  @Schema(name = "AuditActor")
  record AuditActor(
      @Schema(allowableValues = {"user", "agent", "system"}) String type,
      @Nullable String id,
      @ArgosUntrusted @Nullable String name) {}

  /**
   * What it was done to.
   *
   * @param type e.g. user, session, app, channel, rule, token, schema, cli
   * @param id identifier
   * @param name display name
   */
  @Schema(name = "AuditTarget")
  record AuditTarget(
      @Nullable String type, @Nullable String id, @ArgosUntrusted @Nullable String name) {}

  /**
   * Whose personal data a view showed (SEC-81).
   *
   * @param userId the user id, or null
   * @param txId the transaction id (unsigned 64-bit decimal), or null
   */
  @Schema(name = "AuditPiiSubject")
  record AuditPiiSubject(
      @JsonProperty("user_id") @ArgosUntrusted @Nullable String userId,
      @JsonProperty("tx_id") @Nullable String txId) {}

  /**
   * One audit record.
   *
   * @param id the row id
   * @param ts when it was recorded
   * @param actor who did it
   * @param ip the client address, or null
   * @param userAgent the client's user agent, or null
   * @param action the 16 §11.1 event name
   * @param target what it was done to
   * @param result success, failure or rejected
   * @param reason the reason code, or null
   * @param before the changed fields before (secrets masked), or null
   * @param after the changed fields after or the event's details (secrets masked), or null
   * @param requestId the request id, or null outside a request
   * @param piiSubject whose personal data was shown, or null
   */
  @Schema(name = "AuditRecord")
  record Entry(
      String id,
      Instant ts,
      AuditActor actor,
      @Nullable String ip,
      @JsonProperty("user_agent") @ArgosUntrusted @Nullable String userAgent,
      String action,
      @ArgosUntrusted AuditTarget target,
      @Schema(allowableValues = {"success", "failure", "rejected"}) String result,
      @ArgosUntrusted @Nullable String reason,
      @ArgosUntrusted @Nullable Map<String, Object> before,
      @ArgosUntrusted @Nullable Map<String, Object> after,
      @JsonProperty("request_id") @ArgosUntrusted @Nullable String requestId,
      @JsonProperty("pii_subject") @Nullable AuditPiiSubject piiSubject) {

    static Entry of(AuditQueryRepository.Row r) {
      AuditPiiSubject subject =
          r.subjectUserId() == null && r.subjectTxId() == null
              ? null
              : new AuditPiiSubject(
                  r.subjectUserId(),
                  r.subjectTxId() == null ? null : Long.toUnsignedString(r.subjectTxId()));
      return new Entry(
          Long.toString(r.id()),
          r.ts().truncatedTo(ChronoUnit.MILLIS),
          new AuditActor(r.actorType(), r.actorId(), r.actorName()),
          r.ip(),
          r.userAgent(),
          r.action(),
          new AuditTarget(r.targetType(), r.targetId(), r.targetName()),
          r.result(),
          r.reason(),
          object(r.before()),
          object(r.after()),
          r.requestId(),
          subject);
    }
  }

  /** A stored JSON object as a map, or null (a stored value that is not an object is wrapped). */
  @SuppressWarnings("unchecked")
  static @Nullable Map<String, Object> object(@Nullable String json) {
    if (json == null) {
      return null;
    }
    try {
      JsonNode node = JSON.readTree(json);
      if (node == null || node.isNull()) {
        return null;
      }
      if (!node.isObject()) {
        return Map.of("value", JSON.treeToValue(node, Object.class));
      }
      return JSON.treeToValue(node, Map.class);
    } catch (JacksonException e) {
      return Map.of("value", json);
    }
  }
}
