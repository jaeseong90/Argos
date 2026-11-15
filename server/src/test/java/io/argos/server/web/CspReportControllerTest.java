/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** What {@link CspReportController} accepts and what it keeps of a report (16 §9.9). */
@Tag("req:SEC-46")
class CspReportControllerTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final CspReportController controller = new CspReportController(registry);

  private static MockHttpServletRequest post(String body) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/csp-report");
    request.setContent(body.getBytes(StandardCharsets.UTF_8));
    return request;
  }

  private double count() {
    return registry.get(CspReportController.VIOLATIONS_METER).counter().count();
  }

  @Test
  @DisplayName("T-SEC-46a both report formats are counted")
  void formats() throws Exception {
    assertThat(
            controller
                .report(post("{\"csp-report\":{\"effective-directive\":\"img-src\"}}"))
                .getStatusCode()
                .value())
        .isEqualTo(204);
    assertThat(
            controller
                .report(post("[{\"type\":\"csp-violation\",\"body\":{\"blockedURL\":\"inline\"}}]"))
                .getStatusCode()
                .value())
        .isEqualTo(204);
    assertThat(count()).isEqualTo(2);
  }

  @Test
  @DisplayName("T-SEC-46a anything else is 400 and not counted")
  void notReports() {
    for (String body :
        new String[] {
          "", "{}", "[]", "x", "{\"csp-report\":\"x\"}", "[{\"type\":\"other\",\"body\":{}}]", "1"
        }) {
      assertThatThrownBy(() -> controller.report(post(body)))
          .as(body)
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }
    assertThat(count()).isZero();
  }

  @Test
  @DisplayName("T-SEC-46a over 8 KiB is 400 payload_too_large, by the length and while reading")
  void size() {
    String body = "{\"csp-report\":{\"x\":\"" + "a".repeat(9_000) + "\"}}";
    assertThatThrownBy(() -> controller.report(post(body)))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));

    MockHttpServletRequest noLength =
        new MockHttpServletRequest("POST", "/api/v1/csp-report") {
          @Override
          public long getContentLengthLong() {
            return -1;
          }
        };
    noLength.setContent(body.getBytes(StandardCharsets.UTF_8));
    assertThatThrownBy(() -> controller.report(noLength))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
    assertThat(count()).isZero();
  }

  @Test
  @DisplayName(
      "T-SEC-46a the blocked resource is reduced to its origin: a path or query never reaches the"
          + " log")
  void origin() {
    assertThat(CspReportController.origin("https://evil.example:444/p?token=abc"))
        .isEqualTo("https://evil.example:444");
    assertThat(CspReportController.origin("https://evil.example/p"))
        .isEqualTo("https://evil.example");
    assertThat(CspReportController.origin("inline")).isEqualTo("inline");
    assertThat(CspReportController.origin("eval")).isEqualTo("eval");
    assertThat(CspReportController.origin("data:text/html;base64,abc")).isEqualTo("data");
    assertThat(CspReportController.origin("not a url: with ? spaces")).isEqualTo("other");
    assertThat(CspReportController.origin("some/relative/path?token=abc")).isEqualTo("other");
    assertThat(CspReportController.origin("")).isEqualTo("");
  }

  @Test
  @DisplayName("T-SEC-46a a logged field has no control characters and a bounded length")
  void clean() {
    assertThat(CspReportController.clean("a\nb\rc\td")).isEqualTo("a?b?c?d");
    assertThat(CspReportController.clean("x".repeat(500))).hasSize(120);
  }
}
