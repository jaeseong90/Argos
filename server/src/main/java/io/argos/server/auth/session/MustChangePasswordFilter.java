/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.session;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.argos.server.api.error.ErrorBodyWriter;
import io.argos.server.api.error.ErrorCode;
import io.argos.server.audit.Actor;
import io.argos.server.audit.AuditEvent;
import io.argos.server.audit.AuditResult;
import io.argos.server.audit.AuditService;
import io.argos.server.audit.Target;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.web.security.ApiFilter;
import io.argos.server.web.security.FilterOrder;
import io.argos.server.web.security.IpRateLimitFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * While {@code users.must_change_password} is set, a session may only change the password, ask who
 * it is and log out; everything else is 403 {@code password_change_required} ({@link
 * FilterOrder#MUST_CHANGE_PASSWORD}, 16 §7.9, SEC-48). Exempt are:
 *
 * <ul>
 *   <li>{@code PUT /auth/password}, {@code GET /auth/me}, {@code POST /auth/logout};
 *   <li>the endpoints that do not use the session at all: {@code GET /auth/csrf}, {@code POST
 *       /auth/refresh}, {@code POST /auth/login}, CSP reports. They give a session nothing, and the
 *       password change itself needs a CSRF token and, after 15 minutes, a refresh.
 * </ul>
 *
 * Paths are compared as received, so a differently encoded path never gains an exemption. Refused
 * requests are audited ({@code MUST_CHANGE_ENFORCED}) at most once per session and {@value
 * #AUDIT_INTERVAL_MINUTES} minutes: a UI page that loads many resources writes one row, not many.
 */
@Component
public class MustChangePasswordFilter implements ApiFilter {

  /** Password change (14 §4.1). */
  public static final String PASSWORD_PATH = "/api/v1/auth/password";

  /** Who am I (14 §4.1). */
  public static final String ME_PATH = "/api/v1/auth/me";

  /** Logout (14 §4.1). */
  public static final String LOGOUT_PATH = "/api/v1/auth/logout";

  /** CSRF token (14 §4.1). */
  public static final String CSRF_PATH = "/api/v1/auth/csrf";

  private static final Set<String> ALLOWED =
      Set.of(
          "PUT " + PASSWORD_PATH,
          "GET " + ME_PATH,
          "POST " + LOGOUT_PATH,
          "GET " + CSRF_PATH,
          "POST " + IpRateLimitFilter.REFRESH_PATH,
          "POST " + IpRateLimitFilter.LOGIN_PATH,
          "POST " + IpRateLimitFilter.CSP_REPORT_PATH);

  static final int AUDIT_INTERVAL_MINUTES = 10;

  private final AuditService audit;
  private final Cache<UUID, Boolean> audited =
      Caffeine.newBuilder()
          .expireAfterWrite(Duration.ofMinutes(AUDIT_INTERVAL_MINUTES))
          .maximumSize(10_000)
          .build();

  public MustChangePasswordFilter(AuditService audit) {
    this.audit = audit;
  }

  @Override
  public int position() {
    return FilterOrder.MUST_CHANGE_PASSWORD;
  }

  @Override
  public void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    Optional<ArgosPrincipal> principal = ArgosPrincipal.current();
    if (principal.isPresent()
        && principal.get().mustChangePassword()
        && !ALLOWED.contains(request.getMethod() + " " + request.getRequestURI())) {
      ArgosPrincipal p = principal.get();
      if (audited.asMap().putIfAbsent(p.sid(), Boolean.TRUE) == null) {
        audit.record(
            AuditEvent.MUST_CHANGE_ENFORCED,
            Actor.user(p, request),
            Target.session(p.sid()),
            AuditResult.rejected("password_change_required"));
      }
      ErrorBodyWriter.write(request, response, ErrorCode.PASSWORD_CHANGE_REQUIRED);
      return;
    }
    chain.doFilter(request, response);
  }
}
