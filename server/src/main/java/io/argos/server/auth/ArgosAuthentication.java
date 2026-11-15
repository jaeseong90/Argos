/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The Spring Security {@code Authentication} of an {@link ArgosPrincipal}. The access-token filter
 * (WO-24) puts it into the security context with {@link #authenticate(ArgosPrincipal)}; Spring's
 * anonymous filter then leaves the request alone, and {@link ArgosPrincipal#current()} finds it.
 *
 * <p>Authorities ({@code ROLE_<role>} and the permissions) are informational for Spring components;
 * Argos authorises with {@link RequiresRole} only. No credentials are kept.
 */
public final class ArgosAuthentication extends AbstractAuthenticationToken {

  private static final long serialVersionUID = 1L;

  /** Request-scoped, never serialised. */
  private final transient ArgosPrincipal principal;

  /** An authenticated token of {@code principal}. */
  public ArgosAuthentication(ArgosPrincipal principal) {
    super(authorities(Objects.requireNonNull(principal, "principal")));
    this.principal = principal;
    super.setAuthenticated(true);
  }

  /**
   * Makes {@code principal} the authenticated user of the current request (a new security context
   * for the request thread; the stateless chain never stores it beyond the request).
   */
  public static void authenticate(ArgosPrincipal principal) {
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(new ArgosAuthentication(principal));
    SecurityContextHolder.setContext(context);
  }

  @Override
  public ArgosPrincipal getPrincipal() {
    return principal;
  }

  /** None: the access token is not kept after verification. */
  @Override
  public Object getCredentials() {
    return null;
  }

  @Override
  public String getName() {
    return principal.username();
  }

  /** The authenticated flag cannot be changed after construction. */
  @Override
  public void setAuthenticated(boolean authenticated) {
    if (authenticated != isAuthenticated()) {
      throw new IllegalArgumentException(
          "the authenticated flag of an ArgosAuthentication is fixed");
    }
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ArgosAuthentication that
        && super.equals(that)
        && Objects.equals(principal, that.principal);
  }

  @Override
  public int hashCode() {
    return 31 * super.hashCode() + Objects.hashCode(principal);
  }

  @Override
  public String toString() {
    return "ArgosAuthentication[userId="
        + principal.userId()
        + ", sid="
        + principal.sid()
        + ", role="
        + principal.role()
        + "]";
  }

  private static List<GrantedAuthority> authorities(ArgosPrincipal principal) {
    List<GrantedAuthority> authorities = new ArrayList<>();
    authorities.add(new SimpleGrantedAuthority("ROLE_" + principal.role().name()));
    principal.permissions().stream()
        .sorted()
        .map(SimpleGrantedAuthority::new)
        .forEach(authorities::add);
    return authorities;
  }
}
