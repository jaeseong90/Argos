/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Request URL limit of 14 §7.2: 8 KB for path plus query on the server. */
@Tag("req:DSN-14-05")
class RequestUrlLimitFilterTest {

  private static final String PATH = "/api/v1/x";

  /** A GET whose path, {@code ?} and query are {@code bytes} bytes long. */
  private static MockHttpServletRequest withUrlBytes(int bytes) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
    request.setQueryString("q=" + "a".repeat(bytes - PATH.length() - 1 - 2));
    return request;
  }

  private static MockHttpServletResponse filter(
      MockHttpServletRequest request, AtomicBoolean called) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    new RequestUrlLimitFilter().doFilter(request, response, (req, res) -> called.set(true));
    return response;
  }

  @Test
  @DisplayName("a URL of 8,192 bytes passes")
  void atTheLimit() throws Exception {
    MockHttpServletRequest request = withUrlBytes(RequestUrlLimitFilter.MAX_URL_BYTES);
    assertThat(RequestUrlLimitFilter.urlBytes(request)).isEqualTo(8192);
    AtomicBoolean called = new AtomicBoolean();

    MockHttpServletResponse response = filter(request, called);

    assertThat(called).isTrue();
    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentAsString()).isEmpty();
  }

  @Test
  @DisplayName("a URL of 8,193 bytes is refused with 400 limit_exceeded, the URL not echoed")
  void overTheLimit() throws Exception {
    MockHttpServletRequest request = withUrlBytes(RequestUrlLimitFilter.MAX_URL_BYTES + 1);
    assertThat(RequestUrlLimitFilter.urlBytes(request)).isEqualTo(8193);
    AtomicBoolean called = new AtomicBoolean();

    MockHttpServletResponse response = filter(request, called);

    assertThat(called).isFalse();
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentType()).startsWith("application/json");
    assertThat(response.getContentAsString())
        .isEqualTo(
            "{\"error\":{\"code\":\"limit_exceeded\",\"message\":\"The request URL exceeds the"
                + " limit of 8 KB\"}}")
        .doesNotContain("aaaa");
  }

  @Test
  @DisplayName("the path alone counts too, and non-ASCII characters count as UTF-8 bytes")
  void pathAndEncoding() throws Exception {
    MockHttpServletRequest longPath =
        new MockHttpServletRequest("GET", "/" + "p".repeat(RequestUrlLimitFilter.MAX_URL_BYTES));
    AtomicBoolean called = new AtomicBoolean();
    assertThat(filter(longPath, called).getStatus()).isEqualTo(400);
    assertThat(called).isFalse();

    assertThat(RequestUrlLimitFilter.utf8Length("aé€😀")).isEqualTo(10);
    assertThat(RequestUrlLimitFilter.utf8Length(null)).isZero();
  }
}
