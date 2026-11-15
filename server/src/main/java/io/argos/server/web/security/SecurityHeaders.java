/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web.security;

import jakarta.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The security headers of the HTTP port (16 §9.8, SEC-45) with their exact values, in one place:
 * {@link SecurityHeadersFilter} sets them, the tests assert them.
 *
 * <ul>
 *   <li>{@link #STATIC}: on every response of port 8080 (API, UI page, static files, errors);
 *   <li>{@link #HSTS}: only on secure requests ({@code request.isSecure()}: TLS at the server, or
 *       {@code X-Forwarded-Proto: https} from a reverse proxy the operator trusts, see {@code
 *       server.forward-headers-strategy} in {@code application.yml});
 *   <li>{@link #API_CACHE_CONTROL}: on every {@code /api/**} response, always (ADR-0010 C-106 (9):
 *       SEC-45 wins over the rollup-cache exception of 14 §7.4).
 * </ul>
 *
 * The Content-Security-Policy belongs to the UI page only ({@link CspPolicy}).
 */
public final class SecurityHeaders {

  /** {@code Strict-Transport-Security}. */
  public static final String HSTS = "Strict-Transport-Security";

  /** Value of {@link #HSTS}: one year, subdomains included. */
  public static final String HSTS_VALUE = "max-age=31536000; includeSubDomains";

  /** {@code Cache-Control} of every API response. */
  public static final String API_CACHE_CONTROL = "no-store";

  /** Headers on every response, name to value, in a stable order. */
  public static final Map<String, String> STATIC = staticHeaders();

  private SecurityHeaders() {}

  private static Map<String, String> staticHeaders() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("X-Content-Type-Options", "nosniff");
    headers.put("X-Frame-Options", "DENY");
    headers.put("Referrer-Policy", "strict-origin-when-cross-origin");
    headers.put(
        "Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()");
    headers.put("Cross-Origin-Opener-Policy", "same-origin");
    headers.put("Cross-Origin-Resource-Policy", "same-origin");
    return Collections.unmodifiableMap(headers);
  }

  /** Sets the headers of every response (not HSTS, not the API cache rule). */
  public static void applyStatic(HttpServletResponse response) {
    STATIC.forEach(response::setHeader);
  }
}
