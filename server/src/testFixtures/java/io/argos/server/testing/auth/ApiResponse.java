/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.auth;

import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One response seen by {@link ApiTestClient}: status, every header, the body as text, and the
 * parsed {@code Set-Cookie} headers.
 *
 * @param method request method
 * @param path request path (with query)
 * @param status HTTP status
 * @param headers response headers
 * @param body response body (UTF-8 text)
 * @param setCookies every {@code Set-Cookie} header, in order
 */
public record ApiResponse(
    String method,
    String path,
    int status,
    HttpHeaders headers,
    String body,
    List<SetCookie> setCookies) {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  public ApiResponse {
    setCookies = List.copyOf(setCookies);
  }

  /** The first value of a header. */
  public Optional<String> header(String name) {
    return headers.firstValue(name);
  }

  /** Every value of a header. */
  public List<String> headers(String name) {
    return headers.allValues(name);
  }

  /** The body as JSON. */
  public JsonNode json() {
    return JSON.readTree(body);
  }

  /** {@code error.code} of an {@code ErrorBody}, or empty when the body is not one. */
  public Optional<String> errorCode() {
    try {
      JsonNode code = json().path("error").path("code");
      return code.isString() ? Optional.of(code.asString()) : Optional.empty();
    } catch (RuntimeException notJson) {
      return Optional.empty();
    }
  }

  /** The {@code Set-Cookie} of {@code name}, if this response set or deleted it. */
  public Optional<SetCookie> setCookie(String name) {
    return setCookies.stream().filter(c -> c.name().equals(name)).findFirst();
  }

  /** Method, path, status and the body length; never the body or cookie values. */
  @Override
  public String toString() {
    return method
        + " "
        + path
        + " -> "
        + status
        + " ("
        + body.length()
        + " chars, cookies "
        + setCookies.stream().map(SetCookie::name).toList()
        + ")";
  }
}
