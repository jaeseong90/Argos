/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web.security;

import io.argos.server.config.ArgosProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The Content-Security-Policy of the UI page (16 §9.9, SEC-46, DSN-16-15). Only the page carries
 * it; the API and static files have nothing to run ({@link SecurityHeaders}).
 *
 * <pre>
 * default-src 'self'; script-src 'self' 'nonce-N' 'strict-dynamic'; style-src 'self' 'nonce-N';
 * img-src 'self' data: blob:; font-src 'self'; connect-src 'self' wss://HOST;
 * worker-src 'self' blob:; object-src 'none'; base-uri 'self'; form-action 'self';
 * frame-ancestors 'none'; report-uri /api/v1/csp-report; report-to csp
 * </pre>
 *
 * <ul>
 *   <li>{@code img-src data:} covers the inline favicon of {@code ui/index.html} (WO-00);
 *   <li>{@code connect-src} names the WebSocket origin of {@code argos.web.public-url}: {@code
 *       wss://} for an {@code https} URL, {@code ws://} for an {@code http} one, where {@code wss}
 *       could not connect;
 *   <li>{@code argos.web.csp.style-unsafe-inline} switches {@code style-src} to the documented
 *       fallback {@code 'self' 'unsafe-inline'}, without the nonce (a nonce would disable {@code
 *       'unsafe-inline'}). Off by default; whether it is needed is decided by WO-30 (16 §15 #6).
 * </ul>
 */
@Component
public class CspPolicy {

  /** Bytes of randomness of one nonce (16 §9.9). */
  public static final int NONCE_BYTES = 16;

  /** The response header of the policy. */
  public static final String HEADER = "Content-Security-Policy";

  /** The header that names the {@code report-to} group of the policy. */
  public static final String REPORTING_ENDPOINTS_HEADER = "Reporting-Endpoints";

  /** The value of {@link #REPORTING_ENDPOINTS_HEADER}. */
  public static final String REPORTING_ENDPOINTS =
      "csp=\"" + IpRateLimitFilter.CSP_REPORT_PATH + "\"";

  private static final SecureRandom RANDOM = new SecureRandom();

  private final String websocketOrigin;
  private final boolean styleUnsafeInline;

  /**
   * The policy of the configured public URL.
   *
   * @throws IllegalArgumentException when {@code argos.web.public-url} is not an absolute http or
   *     https URL
   */
  @Autowired
  public CspPolicy(ArgosProperties properties) {
    this(properties.web().publicUrl(), properties.web().csp().styleUnsafeInline());
  }

  /** A policy with explicit values (tests). */
  public CspPolicy(String publicUrl, boolean styleUnsafeInline) {
    this.websocketOrigin = websocketOrigin(publicUrl);
    this.styleUnsafeInline = styleUnsafeInline;
  }

  /** A fresh nonce: {@value #NONCE_BYTES} random bytes as unpadded base64url. */
  public static String newNonce() {
    byte[] bytes = new byte[NONCE_BYTES];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /** The header value for one response. */
  public String header(String nonce) {
    String script = "'nonce-" + nonce + "'";
    return "default-src 'self'; "
        + ("script-src 'self' " + script + " 'strict-dynamic'; ")
        + ("style-src 'self' " + (styleUnsafeInline ? "'unsafe-inline'" : script) + "; ")
        + "img-src 'self' data: blob:; "
        + "font-src 'self'; "
        + ("connect-src 'self' " + websocketOrigin + "; ")
        + "worker-src 'self' blob:; "
        + "object-src 'none'; "
        + "base-uri 'self'; "
        + "form-action 'self'; "
        + "frame-ancestors 'none'; "
        + ("report-uri " + IpRateLimitFilter.CSP_REPORT_PATH + "; ")
        + "report-to csp";
  }

  /** {@code wss://host[:port]} or {@code ws://host[:port]} of a public URL. */
  static String websocketOrigin(String publicUrl) {
    URI uri;
    try {
      uri = new URI(publicUrl == null ? "" : publicUrl.strip());
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("argos.web.public-url is not a valid URL", e);
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (uri.getHost() == null || !(scheme.equals("http") || scheme.equals("https"))) {
      throw new IllegalArgumentException("argos.web.public-url must be an http or https URL");
    }
    return (scheme.equals("https") ? "wss://" : "ws://")
        + uri.getHost().toLowerCase(Locale.ROOT)
        + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
  }
}
