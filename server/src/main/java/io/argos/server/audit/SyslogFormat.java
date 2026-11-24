/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

import java.nio.charset.StandardCharsets;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The RFC 5424 form of an audit record (16 §11.4, SEC-82): {@code <PRI>1 TIMESTAMP HOSTNAME argos
 * PROCID MSGID [argos@PEN event="…" actor_type="…" …] BOM{json}}.
 *
 * <ul>
 *   <li>PRI: facility 13 ("log audit"); severity 5 (notice) for {@code success}, 4 (warning) for
 *       {@code failure} and {@code rejected}.
 *   <li>TIMESTAMP: the record's {@code ts} (RFC 3339, UTC, at most microseconds).
 *   <li>HOSTNAME: the server's host name (printable US-ASCII, at most 255), or {@code -}.
 *   <li>APP-NAME {@code argos}; PROCID the process id; MSGID the event name (at most 32).
 *   <li>STRUCTURED-DATA: one element {@code argos@}{@value #PEN} with the record's columns except
 *       {@code before}, {@code after} and the subject (nulls omitted); in a value {@code "}, {@code
 *       \} and {@code ]} are escaped with {@code \} and control characters become U+FFFD.
 *   <li>MSG: UTF-8 with a BOM, the record's JSON ({@link AuditRecordJson}). A message above {@code
 *       maxBytes} drops {@code before} and {@code after} from the JSON and marks it {@code
 *       "truncated":true}; one still above it ends with the structured data.
 * </ul>
 */
public final class SyslogFormat {

  /**
   * The private enterprise number of the SD-ID. 32473 is the number IANA reserves for documentation
   * (RFC 5612); Argos has no registered number yet. It is a constant so that a registered number
   * replaces it in one place (reported to the PM with WO-28).
   */
  public static final int PEN = 32473;

  /** The SD-ID of the structured data element. */
  public static final String SD_ID = "argos@" + PEN;

  /** Facility 13, "log audit" (RFC 5424 table 1). */
  static final int FACILITY = 13;

  private static final String BOM = "﻿";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private SyslogFormat() {}

  /** The message without transport framing, at most {@code maxBytes} UTF-8 bytes when possible. */
  public static String message(AuditRecord r, String hostname, String procId, int maxBytes) {
    String head = header(r, hostname, procId) + " " + structuredData(r);
    String full = head + " " + BOM + AuditRecordJson.line(r);
    if (utf8Length(full) <= maxBytes) {
      return full;
    }
    ObjectNode body = AuditRecordJson.node(r);
    body.remove("before");
    body.remove("after");
    body.put("truncated", true);
    String shorter = head + " " + BOM + JSON.writeValueAsString(body);
    if (utf8Length(shorter) <= maxBytes) {
      return shorter;
    }
    return head;
  }

  /** {@code <PRI>1 TIMESTAMP HOSTNAME APP-NAME PROCID MSGID}. */
  static String header(AuditRecord r, String hostname, String procId) {
    int severity = "success".equals(r.result()) ? 5 : 4;
    return "<"
        + (FACILITY * 8 + severity)
        + ">1 "
        + r.ts()
        + " "
        + headerField(hostname, 255)
        + " argos "
        + headerField(procId, 128)
        + " "
        + headerField(r.action(), 32);
  }

  /** The structured data element. */
  static String structuredData(AuditRecord r) {
    StringBuilder sd = new StringBuilder(256).append('[').append(SD_ID);
    param(sd, "event", r.action());
    param(sd, "actor_type", r.actorType());
    param(sd, "actor_id", r.actorId());
    param(sd, "actor_name", r.actorName());
    param(sd, "ip", r.ip());
    param(sd, "target_type", r.targetType());
    param(sd, "target_id", r.targetId());
    param(sd, "target_name", r.targetName());
    param(sd, "result", r.result());
    param(sd, "reason", r.reason());
    param(sd, "request_id", r.requestId());
    return sd.append(']').toString();
  }

  private static void param(StringBuilder sd, String name, String value) {
    if (value == null) {
      return;
    }
    sd.append(' ').append(name).append("=\"").append(escape(value)).append('"');
  }

  /** RFC 5424 §6.3.3 PARAM-VALUE escaping, control characters replaced. */
  static String escape(String value) {
    StringBuilder out = new StringBuilder(value.length() + 8);
    value
        .codePoints()
        .forEach(
            cp -> {
              if (cp == '"' || cp == '\\' || cp == ']') {
                out.append('\\').appendCodePoint(cp);
              } else if (Character.isISOControl(cp)) {
                out.append('�');
              } else {
                out.appendCodePoint(cp);
              }
            });
    return out.toString();
  }

  /** A header field: printable US-ASCII without spaces, at most {@code max}, or {@code -}. */
  static String headerField(String value, int max) {
    if (value == null || value.isEmpty()) {
      return "-";
    }
    StringBuilder out = new StringBuilder(Math.min(value.length(), max));
    for (int i = 0; i < value.length() && out.length() < max; i++) {
      char c = value.charAt(i);
      out.append(c >= 33 && c <= 126 ? c : '_');
    }
    return out.toString();
  }

  static int utf8Length(String s) {
    return s.getBytes(StandardCharsets.UTF_8).length;
  }
}
