/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.auth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/**
 * A browser-like HTTP client of the Argos REST API for integration and security tests (WO-24, 16
 * §7): one instance is one browser profile at one origin.
 *
 * <ul>
 *   <li><b>Cookies</b>: a {@link CookieJar} that honours {@code Path}, {@code Max-Age} and {@code
 *       SameSite}; every {@code Set-Cookie} of every response is also kept parsed in the {@link
 *       ApiResponse} for exact attribute assertions (T-SEC-40).
 *   <li><b>CSRF</b> (16 §7.6, 14 §2.2): on POST, PUT, PATCH and DELETE the {@value #CSRF_HEADER}
 *       header carries the {@value #CSRF_COOKIE} cookie, as the UI client does. {@link
 *       #csrf(boolean)} turns that off, {@link #csrfToken(String)} sends another value.
 *   <li><b>Origin and Fetch metadata</b>: unsafe requests carry {@code Origin} (the server's origin
 *       by default) and every request {@code Sec-Fetch-Site: same-origin}; {@link #origin(String)},
 *       {@link #referer(String)} and {@link #secFetchSite(String)} change or drop them, {@link
 *       #crossSite()} simulates a request from another site (foreign Origin, {@code cross-site},
 *       {@code SameSite} cookies withheld).
 *   <li><b>Login</b>: {@link #login(String, String)} fetches the CSRF cookie and posts {@code
 *       /api/v1/auth/login} (14 §4.1).
 * </ul>
 *
 * Settings are fluent and mutable; the client is not thread-safe. No redirects are followed.
 * Nothing secret is printed by {@code toString()} of the client, responses or cookies.
 */
public final class ApiTestClient {

  /** CSRF cookie (16 §7.1). */
  public static final String CSRF_COOKIE = "argos_csrf";

  /** CSRF header (16 §7.6, 14 §2.2). */
  public static final String CSRF_HEADER = "X-Argos-CSRF";

  /** Access-token cookie (16 §7.1). */
  public static final String ACCESS_COOKIE = "argos_at";

  /** Refresh-token cookie (16 §7.1). */
  public static final String REFRESH_COOKIE = "argos_rt";

  /** CSRF token endpoint (14 §4.1). */
  public static final String CSRF_PATH = "/api/v1/auth/csrf";

  /** Login endpoint (14 §4.1). */
  public static final String LOGIN_PATH = "/api/v1/auth/login";

  /** An origin that is not the server's. */
  public static final String FOREIGN_ORIGIN = "https://attacker.example";

  private static final Set<String> UNSAFE = Set.of("POST", "PUT", "PATCH", "DELETE");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final HttpClient http;
  private final URI base;
  private final String serverOrigin;
  private final CookieJar jar;
  private final Map<String, String> extraHeaders = new LinkedHashMap<>();

  private boolean csrf = true;
  private String csrfToken;
  private boolean sendOrigin = true;
  private String origin;
  private String referer;
  private String secFetchSite = "same-origin";
  private boolean crossSite;
  private String userAgent = "argos-api-test-client";
  private Duration timeout = Duration.ofSeconds(30);
  private ApiResponse last;

  private ApiTestClient(URI base, CookieJar jar) {
    this.base = base;
    this.serverOrigin = origin(base);
    this.jar = jar;
    this.http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
  }

