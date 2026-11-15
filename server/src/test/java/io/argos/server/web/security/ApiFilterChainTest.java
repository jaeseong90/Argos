/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.api.error.ErrorBodyWriter;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.web.WebConfiguration;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The filter order of the HTTP port and the API filter chain of {@link SecurityConfig}. */
class ApiFilterChainTest {

  /** A recording API filter. */
  private record Recording(int position, List<String> log, boolean reject) implements ApiFilter {
    @Override
    public void doFilter(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws java.io.IOException, jakarta.servlet.ServletException {
      log.add("in " + position);
      if (reject) {
        ErrorBodyWriter.write(request, response, ErrorCode.CSRF_INVALID);
        return;
      }
      chain.doFilter(request, response);
      log.add("out " + position);
    }
  }

  @Test
  @DisplayName(
      "the documented total order: RequestId < UrlLimit < BodyLimit < SecurityHeaders < Spring"
          + " Security; IpRateLimit < AccessToken < Csrf < UserRateLimit < MustChangePassword")
  void order() {
    assertThat(FilterOrder.REQUEST_ID).isEqualTo(WebConfiguration.REQUEST_ID_ORDER);
    assertThat(
            List.of(
                FilterOrder.REQUEST_ID,
                FilterOrder.URL_LIMIT,
                FilterOrder.BODY_LIMIT,
                FilterOrder.SECURITY_HEADERS,
                FilterOrder.SPRING_SECURITY))
        .isSorted()
        .doesNotHaveDuplicates();
    assertThat(
            List.of(
                FilterOrder.IP_RATE_LIMIT,
                FilterOrder.ACCESS_TOKEN_AUTHENTICATION,
                FilterOrder.CSRF,
                FilterOrder.USER_RATE_LIMIT,
                FilterOrder.MUST_CHANGE_PASSWORD))
        .isSorted()
        .doesNotHaveDuplicates();
    assertThat(FilterOrder.SPRING_SECURITY).isEqualTo(-100);
  }

  @Test
  @DisplayName("API filters run in position order, once, then the rest of the chain")
  void runsInOrder() throws Exception {
    List<String> log = new ArrayList<>();
    ApiFilterChain chain =
        new ApiFilterChain(
            List.of(
                new Recording(FilterOrder.CSRF, log, false),
                new Recording(FilterOrder.IP_RATE_LIMIT, log, false),
                new Recording(FilterOrder.ACCESS_TOKEN_AUTHENTICATION, log, false)));
    chain.doFilter(
        new MockHttpServletRequest("GET", "/api/v1/x"),
        new MockHttpServletResponse(),
        (req, res) -> log.add("handler"));
    assertThat(log)
        .containsExactly("in 100", "in 200", "in 300", "handler", "out 300", "out 200", "out 100");
  }

  @Test
  @DisplayName("a rejecting API filter ends the chain with its ErrorBody")
  void rejects() throws Exception {
    List<String> log = new ArrayList<>();
    ApiFilterChain chain =
        new ApiFilterChain(
            List.of(
                new Recording(FilterOrder.IP_RATE_LIMIT, log, false),
                new Recording(FilterOrder.CSRF, log, true),
                new Recording(FilterOrder.MUST_CHANGE_PASSWORD, log, false)));
    MockHttpServletResponse response = new MockHttpServletResponse();
    chain.doFilter(
        new MockHttpServletRequest("POST", "/api/v1/x"),
        response,
        (req, res) -> log.add("handler"));
    assertThat(log).containsExactly("in 100", "in 300", "out 100");
    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getContentAsString()).contains("\"code\":\"csrf_invalid\"");
  }

  @Test
  @DisplayName("two API filters at one position fail the start")
  void duplicatePositions() {
    List<String> log = new ArrayList<>();
    assertThatThrownBy(
            () ->
                new ApiFilterChain(
                    List.of(
                        new Recording(FilterOrder.CSRF, log, false),
                        new Recording(FilterOrder.CSRF, log, false))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("300");
  }
}
