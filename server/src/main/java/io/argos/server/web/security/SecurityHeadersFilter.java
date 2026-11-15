/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.web.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.Locale;

/**
 * Sets the security headers of 16 §9.8 (SEC-45) on every response of the HTTP port: the API, the UI
 * page, static files and error responses (it also runs on error dispatches). Registered as a
 * servlet filter for every path at {@link FilterOrder#SECURITY_HEADERS} ({@link
 * SecurityHeadersConfiguration}); Spring Security's own header writer is off ({@code
 * SecurityConfig}), so these are the only security headers.
 *
 * <ul>
 *   <li>every response: {@link SecurityHeaders#STATIC};
 *   <li>secure requests only ({@code request.isSecure()}): {@code Strict-Transport-Security};
 *   <li>{@code /api/**}: {@code Cache-Control: no-store}, which later code cannot replace (ADR-0010
 *       C-106 (9)), and a JSON {@code Content-Type} always names its charset ({@code
 *       application/json;charset=UTF-8}, 14 §7.4).
 * </ul>
 *
 * The headers are set before the request is handled, so they are present whatever writes the
 * response; the response is wrapped so that a {@code reset()} keeps them.
 */
public class SecurityHeadersFilter implements Filter {

  private static final String CACHE_CONTROL = "Cache-Control";
  private static final String CONTENT_TYPE = "Content-Type";

  @Override
  public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    if (!(req instanceof HttpServletRequest request)
        || !(res instanceof HttpServletResponse response)) {
      chain.doFilter(req, res);
      return;
    }
    HeaderKeepingResponse wrapped = HeaderKeepingResponse.of(request, response);
    wrapped.applyHeaders();
    chain.doFilter(request, wrapped);
  }

  /**
   * The early pass: sets the same headers, right after the request id and before the URL and body
   * limit filters of WO-20, whose 400 responses never reach {@link SecurityHeadersFilter}. It does
   * not wrap the response; the main filter does that for everything that passes the limits.
   */
  static final class Early implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
        throws IOException, ServletException {
      if (req instanceof HttpServletRequest request
          && res instanceof HttpServletResponse response) {
        HeaderKeepingResponse.of(request, response).applyHeaders();
      }
      chain.doFilter(req, res);
    }
  }

  /**
   * Whether the request (for an error dispatch: the request that failed) addresses the REST API.
   */
  static boolean isApi(HttpServletRequest request) {
    return RequestPaths.isApi(RequestPaths.original(request));
  }

  /**
   * {@code type} with {@code ;charset=UTF-8} when it is a JSON media type without a charset; any
   * other value unchanged.
   */
  static String jsonWithCharset(String type) {
    if (type == null) {
      return null;
    }
    String lower = type.toLowerCase(Locale.ROOT);
    int semicolon = lower.indexOf(';');
    String mediaType = (semicolon < 0 ? lower : lower.substring(0, semicolon)).strip();
    boolean json = mediaType.equals("application/json") || mediaType.endsWith("+json");
    if (!json || lower.contains("charset=")) {
      return type;
    }
    return mediaType + ";charset=UTF-8";
  }

  /** Keeps the security headers through {@code reset()} and the API rules for later writers. */
  static final class HeaderKeepingResponse extends HttpServletResponseWrapper {

    private final boolean api;
    private final boolean secure;

    HeaderKeepingResponse(HttpServletResponse response, boolean api, boolean secure) {
      super(response);
      this.api = api;
      this.secure = secure;
    }

    static HeaderKeepingResponse of(HttpServletRequest request, HttpServletResponse response) {
      return new HeaderKeepingResponse(response, isApi(request), request.isSecure());
    }

    void applyHeaders() {
      HttpServletResponse response = (HttpServletResponse) getResponse();
      SecurityHeaders.applyStatic(response);
      if (secure) {
        response.setHeader(SecurityHeaders.HSTS, SecurityHeaders.HSTS_VALUE);
      }
      if (api) {
        response.setHeader(CACHE_CONTROL, SecurityHeaders.API_CACHE_CONTROL);
      }
    }

    @Override
    public void reset() {
      super.reset();
      applyHeaders();
    }

    @Override
    public void setHeader(String name, String value) {
      if (api && CACHE_CONTROL.equalsIgnoreCase(name)) {
        super.setHeader(CACHE_CONTROL, SecurityHeaders.API_CACHE_CONTROL);
      } else if (api && CONTENT_TYPE.equalsIgnoreCase(name)) {
        setContentType(value);
      } else {
        super.setHeader(name, value);
      }
    }

    @Override
    public void addHeader(String name, String value) {
      if (api && CACHE_CONTROL.equalsIgnoreCase(name)) {
        super.setHeader(CACHE_CONTROL, SecurityHeaders.API_CACHE_CONTROL);
      } else if (api && CONTENT_TYPE.equalsIgnoreCase(name)) {
        setContentType(value);
      } else {
        super.addHeader(name, value);
      }
    }

    @Override
    public void setContentType(String type) {
      super.setContentType(api ? jsonWithCharset(type) : type);
    }
  }
}
