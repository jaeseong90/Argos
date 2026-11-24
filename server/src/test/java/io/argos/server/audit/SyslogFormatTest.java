/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The RFC 5424 form of audit records (16 §11.4, T-SEC-82 syslog format). */
@Tag("req:SEC-82")
class SyslogFormatTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** RFC 5424 §6 header: PRI VERSION SP TIMESTAMP SP HOSTNAME SP APP-NAME SP PROCID SP MSGID. */
  static final Pattern HEADER =
      Pattern.compile(
          "<(\\d{1,3})>1 (\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,6})?Z)"
              + " ([\\x21-\\x7e]{1,255}) argos ([\\x21-\\x7e]{1,128}) ([\\x21-\\x7e]{1,32})"
              + " \\[argos@32473( [a-z_]{1,32}=\"(?:[^\"\\\\\\]]|\\\\[\"\\\\\\]])*\")+\\]"
              + "(?: ﻿(\\{.*\\}))?");

  @Test
  @DisplayName(
      "T-SEC-82 a record is one RFC 5424 message: facility 13, notice for success, the record's"
          + " ts, MSGID the event, SD-ID argos@PEN, BOM and the JSON body")
  void format() {
    AuditRecord r = AuditSinksTest.record("ROLE_CHANGED", "success");
    String m = SyslogFormat.message(r, "argos-host", "4242", 8192);
    var matcher = HEADER.matcher(m);
    assertThat(matcher.matches()).as(m).isTrue();
    assertThat(matcher.group(1)).isEqualTo(Integer.toString(13 * 8 + 5));
    assertThat(matcher.group(2)).isEqualTo("2026-11-24T01:02:03.456789Z");
    assertThat(matcher.group(3)).isEqualTo("argos-host");
    assertThat(matcher.group(4)).isEqualTo("4242");
    assertThat(matcher.group(5)).isEqualTo("ROLE_CHANGED");
    JsonNode body = JSON.readTree(matcher.group(7));
    assertThat(body.path("action").asString()).isEqualTo("ROLE_CHANGED");
    assertThat(body.path("before").path("role").asString()).isEqualTo("VIEWER");
    assertThat(m)
        .contains(" event=\"ROLE_CHANGED\"")
        .contains(" actor_type=\"user\"")
        .contains(" result=\"success\"")
        .contains(" request_id=\"01JREQ\"")
        .doesNotContain("\n");
  }

  @Test
  @DisplayName(
      "T-SEC-82 SD-PARAM values escape \", \\ and ] and replace control characters; failure is"
          + " warning")
  void escaping() {
    AuditRecord base = AuditSinksTest.record("LOGIN_FAILED", "failure");
    AuditRecord r =
        new AuditRecord(
            base.ts(),
            "user",
            null,
            "a\"b\\c]d\ne",
            "10.0.0.1",
            null,
            null,
            "LOGIN_FAILED",
            null,
            null,
            null,
            "failure",
            "bad_credentials",
            null,
            null,
            null,
            null);
    String m = SyslogFormat.message(r, "h", "1", 8192);
    assertThat(m).startsWith("<" + (13 * 8 + 4) + ">1 ");
    assertThat(m).contains(" actor_name=\"a\\\"b\\\\c\\]d�e\"");
    assertThat(m).doesNotContain(" actor_id=").doesNotContain("\n");
    assertThat(HEADER.matcher(m).matches()).as(m).isTrue();
    assertThat(SyslogFormat.escape("x]y")).isEqualTo("x\\]y");
    assertThat(SyslogFormat.headerField("a bé", 32)).isEqualTo("a_b_");
    assertThat(SyslogFormat.headerField(null, 32)).isEqualTo("-");
  }

  @Test
  @DisplayName(
      "T-SEC-82 a message above the limit drops before and after (truncated:true), then the body")
  void truncation() {
    AuditRecord base = AuditSinksTest.record("CONFIG_PUSH", "success");
    String big = "{\"k\":\"" + "v".repeat(10_000) + "\"}";
    AuditRecord r =
        new AuditRecord(
            base.ts(),
            base.actorType(),
            base.actorId(),
            base.actorName(),
            base.ip(),
            base.userAgent(),
            base.requestId(),
            base.action(),
            base.targetType(),
            base.targetId(),
            base.targetName(),
            base.result(),
            base.reason(),
            big,
            big,
            null,
            null);
    String m = SyslogFormat.message(r, "h", "1", 8192);
    assertThat(m.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(8192);
    var matcher = HEADER.matcher(m);
    assertThat(matcher.matches()).isTrue();
    JsonNode body = JSON.readTree(matcher.group(7));
    assertThat(body.path("truncated").asBoolean()).isTrue();
    assertThat(body.has("before")).isFalse();
    String tiny = SyslogFormat.message(r, "h", "1", 200);
    assertThat(tiny).endsWith("]").doesNotContain("﻿");
  }

  @Test
  @DisplayName(
      "T-SEC-82 the SD-ID uses the PEN constant (32473, the documentation number until Argos"
          + " registers one)")
  void pen() {
    assertThat(SyslogFormat.SD_ID).isEqualTo("argos@" + SyslogFormat.PEN);
    assertThat(SyslogFormat.PEN).isEqualTo(32473);
  }
}
