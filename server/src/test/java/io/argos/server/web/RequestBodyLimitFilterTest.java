/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.web.RequestBodyLimitFilter.PayloadTooLargeException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Request body limit of 14 §7.2: 1 MB for every content type. */
@Tag("req:DSN-14-05")
class RequestBodyLimitFilterTest {

  @Test
  @DisplayName("a declared body over 1 MB is refused with 400 payload_too_large, unread")
  void declaredLength() throws Exception {
    RequestBodyLimitFilter filter = new RequestBodyLimitFilter();
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/x");
    request.setContent(new byte[(int) RequestBodyLimitFilter.MAX_BODY_BYTES + 1]);
    request.setContentType("application/json");
    MockHttpServletResponse response = new MockHttpServletResponse();
    AtomicBoolean called = new AtomicBoolean();

    filter.doFilter(request, response, (req, res) -> called.set(true));

    assertThat(called).isFalse();
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"error\":{\"code\":\"payload_too_large\",\"message\":\"The request body exceeds"
                + " the limit of 1 MB\"}}");
  }

  @Test
  @DisplayName("a body without a length is cut off when it passes the limit while being read")
  void streamedBody() throws Exception {
    RequestBodyLimitFilter filter = new RequestBodyLimitFilter(100);
    filter.doFilter(
        chunked(100),
        new MockHttpServletResponse(),
        (req, res) -> assertThat(req.getInputStream().readAllBytes()).hasSize(100));

    assertThatThrownBy(
            () ->
                filter.doFilter(
                    chunked(101),
                    new MockHttpServletResponse(),
                    (req, res) -> req.getInputStream().readAllBytes()))
        .isInstanceOf(PayloadTooLargeException.class);
  }

  /** A request without a declared length (chunked transfer). */
  private static MockHttpServletRequest chunked(int bytes) {
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/x") {
          @Override
          public long getContentLengthLong() {
            return -1;
          }

          @Override
          public int getContentLength() {
            return -1;
          }
        };
    request.setContent(new byte[bytes]);
    return request;
  }
}
