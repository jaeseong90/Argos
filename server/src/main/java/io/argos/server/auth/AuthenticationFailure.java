/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import io.argos.server.api.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;

/**
 * Why a request that presented credentials is nevertheless anonymous (14 §2.2, §2.6): the
 * access-token filter records {@code session_expired} or {@code session_revoked} here instead of
 * rejecting the request, because {@code PUBLIC} endpoints (refresh, login, CSRF token) must still
 * work with an expired or revoked access cookie. {@link RoleAuthorizationInterceptor} answers a
 * non-public endpoint with this code (401), or with {@code unauthenticated} when nothing was
 * recorded, before it looks at any role: a session revoked by a role or permission change or a
 * deactivation is 401 {@code session_revoked} on every protected endpoint, whatever role that
 * endpoint needs (14 §2.2, §4.14, 16 §7.4); the new role applies from the next login on.
 */
public final class AuthenticationFailure {

  /** Request attribute holding the {@link ErrorCode}. */
  public static final String ATTRIBUTE = AuthenticationFailure.class.getName();

  /** The codes that may be recorded (the 401 codes of 14 §2.6). */
  public static final Set<ErrorCode> CODES =
      Set.of(ErrorCode.UNAUTHENTICATED, ErrorCode.SESSION_EXPIRED, ErrorCode.SESSION_REVOKED);

  private AuthenticationFailure() {}

  /**
   * Records why the request's credentials were not accepted.
   *
   * @throws IllegalArgumentException for a code that is not a 401 code
   */
  public static void record(HttpServletRequest request, ErrorCode code) {
    if (!CODES.contains(code)) {
      throw new IllegalArgumentException("not an authentication failure code: " + code);
    }
    request.setAttribute(ATTRIBUTE, code);
  }

  /** The recorded code, or {@code unauthenticated}. */
  public static ErrorCode of(HttpServletRequest request) {
    return request.getAttribute(ATTRIBUTE) instanceof ErrorCode code
        ? code
        : ErrorCode.UNAUTHENTICATED;
  }
}
