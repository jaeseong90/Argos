/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.MediaType;

/**
 * Error responses written by servlet filters, before Spring MVC runs, in the error schema of 14
 * §2.6: {@code {"error":{"code":..., "message":..., "request_id":...}}}. Codes and messages are
 * fixed strings of the server; nothing from the request is echoed. The REST error mapping of the
 * controllers ({@code ErrorBody}, global exception handler) is WO-24's and should reuse the same
 * shape.
 */
public final class ErrorResponses {

  private ErrorResponses() {}

  /**
   * Writes an error response.
   *
   * @param status HTTP status
   * @param code error code of 14 §2.6, e.g. {@code payload_too_large}
   * @param message fixed operator-facing text (no request data)
   */
  public static void write(
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String code,
      String message)
      throws IOException {
    if (response.isCommitted()) {
      return;
    }
    String requestId = RequestIdFilter.currentId(request);
    String body =
        "{\"error\":{\"code\":"
            + quote(code)
            + ",\"message\":"
            + quote(message)
            + (requestId == null ? "" : ",\"request_id\":" + quote(requestId))
            + "}}";
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.getWriter().write(body);
  }

  /** A JSON string literal of a server-defined text. */
  static String quote(String text) {
    StringBuilder out = new StringBuilder(text.length() + 2).append('"');
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        default -> {
          if (c < 0x20) {
            out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('"').toString();
  }
}
