/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The JSON form of an {@link AuditRecord} (16 §11.4: one JSON Lines line, the syslog message body):
 * one object with the {@code audit_logs} column names ({@code ts}, {@code actor_type}, ..., {@code
 * before} and {@code after} as embedded objects, {@code subject_user_id}, {@code subject_tx_id}),
 * nulls omitted, on one line ({@code \n} and {@code \r} never appear unescaped).
 */
public final class AuditRecordJson {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private AuditRecordJson() {}

  /** The record as one JSON object on one line, without a line end. */
  public static String line(AuditRecord r) {
    return JSON.writeValueAsString(node(r));
  }

  /** The record as a JSON object. */
  public static ObjectNode node(AuditRecord r) {
    ObjectNode n = JSON.createObjectNode();
    n.put("ts", r.ts().toString());
    n.put("actor_type", r.actorType());
    put(n, "actor_id", r.actorId());
    put(n, "actor_name", r.actorName());
    put(n, "ip", r.ip());
    put(n, "user_agent", r.userAgent());
    put(n, "request_id", r.requestId());
    n.put("action", r.action());
    put(n, "target_type", r.targetType());
    put(n, "target_id", r.targetId());
    put(n, "target_name", r.targetName());
    n.put("result", r.result());
    put(n, "reason", r.reason());
    putJson(n, "before", r.beforeJson());
    putJson(n, "after", r.afterJson());
    put(n, "subject_user_id", r.subjectUserId());
    if (r.subjectTxId() != null) {
      n.put("subject_tx_id", Long.toUnsignedString(r.subjectTxId()));
    }
    return n;
  }

  private static void put(ObjectNode n, String name, String value) {
    if (value != null) {
      n.put(name, value);
    }
  }

  private static void putJson(ObjectNode n, String name, String json) {
    if (json == null) {
      return;
    }
    try {
      JsonNode parsed = JSON.readTree(json);
      n.set(name, parsed);
    } catch (JacksonException e) {
      n.put(name, json);
    }
  }
}
