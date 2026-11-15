/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.session.SessionCookies;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.SessionTable;
import io.argos.server.testing.auth.TestUsers.TestUser;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-SEC-43 (16 §7.6, 14 §2.2, SEC-43, DSN-16-11) on the real filter chain: every state-changing
 * request needs the double-submit token and the public origin; a cross-site request is refused;
 * nothing a browser held before a login is still valid after it.
 */
@Tag("req:SEC-43")
class CsrfSecurityTest {

  private static final String LOGOUT = "/api/v1/auth/logout";
  private static final String ME = "/api/v1/auth/me";

  private final ArgosTestServer server = AuthSuite.server();

  private ApiTestClient loggedIn() {
    TestUser user = AuthSuite.users().create(Role.VIEWER);
    ApiTestClient client = server.client();
    assertThat(client.login(user.username(), user.password()).status()).isEqualTo(200);
    return client;
  }

  private static void assertCsrfRefused(ApiResponse response) {
    assertThat(response.status()).as(response.toString()).isEqualTo(403);
    assertThat(response.errorCode()).contains("csrf_invalid");
    assertThat(response.json().path("error").path("request_id").isString()).isTrue();
  }

  private static void assertStillLoggedIn(ApiTestClient client) {
    assertThat(client.get(ME).status()).as("the refused request changed nothing").isEqualTo(200);
  }

  @Test
  @DisplayName("T-SEC-43 a POST without the header is 403 csrf_invalid")
  void noHeader() {
    ApiTestClient client = loggedIn();
    assertCsrfRefused(client.csrf(false).post(LOGOUT, null));
    assertStillLoggedIn(client);
  }

  @Test
  @DisplayName("T-SEC-43 a header different from the cookie is 403 csrf_invalid")
  void differentHeader() {
    ApiTestClient client = loggedIn();
    String other =
        ApiTestClient.of(server.baseUrl())
            .fetchCsrf()
            .setCookie("argos_csrf")
            .orElseThrow()
            .value();
    assertCsrfRefused(client.csrfToken(other).post(LOGOUT, null));
    assertStillLoggedIn(client);
  }

  @Test
  @DisplayName("T-SEC-43 no CSRF cookie, with or without a header, is 403 csrf_invalid")
  void noCookie() {
    ApiTestClient client = loggedIn();
    String token = client.cookie(SessionCookies.CSRF_COOKIE).orElseThrow();
    client.cookies().remove(SessionCookies.CSRF_COOKIE);
    assertCsrfRefused(client.csrfToken(token).post(LOGOUT, null));
    assertCsrfRefused(client.csrfToken(null).post(LOGOUT, null));
    assertStillLoggedIn(client);
  }

  @Test
  @DisplayName("T-SEC-43 a foreign Origin is 403 csrf_invalid even with a valid token")
  void foreignOrigin() {
    ApiTestClient client = loggedIn();
    assertCsrfRefused(client.origin(ApiTestClient.FOREIGN_ORIGIN).post(LOGOUT, null));
    client.sameOrigin();
    assertStillLoggedIn(client);
  }

  @Test
  @DisplayName("T-SEC-43 without Origin the Referer decides; with neither: 403 csrf_invalid")
  void refererAndNeither() {
    ApiTestClient client = loggedIn();
    client.origin(null);
    assertCsrfRefused(client.post(LOGOUT, null));
    client.referer(ApiTestClient.FOREIGN_ORIGIN + "/page");
    assertCsrfRefused(client.post(LOGOUT, null));
    client.referer(client.serverOrigin() + "/ui/settings");
    assertThat(client.post(LOGOUT, null).status()).isEqualTo(204);
  }

  @Test
  @DisplayName("T-SEC-43 Sec-Fetch-Site: cross-site is 403 csrf_invalid with everything else right")
  void crossSiteFetchMetadata() {
    ApiTestClient client = loggedIn();
    assertCsrfRefused(client.secFetchSite("cross-site").post(LOGOUT, null));
    client.secFetchSite("same-origin");
    assertStillLoggedIn(client);
  }

  @Test
  @DisplayName(
      "T-SEC-43 a page of another site gets 403 csrf_invalid on every state-changing method")
  void crossSitePage() {
    ApiTestClient client = loggedIn();
    client.crossSite();
    assertCsrfRefused(client.post(LOGOUT, null));
    assertCsrfRefused(client.put("/api/v1/auth/password", Map.of("current", "a", "new", "b")));
    assertCsrfRefused(
        client.patch("/api/v1/admin/users/" + UUID.randomUUID(), Map.of("enabled", false)));
    assertCsrfRefused(client.delete("/api/v1/admin/users/" + UUID.randomUUID()));
  }

