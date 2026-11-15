/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The cookies of one simulated browser for one origin (RFC 6265 §5.3-§5.4, simplified to a single
 * host): stores {@code Set-Cookie} headers by name and path, drops expired and deleted cookies, and
 * selects the cookies of a request by path match and same-site context.
 *
 * <ul>
 *   <li>Path: a cookie is sent when its {@code Path} path-matches the request path (RFC 6265
 *       §5.1.4); without {@code Path} the default path of the request that set it applies. The
 *       refresh cookie ({@code Path=/api/v1/auth/refresh}) therefore reaches the refresh endpoint
 *       only.
 *   <li>SameSite: a same-site request carries every cookie; a cross-site request ({@link
 *       ApiTestClient#crossSite()}) only {@code SameSite=None} cookies, as a browser does for
 *       {@code Strict} and for {@code Lax} on non-navigation requests.
 *   <li>Secure: sent over http to the loopback test server, as browsers do for {@code localhost} (a
 *       potentially trustworthy origin).
 *   <li>Expiry: {@code Max-Age} relative to the clock of the jar; {@code Max-Age<=0} deletes.
 * </ul>
 */
public final class CookieJar {

  /** A stored cookie. */
  public record Cookie(
      String name, String value, String path, String sameSite, boolean httpOnly, Instant expires) {

    /** Name and path; the value is never printed. */
    @Override
    public String toString() {
      return "Cookie[" + name + ", path=" + path + ", sameSite=" + sameSite + "]";
    }
  }

  private final Clock clock;
  private final Map<String, Cookie> cookies = new LinkedHashMap<>();

  /** A jar on the system clock. */
  public CookieJar() {
    this(Clock.systemUTC());
  }

  /** A jar on {@code clock}. */
  public CookieJar(Clock clock) {
    this.clock = clock;
  }

  /**
   * Stores (or deletes) the cookie of one {@code Set-Cookie} header of a response to {@code
   * requestPath}.
   */
  public synchronized void store(SetCookie header, String requestPath) {
    String path = header.path().filter(p -> p.startsWith("/")).orElse(defaultPath(requestPath));
    String key = header.name() + "\u0000" + path;
    Optional<Long> maxAge = header.maxAge();
    if (maxAge.isPresent() && maxAge.get() <= 0) {
      cookies.remove(key);
      return;
    }
    Instant expires = maxAge.map(s -> clock.instant().plusSeconds(s)).orElse(null);
    cookies.put(
        key,
        new Cookie(
            header.name(),
            header.value(),
            path,
            header.sameSite().map(s -> s.toLowerCase(Locale.ROOT)).orElse("lax"),
            header.httpOnly(),
            expires));
  }

  /**
   * The {@code Cookie} header value for a request, or null when no cookie applies.
   *
   * @param requestPath path of the request (a query string is ignored)
   * @param crossSite whether the request is cross-site (only {@code SameSite=None} cookies)
   */
  public synchronized String header(String requestPath, boolean crossSite) {
    List<Cookie> matching = matching(requestPath, crossSite);
    if (matching.isEmpty()) {
      return null;
    }
    StringBuilder out = new StringBuilder();
    for (Cookie c : matching) {
      if (!out.isEmpty()) {
        out.append("; ");
      }
      out.append(c.name()).append('=').append(c.value());
    }
    return out.toString();
  }

  /** The cookies a same-site request to {@code requestPath} would carry, longest path first. */
  public synchronized List<Cookie> matching(String requestPath, boolean crossSite) {
    purgeExpired();
    String path = pathOf(requestPath);
    List<Cookie> out = new ArrayList<>();
    for (Cookie c : cookies.values()) {
      if (pathMatches(path, c.path()) && (!crossSite || "none".equals(c.sameSite()))) {
        out.add(c);
      }
    }
    out.sort(Comparator.comparingInt((Cookie c) -> c.path().length()).reversed());
    return out;
  }

  /** The value of the cookie {@code name} (any path), if stored and not expired. */
  public synchronized Optional<String> value(String name) {
    purgeExpired();
    return cookies.values().stream()
        .filter(c -> c.name().equals(name))
        .map(Cookie::value)
        .findFirst();
  }

  /** Every stored cookie. */
  public synchronized List<Cookie> all() {
    purgeExpired();
    return List.copyOf(cookies.values());
  }

  /** Forgets every cookie. */
  public synchronized void clear() {
    cookies.clear();
  }

  /** Forgets the cookie {@code name} (every path). */
  public synchronized void remove(String name) {
    cookies.values().removeIf(c -> c.name().equals(name));
  }

  /** Stores a cookie directly (e.g. a token captured elsewhere, a forged value). */
  public synchronized void put(String name, String value, String path) {
    cookies.put(name + "\u0000" + path, new Cookie(name, value, path, "strict", true, null));
  }

  private void purgeExpired() {
    Instant now = clock.instant();
    cookies.values().removeIf(c -> c.expires() != null && !c.expires().isAfter(now));
  }

  /** RFC 6265 §5.1.4 path-match. */
  static boolean pathMatches(String requestPath, String cookiePath) {
    if (requestPath.equals(cookiePath)) {
      return true;
    }
    if (!requestPath.startsWith(cookiePath)) {
      return false;
    }
    return cookiePath.endsWith("/") || requestPath.charAt(cookiePath.length()) == '/';
  }

  /** RFC 6265 §5.1.4 default-path of a request path. */
  static String defaultPath(String requestPath) {
    String path = pathOf(requestPath);
    int last = path.lastIndexOf('/');
    return last <= 0 ? "/" : path.substring(0, last);
  }

  private static String pathOf(String requestPath) {
    int q = requestPath.indexOf('?');
    String path = q < 0 ? requestPath : requestPath.substring(0, q);
    return path.isEmpty() ? "/" : path;
  }
}
