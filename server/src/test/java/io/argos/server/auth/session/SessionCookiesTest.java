/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.AuthTestProperties;
import io.argos.server.config.ArgosProperties;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The exact {@code Set-Cookie} text of the three cookies (16 §7.1, C-106 (3), C-107, SEC-40). */
@Tag("req:SEC-40")
class SessionCookiesTest {

  private static final SessionCookies COOKIES = new SessionCookies(AuthTestProperties.defaults());

  /** The header without the {@code Expires} attribute, whose text depends on the time. */
  private static String stable(String header) {
    return header.replaceAll("; Expires=[^;]+", "");
  }

  @Test
  @DisplayName("T-SEC-40a argos_at: HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=900")
  void access() {
    MockHttpServletResponse response = new MockHttpServletResponse();
    COOKIES.setAccess(response, "value");
    assertThat(stable(response.getHeader("Set-Cookie")))
        .isEqualTo("argos_at=value; Path=/; Max-Age=900; Secure; HttpOnly; SameSite=Strict");
  }

  @Test
  @DisplayName(
      "T-SEC-40a argos_rt: HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth/refresh;"
          + " Max-Age=86400; longer only for a longer idle lifetime")
  void refresh() {
    MockHttpServletResponse response = new MockHttpServletResponse();
    COOKIES.setRefresh(response, "value", Duration.ofHours(8));
    assertThat(stable(response.getHeader("Set-Cookie")))
        .isEqualTo(
            "argos_rt=value; Path=/api/v1/auth/refresh; Max-Age=86400; Secure; HttpOnly;"
                + " SameSite=Strict");
    MockHttpServletResponse longer = new MockHttpServletResponse();
    COOKIES.setRefresh(longer, "value", Duration.ofDays(2));
    assertThat(stable(longer.getHeader("Set-Cookie"))).contains("Max-Age=172800");
  }

  @Test
  @DisplayName("T-SEC-40a argos_csrf: Secure; SameSite=Strict; Path=/; readable by the UI script")
  void csrf() {
    MockHttpServletResponse response = new MockHttpServletResponse();
    COOKIES.setCsrf(response, "value");
    assertThat(response.getHeader("Set-Cookie"))
        .isEqualTo("argos_csrf=value; Path=/; Secure; SameSite=Strict");
  }

  @Test
  @DisplayName("T-SEC-40a clearing keeps name, path and flags with Max-Age=0")
  void clear() {
    MockHttpServletResponse response = new MockHttpServletResponse();
    COOKIES.clearAll(response);
    assertThat(response.getHeaders("Set-Cookie").stream().map(SessionCookiesTest::stable).toList())
        .containsExactly(
            "argos_at=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Strict",
            "argos_rt=; Path=/api/v1/auth/refresh; Max-Age=0; Secure; HttpOnly; SameSite=Strict",
            "argos_csrf=; Path=/; Max-Age=0; Secure; SameSite=Strict");
  }

  @Test
  @DisplayName("T-SEC-40a argos.security.cookie.secure=false drops Secure, nothing else")
  void notSecure() {
    ArgosProperties properties =
        AuthTestProperties.of(Map.of("argos.security.cookie.secure", "false"));
    MockHttpServletResponse response = new MockHttpServletResponse();
    new SessionCookies(properties).setAccess(response, "value");
    assertThat(stable(response.getHeader("Set-Cookie")))
        .isEqualTo("argos_at=value; Path=/; Max-Age=900; HttpOnly; SameSite=Strict");
  }

  @Test
  @DisplayName("T-SEC-40a reading: the named cookie, empty values and absent cookies are nothing")
  void read() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    assertThat(SessionCookies.read(request, SessionCookies.ACCESS_COOKIE)).isEmpty();
    request.setCookies(new Cookie("other", "x"), new Cookie(SessionCookies.ACCESS_COOKIE, "y"));
    assertThat(SessionCookies.read(request, SessionCookies.ACCESS_COOKIE)).contains("y");
    request.setCookies(new Cookie(SessionCookies.ACCESS_COOKIE, ""));
    assertThat(SessionCookies.read(request, SessionCookies.ACCESS_COOKIE)).isEmpty();
    assertThat(List.of(SessionCookies.ACCESS_PATH, SessionCookies.CSRF_PATH)).containsOnly("/");
  }
}