  /** A client of the server at {@code baseUrl} (e.g. {@code http://127.0.0.1:18080}). */
  public static ApiTestClient of(String baseUrl) {
    return new ApiTestClient(
        URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl),
        new CookieJar());
  }

  /** A second browser at the same server: same settings, empty cookie jar. */
  public ApiTestClient newBrowser() {
    ApiTestClient other = new ApiTestClient(base, new CookieJar());
    other.csrf = csrf;
    other.csrfToken = csrfToken;
    other.sendOrigin = sendOrigin;
    other.origin = origin;
    other.referer = referer;
    other.secFetchSite = secFetchSite;
    other.crossSite = crossSite;
    other.userAgent = userAgent;
    other.timeout = timeout;
    other.extraHeaders.putAll(extraHeaders);
    return other;
  }

  // --- settings ---------------------------------------------------------------------------------

  /** Whether unsafe requests carry {@value #CSRF_HEADER} (default true). */
  public ApiTestClient csrf(boolean enabled) {
    this.csrf = enabled;
    return this;
  }

  /**
   * Sends {@code token} as {@value #CSRF_HEADER} instead of the cookie value (null: the cookie).
   */
  public ApiTestClient csrfToken(String token) {
    this.csrfToken = token;
    return this;
  }

  /**
   * The {@code Origin} of unsafe requests: null drops the header; the default is the server's own
   * origin.
   */
  public ApiTestClient origin(String value) {
    this.sendOrigin = value != null;
    this.origin = value;
    return this;
  }

  /** Restores the default {@code Origin} (the server's origin). */
  public ApiTestClient sameOrigin() {
    this.sendOrigin = true;
    this.origin = null;
    this.secFetchSite = "same-origin";
    this.crossSite = false;
    return this;
  }

  /** A {@code Referer} for every request (null: none, the default). */
  public ApiTestClient referer(String value) {
    this.referer = value;
    return this;
  }

  /** {@code Sec-Fetch-Site} of every request (null drops it; default {@code same-origin}). */
  public ApiTestClient secFetchSite(String value) {
    this.secFetchSite = value;
    return this;
  }

  /**
   * Requests as a page of another site would send them: {@code Origin} {@value #FOREIGN_ORIGIN},
   * {@code Sec-Fetch-Site: cross-site}, and no {@code SameSite=Strict} or {@code Lax} cookies.
   */
  public ApiTestClient crossSite() {
    this.sendOrigin = true;
    this.origin = FOREIGN_ORIGIN;
    this.secFetchSite = "cross-site";
    this.crossSite = true;
    return this;
  }

  /** The {@code User-Agent} of every request. */
  public ApiTestClient userAgent(String value) {
    this.userAgent = value;
    return this;
  }

  /** An extra header on every request (null value removes it). */
  public ApiTestClient header(String name, String value) {
    if (value == null) {
      extraHeaders.remove(name);
    } else {
      extraHeaders.put(name, value);
    }
    return this;
  }

  /** Request timeout (default 30 s). */
  public ApiTestClient timeout(Duration value) {
    this.timeout = value;
    return this;
  }

  // --- requests ---------------------------------------------------------------------------------

  /** GET. */
  public ApiResponse get(String path) {
    return send("GET", path, null, Map.of());
  }

  /** POST with a JSON body ({@code null}: no body; a {@code String} is sent as is). */
  public ApiResponse post(String path, Object body) {
    return send("POST", path, body, Map.of());
  }

  /** PUT with a JSON body. */
  public ApiResponse put(String path, Object body) {
    return send("PUT", path, body, Map.of());
  }

  /** PATCH with a JSON body. */
  public ApiResponse patch(String path, Object body) {
    return send("PATCH", path, body, Map.of());
  }

  /** DELETE. */
  public ApiResponse delete(String path) {
    return send("DELETE", path, null, Map.of());
  }

  /**
   * Sends a request with the cookies, CSRF, Origin and Fetch metadata of the current settings.
   *
   * @param method HTTP method
   * @param path path with query, starting with {@code /}
   * @param body JSON body: null for none, a {@code String} as is, anything else serialised
   * @param headers further headers of this request only (override the defaults)
   */
  public ApiResponse send(String method, String path, Object body, Map<String, String> headers) {
    String m = method.toUpperCase(Locale.ROOT);
    HttpRequest.Builder request =
        HttpRequest.newBuilder(base.resolve(path)).timeout(timeout).header("User-Agent", userAgent);
    String cookies = jar.header(path, crossSite);
    if (cookies != null) {
      request.header("Cookie", cookies);
    }
    if (secFetchSite != null) {
      request.header("Sec-Fetch-Site", secFetchSite);
    }
    if (referer != null) {
      request.header("Referer", referer);
    }
    if (UNSAFE.contains(m)) {
      if (sendOrigin) {
        request.header("Origin", origin != null ? origin : serverOrigin);
      }
      if (csrf) {
        String token = csrfToken != null ? csrfToken : jar.value(CSRF_COOKIE).orElse(null);
        if (token != null) {
          request.header(CSRF_HEADER, token);
        }
      }
    }
    Map<String, String> all = new LinkedHashMap<>(extraHeaders);
    all.putAll(headers);
    all.forEach(request::setHeader);
    if (body == null) {
      request.method(m, HttpRequest.BodyPublishers.noBody());
    } else {
      String text = body instanceof String s ? s : JSON.writeValueAsString(body);
      request.header("Content-Type", "application/json");
      request.method(m, HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8));
    }
    HttpResponse<String> response;
    try {
      response =
          http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(m + " " + path + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(m + " " + path + " interrupted", e);
    }
    List<SetCookie> setCookies = new ArrayList<>();
    for (String header : response.headers().allValues("Set-Cookie")) {
      SetCookie cookie = SetCookie.parse(header);
      setCookies.add(cookie);
      jar.store(cookie, path);
    }
    last =
        new ApiResponse(
            m, path, response.statusCode(), response.headers(), response.body(), setCookies);
    return last;
  }

  /** {@code GET /api/v1/auth/csrf}: stores the {@value #CSRF_COOKIE} cookie. */
  public ApiResponse fetchCsrf() {
    return get(CSRF_PATH);
  }

  /**
   * Logs in like the UI: fetches a CSRF cookie when none is stored, then posts the credentials (14
   * §4.1, §8 example 1). The session cookies land in the jar.
   */
  public ApiResponse login(String username, String password) {
    if (jar.value(CSRF_COOKIE).isEmpty()) {
      fetchCsrf();
    }
    Map<String, String> credentials = new LinkedHashMap<>();
    credentials.put("username", username);
    credentials.put("password", password);
    return post(LOGIN_PATH, credentials);
  }

  // --- state ------------------------------------------------------------------------------------

  /** The cookie jar of this browser. */
  public CookieJar cookies() {
    return jar;
  }

  /** The stored value of a cookie. */
  public Optional<String> cookie(String name) {
    return jar.value(name);
  }

  /** The last response, or null before the first request. */
  public ApiResponse last() {
    return last;
  }

  /** The base URL (no trailing slash). */
  public String baseUrl() {
    return base.toString();
  }

  /** The server's origin ({@code scheme://host:port}), the default {@code Origin}. */
  public String serverOrigin() {
    return serverOrigin;
  }

  @Override
  public String toString() {
    return "ApiTestClient[" + base + ", cookies " + jar.all() + "]";
  }

  private static String origin(URI uri) {
    return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
  }
}
