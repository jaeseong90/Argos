/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.audit;

import io.argos.server.auth.ArgosPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpHeaders;

/**
 * Who did it (16 §11.2: {@code actor_type}, {@code actor_id}, {@code actor_name}, {@code ip},
 * {@code user_agent}).
 *
 * <p>The client address is the request's remote address as the servlet container reports it (with
 * Spring's forwarded-header handling if the operator configured it). It is stored in full: the
 * address of an Argos user is a security record, not collected application data (ADR-0010 C-117).
 *
 * @param type user, agent or system
 * @param id user id, token prefix or {@code system}; null for an unknown user (failed login)
 * @param name user name (for a failed login: the name that was tried, cut to 64 characters)
 * @param ip client address, or null
 * @param userAgent client {@code User-Agent}, cut to 256 characters later, or null
 */
public record Actor(Type type, String id, String name, String ip, String userAgent) {

  /** Values of {@code audit_logs.actor_type} (16 §11.2). */
  public enum Type {
    /** An Argos user (also an unknown name in a failed login). */
    USER,
    /** An agent token. */
    AGENT,
    /** The server itself (start-up, jobs, unattended setup). */
    SYSTEM;

    /** The column value: {@code user}, {@code agent}, {@code system}. */
    public String value() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  public Actor {
    Objects.requireNonNull(type, "type");
  }

  /** The server itself. */
  public static Actor system() {
    return new Actor(Type.SYSTEM, "system", "system", null, null);
  }

  /** The authenticated user of a request. */
  public static Actor user(ArgosPrincipal principal, HttpServletRequest request) {
    return user(principal.userId(), principal.username(), request);
  }

  /** A known user, with the client of {@code request} (may be null outside a request). */
  public static Actor user(UUID userId, String username, HttpServletRequest request) {
    return new Actor(
        Type.USER,
        userId == null ? null : userId.toString(),
        username,
        request == null ? null : request.getRemoteAddr(),
        request == null ? null : request.getHeader(HttpHeaders.USER_AGENT));
  }

  /** An unauthenticated client that named {@code attemptedName} (e.g. a failed login). */
  public static Actor anonymous(String attemptedName, HttpServletRequest request) {
    return user(null, attemptedName, request);
  }
}
