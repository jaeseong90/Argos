/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** The 14 §2.6 error body and the code table. */
@Tag("req:SEC-56")
class ErrorBodyTest {

  private static String json(ErrorBody body) {
    return new String(ErrorBodyWriter.json(body), StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName(
      "T-SEC-56a a 4xx body has code, message, details and request_id in the 14 §2.6 shape")
  void clientErrorShape() {
    ErrorBody body =
        ErrorBody.of(
            ErrorCode.RANGE_INVALID,
            "to must be after from",
            List.of(new ErrorBody.Detail("to", "before_from")),
            "01JABC");
    assertThat(json(body))
        .isEqualTo(
            "{\"error\":{\"code\":\"range_invalid\",\"message\":\"to must be after from\","
                + "\"details\":[{\"field\":\"to\",\"reason\":\"before_from\"}],"
                + "\"request_id\":\"01JABC\"}}");
  }

  @Test
  @DisplayName(
      "T-SEC-56a empty details and a missing request id are left out; a detail may lack a field")
  void optionalParts() {
    assertThat(json(ErrorBody.of(ErrorCode.NOT_FOUND, null, null, null)))
        .isEqualTo(
            "{\"error\":{\"code\":\"not_found\",\"message\":\"The resource does not exist\"}}");
    assertThat(
            json(
                ErrorBody.of(
                    ErrorCode.PASSWORD_POLICY,
                    null,
                    List.of(ErrorBody.Detail.of("too_short")),
                    "r1")))
        .isEqualTo(
            "{\"error\":{\"code\":\"password_policy\",\"message\":\"The password does not satisfy"
                + " the password policy\",\"details\":[{\"reason\":\"too_short\"}],"
                + "\"request_id\":\"r1\"}}");
  }

  @Test
  @DisplayName("T-SEC-56a a 5xx body carries the fixed message and the request id only")
  void serverErrorShape() {
    for (ErrorCode code : ErrorCode.values()) {
      if (!code.serverError()) {
        continue;
      }
      ErrorBody body =
          ErrorBody.of(
              code,
              "java.sql.SQLException at /opt/argos/x.java host db-1",
              List.of(new ErrorBody.Detail("sql", "SELECT 1")),
              "r2");
      assertThat(json(body))
          .as(code.name())
          .isEqualTo(
              "{\"error\":{\"code\":\""
                  + code.code()
                  + "\",\"message\":\""
                  + code.defaultMessage()
                  + "\",\"request_id\":\"r2\"}}");
    }
    assertThat(ErrorCode.INTERNAL.code()).isEqualTo("internal");
    assertThat(ErrorCode.INTERNAL.status()).isEqualTo(500);
  }

  @Test
  @DisplayName("details are capped at 20 entries")
  void detailsCapped() {
    List<ErrorBody.Detail> many = new ArrayList<>();
    for (int i = 0; i < 50; i++) {
      many.add(new ErrorBody.Detail("f" + i, "invalid"));
    }
    assertThat(ErrorBody.of(ErrorCode.INVALID_REQUEST, null, many, null).error().details())
        .hasSize(ErrorBody.MAX_DETAILS);
  }

  @Test
  @DisplayName(
      "T-SEC-56a the code table holds every 14 §2.6 code and the WO-24 auth codes, with their status")
  void codeTable() {
    Set<String> codes =
        Arrays.stream(ErrorCode.values()).map(ErrorCode::code).collect(Collectors.toSet());
    assertThat(codes)
        .contains(
            // 14 §2.6
            "range_invalid",
            "limit_exceeded",
            "filter_invalid",
            "unsupported_sort",
            "payload_too_large",
            "unauthenticated",
            "session_expired",
            "session_revoked",
            "forbidden",
            "csrf_invalid",
            "not_owner",
            "pii_view_required",
            "not_found",
            "already_exists",
            "invalid_state",
            "webhook_destination_blocked",
            "password_policy",
            "template_variable_forbidden",
            "remote_key_not_allowed",
            "account_locked",
            "rate_limited",
            "internal",
            "storage_unavailable",
            "starting",
            "query_timeout",
            // 16 §7.9
            "password_change_required");
    assertThat(ErrorCode.PAYLOAD_TOO_LARGE.status()).isEqualTo(400);
    assertThat(ErrorCode.LIMIT_EXCEEDED.status()).isEqualTo(400);
    assertThat(ErrorCode.SESSION_REVOKED.status()).isEqualTo(401);
    assertThat(ErrorCode.CSRF_INVALID.status()).isEqualTo(403);
    assertThat(ErrorCode.PASSWORD_CHANGE_REQUIRED.status()).isEqualTo(403);
    assertThat(ErrorCode.PASSWORD_POLICY.status()).isEqualTo(422);
    assertThat(ErrorCode.ACCOUNT_LOCKED.status()).isEqualTo(423);
    assertThat(ErrorCode.RATE_LIMITED.status()).isEqualTo(429);
    assertThat(ErrorCode.STORAGE_UNAVAILABLE.status()).isEqualTo(503);
    assertThat(ErrorCode.QUERY_TIMEOUT.status()).isEqualTo(504);
  }

  @Test
  @DisplayName(
      "a bare status maps to its generic code; 413/414 to 400 codes; every 5xx to internal")
  void forStatus() {
    assertThat(ErrorCode.forStatus(400)).isEqualTo(ErrorCode.INVALID_REQUEST);
    assertThat(ErrorCode.forStatus(401)).isEqualTo(ErrorCode.UNAUTHENTICATED);
    assertThat(ErrorCode.forStatus(403)).isEqualTo(ErrorCode.FORBIDDEN);
    assertThat(ErrorCode.forStatus(404)).isEqualTo(ErrorCode.NOT_FOUND);
    assertThat(ErrorCode.forStatus(405)).isEqualTo(ErrorCode.METHOD_NOT_ALLOWED);
    assertThat(ErrorCode.forStatus(413)).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
    assertThat(ErrorCode.forStatus(414)).isEqualTo(ErrorCode.LIMIT_EXCEEDED);
    assertThat(ErrorCode.forStatus(418)).isEqualTo(ErrorCode.INVALID_REQUEST);
    assertThat(ErrorCode.forStatus(429)).isEqualTo(ErrorCode.RATE_LIMITED);
    assertThat(ErrorCode.forStatus(500)).isEqualTo(ErrorCode.INTERNAL);
    assertThat(ErrorCode.forStatus(503)).isEqualTo(ErrorCode.INTERNAL);
    assertThat(ErrorCode.forStatus(504)).isEqualTo(ErrorCode.INTERNAL);
  }
}
