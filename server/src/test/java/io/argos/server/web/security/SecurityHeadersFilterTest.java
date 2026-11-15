/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The rules of {@link SecurityHeadersFilter} (16 §9.8) on each kind of request. */
@Tag("req:SEC-45")
class SecurityHeadersFilterTest {

  private static MockHttpServletRequest request(String path) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    request.setServletPath(path);
    return request;
  }

  private static MockHttpServletResponse filter(
      MockHttpServletRequest request, java.util.function.Consumer<HttpServletResponse> handler)
      throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    new SecurityHeadersFilter()
        .doFilter(
            request,
            response,
            new MockFilterChain() {
              @Override
              public void doFilter(
                  jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                handler.accept((HttpServletResponse) res);
              }
            });
    return response;
  }

  private static void assertStatic(MockHttpServletResponse response) {
    assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getHeader("X-Frame-Options")).isEqualTo("DENY");
    assertThat(response.getHeader("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
    assertThat(response.getHeader("Permissions-Policy"))
        .isEqualTo("camera=(), microphone=(), geolocation=(), payment=(), usb=()");
    assertThat(response.getHeader("Cross-Origin-Opener-Policy")).isEqualTo("same-origin");
    assertThat(response.getHeader("Cross-Origin-Resource-Policy")).isEqualTo("same-origin");
  }

  @Test
  @DisplayName("T-SEC-45 every path gets the static headers; only /api gets Cache-Control no-store")
  void staticHeaders() throws Exception {
    MockHttpServletResponse page = filter(request("/apps/1"), r -> {});
    assertStatic(page);
    assertThat(page.getHeader("Cache-Control")).isNull();

    MockHttpServletResponse api = filter(request("/api/v1/x"), r -> {});
    assertStatic(api);
    assertThat(api.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(filter(request("/apix"), r -> {}).getHeader("Cache-Control")).isNull();
    assertThat(filter(request("/api"), r -> {}).getHeader("Cache-Control")).isEqualTo("no-store");
  }

  @Test
  @DisplayName("T-SEC-45 a later writer cannot replace Cache-Control on /api (ADR-0010 C-106 (9))")
  void cacheControlIsFixed() throws Exception {
    MockHttpServletResponse set =
        filter(request("/api/v1/x"), r -> r.setHeader("Cache-Control", "max-age=60"));
    assertThat(set.getHeaders("Cache-Control")).containsExactly("no-store");
    MockHttpServletResponse added =
        filter(request("/api/v1/x"), r -> r.addHeader("cache-control", "max-age=60"));
    assertThat(added.getHeaders("Cache-Control")).containsExactly("no-store");

    MockHttpServletResponse elsewhere =
        filter(request("/assets/a.js"), r -> r.setHeader("Cache-Control", "max-age=60"));
    assertThat(elsewhere.getHeader("Cache-Control")).isEqualTo("max-age=60");
  }

  @Test
  @DisplayName("T-SEC-45 reset() keeps the headers")
  void resetKeepsHeaders() throws Exception {
    MockHttpServletResponse response = filter(request("/api/v1/x"), HttpServletResponse::reset);
    assertStatic(response);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
  }

  @Test
  @DisplayName("T-SEC-45 HSTS only on secure requests, with the exact value")
  void hsts() throws Exception {
    assertThat(filter(request("/api/v1/x"), r -> {}).getHeader("Strict-Transport-Security"))
        .isNull();
    MockHttpServletRequest secure = request("/apps/1");
    secure.setSecure(true);
    assertThat(filter(secure, r -> {}).getHeader("Strict-Transport-Security"))
        .isEqualTo("max-age=31536000; includeSubDomains");
    MockHttpServletRequest forwarded = request("/apps/1");
    forwarded.addHeader("X-Forwarded-Proto", "https");
    assertThat(filter(forwarded, r -> {}).getHeader("Strict-Transport-Security"))
        .as("the header alone proves nothing: Tomcat sets isSecure for a trusted proxy")
        .isNull();
  }

  @Test
  @DisplayName("T-SEC-45 a JSON body on /api always names its charset, set by any route")
  void jsonCharset() throws Exception {
    assertThat(
            filter(request("/api/v1/x"), r -> r.setContentType("application/json"))
                .getContentType())
        .isEqualToIgnoringCase("application/json;charset=UTF-8");
    assertThat(
            filter(request("/api/v1/x"), r -> r.setHeader("Content-Type", "application/json"))
                .getContentType())
        .isEqualToIgnoringCase("application/json;charset=UTF-8");
    assertThat(
            filter(
                    request("/api/v1/x"),
                    r -> r.addHeader("content-type", "application/problem+json"))
                .getContentType())
        .isEqualToIgnoringCase("application/problem+json;charset=UTF-8");
    assertThat(filter(request("/api/v1/x"), r -> r.setContentType("text/csv")).getContentType())
        .isEqualTo("text/csv");
    assertThat(SecurityHeadersFilter.jsonWithCharset("application/json; charset=ISO-8859-1"))
        .isEqualTo("application/json; charset=ISO-8859-1");
    assertThat(SecurityHeadersFilter.jsonWithCharset(null)).isNull();
  }

  @Test
  @DisplayName("T-SEC-45 an error dispatch follows the request that failed; a decoded path is /api")
  void errorDispatchAndEncodedPath() throws Exception {
    MockHttpServletRequest error = request("/error");
    error.setDispatcherType(DispatcherType.ERROR);
    error.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/x");
    assertThat(filter(error, r -> {}).getHeader("Cache-Control")).isEqualTo("no-store");

    MockHttpServletRequest pageError = request("/error");
    pageError.setDispatcherType(DispatcherType.ERROR);
    pageError.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/apps/1");
    assertThat(filter(pageError, r -> {}).getHeader("Cache-Control")).isNull();

    MockHttpServletRequest encoded = new MockHttpServletRequest("GET", "/%61pi/v1/x");
    encoded.setServletPath("/api/v1/x");
    assertThat(filter(encoded, r -> {}).getHeader("Cache-Control")).isEqualTo("no-store");
  }

  @Test
  @DisplayName("a CORS preflight to the API is 403 in the ErrorBody; other requests pass")
  void corsPreflight() throws Exception {
    CorsPreflightFilter filter = new CorsPreflightFilter();
    assertThat(filter.position())
        .isGreaterThan(FilterOrder.IP_RATE_LIMIT)
        .isLessThan(FilterOrder.ACCESS_TOKEN_AUTHENTICATION);

    MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/api/v1/x");
    preflight.addHeader("Origin", "https://other.example");
    preflight.addHeader("Access-Control-Request-Method", "POST");
    MockHttpServletResponse refused = new MockHttpServletResponse();
    MockFilterChain stopped = new MockFilterChain();
    filter.doFilter(preflight, refused, stopped);
    assertThat(refused.getStatus()).isEqualTo(403);
    assertThat(refused.getContentAsString()).contains("\"code\":\"forbidden\"");
    assertThat(stopped.getRequest()).isNull();

    MockHttpServletRequest plainOptions = new MockHttpServletRequest("OPTIONS", "/api/v1/x");
    plainOptions.addHeader("Origin", "https://other.example");
    MockFilterChain passed = new MockFilterChain();
    filter.doFilter(plainOptions, new MockHttpServletResponse(), passed);
    assertThat(passed.getRequest()).isNotNull();

    MockHttpServletRequest post = new MockHttpServletRequest("POST", "/api/v1/x");
    post.addHeader("Origin", "https://other.example");
    post.addHeader("Access-Control-Request-Method", "POST");
    MockFilterChain alsoPassed = new MockFilterChain();
    filter.doFilter(post, new MockHttpServletResponse(), alsoPassed);
    assertThat(alsoPassed.getRequest()).isNotNull();
  }

  @Test
  @DisplayName(
      "T-SEC-45 the early pass sets the same headers on the response a limit filter writes")
  void earlyPass() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    new SecurityHeadersFilter.Early()
        .doFilter(request("/api/v1/x"), response, new MockFilterChain());
    assertStatic(response);
    assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    assertThat(FilterOrder.SECURITY_HEADERS_EARLY)
        .isGreaterThan(FilterOrder.REQUEST_ID)
        .isLessThan(FilterOrder.URL_LIMIT);
  }
}
