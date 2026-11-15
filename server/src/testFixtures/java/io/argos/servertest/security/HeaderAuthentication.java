/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.servertest.security;

import io.argos.server.auth.ArgosAuthentication;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.Role;
import io.argos.server.web.security.ApiFilter;
import io.argos.server.web.security.FilterOrder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/**
 * A test-only way to be a user of a given role: an {@link ApiFilter} that authenticates a request
 * from its {@value #ROLE_HEADER} header, right after the real access-token filter. For the tests of
 * the authorisation layer (role checks, rate limits, headers), which are about what happens after
 * authentication; the session tests of WO-24 and the session run of the permission matrix use real
 * logins.
 *
 * <p>Never part of the server: it lives in the test fixtures, outside {@code io.argos.server} (no
 * component scan finds it), and a test adds it to an application explicitly.
 *
 * <ul>
 *   <li>{@value #ROLE_HEADER}: {@code viewer}, {@code operator} or {@code admin};
 *   <li>{@value #USER_HEADER}: {@code users.id} (default: a fixed id per role);
 *   <li>{@value #SID_HEADER}: session id (default: a fixed id per user).
 * </ul>
 *
 * Administrators get {@code pii.view}, the others no permission.
 */
public class HeaderAuthentication implements ApiFilter {

  /** The role of the request. */
  public static final String ROLE_HEADER = "X-Test-Role";

  /** The user of the request. */
  public static final String USER_HEADER = "X-Test-User";

  /** The session of the request. */
  public static final String SID_HEADER = "X-Test-Sid";

  /** The stable user id of a role. */
  public static UUID userOf(Role role) {
    return UUID.nameUUIDFromBytes(role.name().getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public int position() {
    return FilterOrder.ACCESS_TOKEN_AUTHENTICATION + 50;
  }

  @Override
  public void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    String value = request.getHeader(ROLE_HEADER);
    if (value != null) {
      Role role = Role.ofUserRole(value);
      String user = request.getHeader(USER_HEADER);
      UUID userId = user == null ? userOf(role) : UUID.fromString(user);
      String sid = request.getHeader(SID_HEADER);
      ArgosAuthentication.authenticate(
          new ArgosPrincipal(
              userId,
              "test-" + role.extensionValue(),
              sid == null
                  ? UUID.nameUUIDFromBytes(userId.toString().getBytes(StandardCharsets.UTF_8))
                  : UUID.fromString(sid),
              role,
              role == Role.ADMIN ? Set.of(ArgosPrincipal.PII_VIEW) : Set.of(),
              false));
    }
    chain.doFilter(request, response);
  }
}
