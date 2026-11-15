/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.auth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One {@code Set-Cookie} header as the server sent it, parsed (RFC 6265 §5.2) for exact attribute
 * assertions (T-SEC-40). {@link #raw()} keeps the header text; never print it in test output when
 * the value is a token (use {@link #toString()}, which hides the value).
 *
 * @param name cookie name
 * @param value cookie value (a token for {@code argos_at}, {@code argos_rt}, {@code argos_csrf})
 * @param attributes attributes in header order, keys in lower case ({@code path}, {@code max-age},
 *     {@code samesite}, {@code httponly}, {@code secure}, ...); flags map to the empty string
 * @param raw the header text
 */
public record SetCookie(String name, String value, Map<String, String> attributes, String raw) {

  public SetCookie {
    attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
  }

  /** Parses one {@code Set-Cookie} header value. */
  public static SetCookie parse(String header) {
    String[] parts = header.split(";", -1);
    String pair = parts[0];
    int eq = pair.indexOf('=');
    String name = (eq < 0 ? pair : pair.substring(0, eq)).trim();
    String value = eq < 0 ? "" : pair.substring(eq + 1).trim();
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      value = value.substring(1, value.length() - 1);
    }
    Map<String, String> attributes = new LinkedHashMap<>();
    for (int i = 1; i < parts.length; i++) {
      String part = parts[i].trim();
      if (part.isEmpty()) {
        continue;
      }
      int sep = part.indexOf('=');
      String key = (sep < 0 ? part : part.substring(0, sep)).trim().toLowerCase(Locale.ROOT);
      String val = sep < 0 ? "" : part.substring(sep + 1).trim();
      attributes.putIfAbsent(key, val);
    }
    return new SetCookie(name, value, attributes, header);
  }

  /** An attribute value (case-insensitive name); a flag is present with the empty string. */
  public Optional<String> attribute(String key) {
    return Optional.ofNullable(attributes.get(key.toLowerCase(Locale.ROOT)));
  }

  /** {@code Path}, if given. */
  public Optional<String> path() {
    return attribute("path");
  }

  /** {@code Max-Age} in seconds, if given. */
  public Optional<Long> maxAge() {
    return attribute("max-age").map(Long::parseLong);
  }

  /** {@code SameSite}, if given. */
  public Optional<String> sameSite() {
    return attribute("samesite");
  }

  /** Whether {@code HttpOnly} is set. */
  public boolean httpOnly() {
    return attributes.containsKey("httponly");
  }

  /** Whether {@code Secure} is set. */
  public boolean secure() {
    return attributes.containsKey("secure");
  }

  /** Whether this header deletes the cookie ({@code Max-Age=0} or an empty value with expiry). */
  public boolean deletes() {
    return maxAge().map(age -> age <= 0).orElse(false);
  }

  /** Name and attributes; the value is never printed. */
  @Override
  public String toString() {
    return "SetCookie[" + name + "=<" + value.length() + " chars>, " + attributes + "]";
  }
}
