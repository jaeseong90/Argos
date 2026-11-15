/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Request URL limit of the HTTP port (14 §7.2: 8 KB for the server): a request whose path plus
 * query ({@code requestURI} and {@code ?queryString}, as received, before decoding) is longer than
 * {@link #MAX_URL_BYTES} bytes is answered with 400 {@code limit_exceeded} (14 §2.6) before
 * anything else handles it. The message is fixed; the URL is not echoed. The request line as a
 * whole is also bounded by the connector's header limit of 16 KB ({@code application.yml}).
 */
public class RequestUrlLimitFilter extends OncePerRequestFilter {

  /** 8 KiB (14 §7.2). */
  public static final int MAX_URL_BYTES = 8 * 1024;

  /** The error message of a refused request. */
  static final String MESSAGE = "The request URL exceeds the limit of 8 KB";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (urlBytes(request) > MAX_URL_BYTES) {
      ErrorResponses.write(
          request, response, HttpServletResponse.SC_BAD_REQUEST, "limit_exceeded", MESSAGE);
      return;
    }
    chain.doFilter(request, response);
  }

  /** Bytes of the path and the query string (with its {@code ?}) in UTF-8. */
  static long urlBytes(HttpServletRequest request) {
    long bytes = utf8Length(request.getRequestURI());
    String query = request.getQueryString();
    if (query != null) {
      bytes += 1 + utf8Length(query);
    }
    return bytes;
  }

  /**
   * UTF-8 length of {@code text} without encoding it. The connector passes the request target as
   * received, which is ASCII (one byte per character); anything else is counted as UTF-8 would
   * encode it.
   */
  static long utf8Length(String text) {
    if (text == null) {
      return 0;
    }
    return text.codePoints()
        .mapToLong(c -> c < 0x80 ? 1 : c < 0x800 ? 2 : c < 0x10000 ? 3 : 4)
        .sum();
  }
}
