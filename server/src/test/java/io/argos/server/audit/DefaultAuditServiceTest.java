/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.argos.server.web.RequestIdFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** The minimal audit service of WO-24 (12 §2, §8.1; 16 §11.2, §11.3). */
@Tag("req:SEC-80")
class DefaultAuditServiceTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final List<AuditRecord> written = new ArrayList<>();

  @AfterEach
  void clearMdc() {
    MDC.remove(RequestIdFilter.MDC_KEY);
  }

  private double failures() {
    return registry.get(DefaultAuditService.FAILURES_METER).counter().count();
  }

  @Test
  @DisplayName(
      "T-SEC-80a a record carries the 16 §11.2 values: actor, target, outcome, reason, request id")
  void record() {
    DefaultAuditService service = new DefaultAuditService(List.of(written::add), registry);
    MDC.put(RequestIdFilter.MDC_KEY, "01JREQUEST");
    UUID user = UUID.randomUUID();
    UUID sid = UUID.randomUUID();
    service.record(
        AuditEvent.SESSION_REVOKED,
        new Actor(Actor.Type.USER, user.toString(), "ops", "::1%lo0", "Mozilla/5.0\u0007"),
        Target.session(sid),
        AuditResult.success("logout"));
    assertThat(written).hasSize(1);
    AuditRecord r = written.get(0);
    assertThat(r.action()).isEqualTo("SESSION_REVOKED");
    assertThat(r.actorType()).isEqualTo("user");
    assertThat(r.actorId()).isEqualTo(user.toString());
    assertThat(r.actorName()).isEqualTo("ops");
    assertThat(r.ip()).isEqualTo("::1");
    assertThat(r.userAgent()).isEqualTo("Mozilla/5.0�");
    assertThat(r.requestId()).isEqualTo("01JREQUEST");
    assertThat(r.targetType()).isEqualTo("session");
    assertThat(r.targetId()).isEqualTo(sid.toString());
    assertThat(r.result()).isEqualTo("success");
    assertThat(r.reason()).isEqualTo("logout");
    assertThat(r.beforeJson()).isNull();
    assertThat(r.afterJson()).isNull();
    assertThat(failures()).isZero();
  }

  @Test
  @DisplayName("T-SEC-80a before and after are masked: secret keys, token values; flags stay")
  void masking() {
    DefaultAuditService service = new DefaultAuditService(List.of(written::add), registry);
    String agentToken = "argos_" + "A".repeat(43);
    Map<String, Object> before = new LinkedHashMap<>();
    before.put("role", "VIEWER");
    before.put("must_change_password", true);
    before.put("password", "plain-text-value");
    before.put("refresh_hash", "ab".repeat(32));
    Map<String, Object> after = new LinkedHashMap<>();
    after.put("role", "ADMIN");
    after.put("note", "pasted " + agentToken + " by mistake");
    after.put("kiosk_session", Map.of("allowed", true, "api_key", "k-123"));
    after.put("permissions", List.of("pii.view"));
    service.record(
        AuditEvent.ROLE_CHANGED,
        Actor.system(),
        Target.user(UUID.randomUUID(), "ops"),
        AuditResult.success(),
        before,
        after);
    AuditRecord r = written.get(0);
    assertThat(r.beforeJson())
        .isEqualTo(
            "{\"role\":\"VIEWER\",\"must_change_password\":true,\"password\":\"****\","
                + "\"refresh_hash\":\"****\"}");
    assertThat(r.afterJson())
        .contains("\"role\":\"ADMIN\"")
        .contains("\"note\":\"pasted argos_**** by mistake\"")
        .contains("\"api_key\":\"****\"")
        .contains("\"allowed\":true")
        .contains("\"permissions\":[\"pii.view\"]")
        .doesNotContain(agentToken)
        .doesNotContain("k-123");
    assertThat(r.actorType()).isEqualTo("system");
  }

  @Test
  @DisplayName("T-SEC-80a values are cut to the audit_logs column sizes; a host name is not an IP")
  void columnSizes() {
    DefaultAuditService service = new DefaultAuditService(List.of(written::add), registry);
    service.record(
        AuditEvent.LOGIN_FAILED,
        new Actor(Actor.Type.USER, null, "n".repeat(500), "db.internal.example", "u".repeat(900)),
        new Target("user", "i".repeat(100), "t".repeat(400)),
        AuditResult.failure("r".repeat(1000)));
    AuditRecord r = written.get(0);
    assertThat(r.actorName()).hasSize(128);
    assertThat(r.userAgent()).hasSize(256);
    assertThat(r.targetId()).hasSize(64);
    assertThat(r.targetName()).hasSize(256);
    assertThat(r.reason()).hasSize(512);
    assertThat(r.ip()).isNull();
    assertThat(r.result()).isEqualTo("failure");
    assertThat(DefaultAuditService.ipLiteral("10.1.2.3")).isEqualTo("10.1.2.3");
    assertThat(DefaultAuditService.ipLiteral("[2001:db8::1]")).isEqualTo("2001:db8::1");
    assertThat(DefaultAuditService.cut("ab😀", 3)).isEqualTo("ab");
  }

  @Test
  @DisplayName(
      "T-SEC-80a a failing sink never fails the caller: counted in argos_audit_write_failures_total,"
          + " the other sinks still get the record")
  void failureCounted() {
    AuditSink broken =
        record -> {
          throw new IllegalStateException("connection refused to pg-1:5432");
        };
    DefaultAuditService service = new DefaultAuditService(List.of(broken, written::add), registry);
    assertThat(failures()).isZero();
    assertThatCode(
            () ->
                service.record(
                    AuditEvent.LOGOUT, Actor.system(), Target.none(), AuditResult.success()))
        .doesNotThrowAnyException();
    assertThat(failures()).isEqualTo(1.0);
    assertThat(written).hasSize(1);
  }

  @Test
  @DisplayName("secret key names, with any case and separator")
  void secretKeys() {
    assertThat(AuditMasking.secretKey("password")).isTrue();
    assertThat(AuditMasking.secretKey("newPassword")).isTrue();
    assertThat(AuditMasking.secretKey("setup-token")).isTrue();
    assertThat(AuditMasking.secretKey("jwt.key")).isTrue();
    assertThat(AuditMasking.secretKey("refresh_hash")).isTrue();
    assertThat(AuditMasking.secretKey("role")).isFalse();
    assertThat(AuditMasking.secretKey("token_prefix")).isFalse();
    assertThat(AuditMasking.secretKey("kiosk_session")).isFalse();
    assertThat(AuditMasking.mask(null)).isNull();
  }
}