  @Test
  @DisplayName("T-SEC-43 GET needs no token and changes nothing; every unsafe method is checked")
  void methods() {
    ApiTestClient client = loggedIn();
    client.csrf(false);
    assertThat(client.get(ME).status()).isEqualTo(200);
    assertCsrfRefused(client.put("/api/v1/auth/password", Map.of("current", "a", "new", "b")));
    assertCsrfRefused(client.delete("/api/v1/anything"));
    assertCsrfRefused(client.patch("/api/v1/anything", Map.of()));
  }

  @Test
  @DisplayName("T-SEC-43 login needs no token (first request) but the Origin check still applies")
  void loginOriginOnly() {
    TestUser user = AuthSuite.users().create(Role.VIEWER);
    ApiTestClient client = server.client();
    Map<String, String> credentials =
        Map.of("username", user.username(), "password", user.password());
    assertThat(client.post(ApiTestClient.LOGIN_PATH, credentials).status())
        .as("no token, no cookie, right Origin")
        .isEqualTo(200);

    ApiTestClient foreign = server.client().origin(ApiTestClient.FOREIGN_ORIGIN);
    assertCsrfRefused(foreign.post(ApiTestClient.LOGIN_PATH, credentials));
    assertThat(foreign.cookies().all()).isEmpty();

    ApiTestClient noOrigin = server.client().origin(null);
    assertCsrfRefused(noOrigin.post(ApiTestClient.LOGIN_PATH, credentials));

    ApiTestClient crossSite = server.client().secFetchSite("cross-site");
    assertCsrfRefused(crossSite.post(ApiTestClient.LOGIN_PATH, credentials));
  }

  @Test
  @DisplayName("T-SEC-43 setup needs no token (first request) but the Origin check still applies")
  void setupOriginOnly() {
    ApiTestClient foreign = server.client().origin(ApiTestClient.FOREIGN_ORIGIN);
    assertCsrfRefused(foreign.post("/api/v1/setup/admin", Map.of()));
    ApiResponse sameOrigin = server.client().csrf(false).post("/api/v1/setup/admin", Map.of());
    assertThat(sameOrigin.errorCode()).isNotEqualTo(java.util.Optional.of("csrf_invalid"));
  }

  @Test
  @DisplayName("T-SEC-43 refresh is a state change: it needs the token")
  void refreshNeedsToken() {
    ApiTestClient client = loggedIn();
    assertCsrfRefused(client.csrf(false).post(SessionCookies.REFRESH_PATH, null));
    client.csrf(true);
    assertThat(client.post(SessionCookies.REFRESH_PATH, null).status()).isEqualTo(200);
  }

  @Test
  @DisplayName(
      "T-SEC-43 sid and CSRF token differ before and after a login; a CSRF token planted before it"
          + " is refused after it (fixation, SEC-43)")
  void newIdentifiersAtLogin() {
    TestUser user = AuthSuite.users().create(Role.VIEWER);
    ApiTestClient client = server.client();
    String planted = client.fetchCsrf().setCookie("argos_csrf").orElseThrow().value();
    client.login(user.username(), user.password());
    String firstCsrf = client.cookie(SessionCookies.CSRF_COOKIE).orElseThrow();
    String firstAccess = client.cookie(SessionCookies.ACCESS_COOKIE).orElseThrow();
    String firstRefresh = client.cookie(SessionCookies.REFRESH_COOKIE).orElseThrow();
    assertThat(firstCsrf).as("no token from before the login survives it").isNotEqualTo(planted);

    client.login(user.username(), user.password());
    assertThat(client.cookie(SessionCookies.CSRF_COOKIE).orElseThrow()).isNotEqualTo(firstCsrf);
    assertThat(client.cookie(SessionCookies.REFRESH_COOKIE).orElseThrow())
        .isNotEqualTo(firstRefresh);
    UUID firstSid = SessionTable.sid(firstAccess);
    UUID secondSid = SessionTable.sid(client.cookie(SessionCookies.ACCESS_COOKIE).orElseThrow());
    assertThat(secondSid).isNotEqualTo(firstSid);

    assertCsrfRefused(
        client.csrf(false).send("POST", LOGOUT, null, Map.of(SessionCookies.CSRF_HEADER, planted)));
    assertStillLoggedIn(client);
  }

  @Test
  @DisplayName("T-SEC-43 the cookie names of the server are those the test client and the UI use")
  void constants() {
    assertThat(SessionCookies.ACCESS_COOKIE).isEqualTo(ApiTestClient.ACCESS_COOKIE);
    assertThat(SessionCookies.REFRESH_COOKIE).isEqualTo(ApiTestClient.REFRESH_COOKIE);
    assertThat(SessionCookies.CSRF_COOKIE).isEqualTo(ApiTestClient.CSRF_COOKIE);
    assertThat(SessionCookies.CSRF_HEADER).isEqualTo(ApiTestClient.CSRF_HEADER);
  }
}
