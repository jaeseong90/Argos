/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.error;

import io.argos.server.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes {@link ErrorBody} responses. The single writer of the REST error format: the global
 * exception handler ({@link ApiExceptionHandler}), the error page ({@link ApiErrorController}), the
 * Spring Security entry point and access-denied handler ({@code SecurityConfig}) and the API
 * filters of WO-24 (CSRF, rate limits, authentication, password change) all write through it, so
 * every error has the same shape whatever produced it.
 *
 * <p>The body is written directly to the response, without content negotiation: an error is JSON
 * even when the request accepted something else. Headers already set (request id, server version,
 * security headers) stay; a response that is already committed is left alone.
 */
public final class ErrorBodyWriter {

  /** Media type of every API body: {@code application/json;charset=UTF-8} (14 §7.4). */
  public static final MediaType JSON = new MediaType("application", "json", StandardCharsets.UTF_8);

  /** Own mapper: the body is a fixed structure and must not depend on API-wide settings. */
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private ErrorBodyWriter() {}

  /** Writes the error of an {@link ApiException}. */
  public static void write(
      HttpServletRequest request, HttpServletResponse response, ApiException error)
      throws IOException {
    write(
        request,
        response,
        error.errorCode(),
        error.clientMessage(),
        error.details(),
        error.headers());
  }

  /** Writes an error with the fixed message of its code. */
  public static void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code)
      throws IOException {
    write(request, response, code, null, List.of(), Map.of());
  }

  /**
   * Writes an error response.
   *
   * @param code error code (status and wire code)
   * @param message fixed server text, or null for the code's message (always the code's for 5xx)
   * @param details field details, or empty
   * @param headers extra headers, e.g. {@code Retry-After}
   */
  public static void write(
      HttpServletRequest request,
      HttpServletResponse response,
      ErrorCode code,
      String message,
      List<ErrorBody.Detail> details,
      Map<String, String> headers)
      throws IOException {
    if (response.isCommitted()) {
      return;
    }
    byte[] json = json(ErrorBody.of(code, message, details, requestId(request)));
    response.resetBuffer();
    response.setStatus(code.status());
    headers.forEach(response::setHeader);
    response.setContentType(JSON.toString());
    response.setContentLength(json.length);
    try {
      OutputStream out = response.getOutputStream();
      out.write(json);
      out.flush();
    } catch (IllegalStateException writerInUse) {
      // getWriter() was called before (e.g. by a filter that started a body): same bytes, as text.
      response.getWriter().write(new String(json, StandardCharsets.UTF_8));
      response.getWriter().flush();
    }
  }

  /** The JSON bytes of a body. */
  public static byte[] json(ErrorBody body) {
    return MAPPER.writeValueAsBytes(body);
  }

  /** The request id of the current request (request attribute, else the logging context). */
  static String requestId(HttpServletRequest request) {
    String id = request == null ? null : RequestIdFilter.currentId(request);
    return id != null ? id : MDC.get(RequestIdFilter.MDC_KEY);
  }
}
