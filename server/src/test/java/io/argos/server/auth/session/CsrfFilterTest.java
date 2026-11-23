/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.token.SecureTokens;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The rules of the CSRF filter (16 §7.6, 14 §2.2, SEC-43), one by one. */
@Tag("req:SEC-43")
class CsrfFilterTest {

  private static final String ORIGIN = "https://argos.example.test";
  private static final String PATH = "/api/v1/auth/logout";

  private final CsrfFilter filter = new CsrfFilter(new PublicOrigin(ORIGIN + "/some/base"));
  private final String token = SecureTokens.newToken();

  private MockHttpServletRequest valid(String method, String path) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    request.addHeader("Origin", ORIGIN);
    request.addHeader(SessionCookies.CSRF_HEADER, token);
    request.setCookies(new Cookie(SessionCookies.CSRF_COOKIE, token));
    return request;
  }

  private boolean passes(MockHttpServletRequest request) throws Exception {
    MockFilterChain chain = new MockFilterChain();
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    if (chain.getRequest() == null) {
      assertThat(response.getStatus()).isEqualTo(403);
      assertThat(response.getContentAsString()).contains("\"csrf_invalid\"");
      return false;
    }
    return true;
  }

  private static MockHttpServletRequest withHeaders(String method, String path, String... headers) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    for (int i = 0; i < headers.length; i += 2) {
      request.addHeader(headers[i], headers[i + 1]);
    }
    return request;
  }

  @ParameterizedTest(name = "T-SEC-43a {0} with token, cookie and Origin passes")
  @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
  void unsafeMethodsPass(String method) throws Exception {
    assertThat(passes(valid(method, PATH))).isTrue();
  }

  @ParameterizedTest(name = "T-SEC-43a {0} without anything passes (no state change)")
  @ValueSource(strings = {"GET", "HEAD", "OPTIONS", "TRACE"})
  void safeMethodsPass(String method) throws Exception {
    assertThat(passes(new MockHttpServletRequest(method, PATH))).isTrue();
  }

  @Test
  @DisplayName("T-SEC-43a no header, no cookie, different values, a malformed cookie: 403")
  void doubleSubmit() throws Exception {
    MockHttpServletRequest noHeader = withHeaders("POST", PATH, "Origin", ORIGIN);
    noHeader.setCookies(new Cookie(SessionCookies.CSRF_COOKIE, token));
    assertThat(passes(noHeader)).isFalse();

    MockHttpServletRequest noCookie =
        withHeaders("POST", PATH, "Origin", ORIGIN, SessionCookies.CSRF_HEADER, token);
    assertThat(passes(noCookie)).isFalse();

    MockHttpServletRequest different =
        withHeaders("POST", PATH, "Origin", ORIGIN, SessionCookies.CSRF_HEADER, token);
    different.setCookies(new Cookie(SessionCookies.CSRF_COOKIE, SecureTokens.newToken()));
    assertThat(passes(different)).isFalse();

    MockHttpServletRequest chosen =
        withHeaders("POST", PATH, "Origin", ORIGIN, SessionCookies.CSRF_HEADER, "x");
    chosen.setCookies(new Cookie(SessionCookies.CSRF_COOKIE, "x"));
    assertThat(passes(chosen)).as("a value the server cannot have issued").isFalse();
  }

  @ParameterizedTest(name = "T-SEC-43a Origin {0} is refused")
  @ValueSource(
      strings = {
        "https://attacker.example",
        "http://argos.example.test",
        "https://argos.example.test:8443",
        "https://argos.example.test.attacker.example",
        "https://sub.argos.example.test",
        "https://argos.example.test/path",
        "https://user@argos.example.test",
        "null",
        ""
      })
  void foreignOrigin(String origin) throws Exception {
    MockHttpServletRequest request = valid("POST", PATH);
    request.removeHeader("Origin");
    request.addHeader("Origin", origin);
    assertThat(passes(request)).isFalse();
  }

  @ParameterizedTest(name = "T-SEC-43a Origin {0} is the public origin")
  @ValueSource(strings = {"https://argos.example.test", "HTTPS://Argos.Example.TEST:443"})
  void sameOriginVariants(String origin) throws Exception {
    MockHttpServletRequest request = valid("POST", PATH);
    request.removeHeader("Origin");
    request.addHeader("Origin", origin);
    assertThat(passes(request)).isTrue();
  }

  @Test
  @DisplayName("T-SEC-43a Referer stands in for an absent Origin only; with neither: 403")
  void referer() throws Exception {
    MockHttpServletRequest same = valid("POST", PATH);
    same.removeHeader("Origin");
    same.addHeader("Referer", ORIGIN + "/ui/page?x=1");
    assertThat(passes(same)).isTrue();

    MockHttpServletRequest foreign = valid("POST", PATH);
    foreign.removeHeader("Origin");
    foreign.addHeader("Referer", "https://attacker.example/" + ORIGIN);
    assertThat(passes(foreign)).isFalse();

    MockHttpServletRequest neither = valid("POST", PATH);
    neither.removeHeader("Origin");
    assertThat(passes(neither)).isFalse();

    MockHttpServletRequest originWins = valid("POST", PATH);
    originWins.removeHeader("Origin");
    originWins.addHeader("Origin", "https://attacker.example");
    originWins.addHeader("Referer", ORIGIN + "/");
    assertThat(passes(originWins)).isFalse();
  }

  @ParameterizedTest(name = "T-SEC-43a Sec-Fetch-Site {0}")
  @ValueSource(strings = {"cross-site", "CROSS-SITE", " cross-site "})
  void crossSite(String value) throws Exception {
    MockHttpServletRequest request = valid("POST", PATH);
    request.addHeader("Sec-Fetch-Site", value);
    assertThat(passes(request)).isFalse();
  }

  @ParameterizedTest(name = "T-SEC-43a Sec-Fetch-Site {0} with a good Origin passes")
  @ValueSource(strings = {"same-origin", "same-site", "none"})
  void otherFetchSites(String value) throws Exception {
    MockHttpServletRequest request = valid("POST", PATH);
    request.addHeader("Sec-Fetch-Site", value);
    assertThat(passes(request)).isTrue();
  }

  @ParameterizedTest(name = "T-SEC-43a {0}: Origin check only, no token needed")
  @ValueSource(strings = {CsrfFilter.LOGIN_PATH, CsrfFilter.SETUP_ADMIN_PATH})
  void loginAndSetup(String path) throws Exception {
    assertThat(passes(withHeaders("POST", path, "Origin", ORIGIN))).isTrue();
    assertThat(passes(withHeaders("POST", path, "Origin", "https://attacker.example"))).isFalse();
    assertThat(passes(withHeaders("POST", path))).isFalse();
    assertThat(passes(withHeaders("POST", path, "Origin", ORIGIN, "Sec-Fetch-Site", "cross-site")))
        .isFalse();
  }

  @Test
  @DisplayName("T-SEC-43a an exemption needs the exact path: another encoding gets the full check")
  void encodedPathHasNoExemption() throws Exception {
    assertThat(passes(withHeaders("POST", "/api/v1/auth/logi%6E", "Origin", ORIGIN))).isFalse();
    assertThat(passes(withHeaders("POST", "/api/v1/auth/login/", "Origin", ORIGIN))).isFalse();
  }

  @Test
  @DisplayName("T-SEC-43a CSP reports carry no session and pass the filter (16 §9.9)")
  void cspReports() throws Exception {
    assertThat(passes(withHeaders("POST", "/api/v1/csp-report"))).isTrue();
  }

  @Test
  @DisplayName(
      "T-SEC-43a POST /api/v1/events with an agent token (Bearer) skips CSRF; nothing else does")
  void agentTokenEvents() throws Exception {
    String bearer = "Bearer argos_" + "a".repeat(43);
    assertThat(passes(withHeaders("POST", "/api/v1/events", "Authorization", bearer))).isTrue();
    assertThat(passes(withHeaders("POST", "/api/v1/events"))).as("no header").isFalse();
    assertThat(passes(withHeaders("POST", "/api/v1/events", "Authorization", "Basic eDp5")))
        .as("another scheme")
        .isFalse();
    assertThat(passes(withHeaders("DELETE", "/api/v1/events", "Authorization", bearer)))
        .as("another method")
        .isFalse();
    assertThat(passes(withHeaders("POST", "/api/v1/events/x", "Authorization", bearer)))
        .as("another path")
        .isFalse();
    assertThat(passes(withHeaders("POST", "/api/v1/apps", "Authorization", bearer)))
        .as("another endpoint")
        .isFalse();
  }
}
