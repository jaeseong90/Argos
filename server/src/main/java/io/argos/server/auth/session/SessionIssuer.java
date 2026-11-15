/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.session;

import io.argos.server.auth.Role;
import io.argos.server.auth.Sensitive;
import io.argos.server.auth.token.AccessTokenService;
import io.argos.server.auth.token.SecureTokens;
import io.argos.server.config.ArgosProperties;
import io.argos.server.config.CidrRange;
import io.argos.server.storage.postgres.auth.NewSession;
import io.argos.server.storage.postgres.auth.SessionRow;
import io.argos.server.storage.postgres.auth.UserRecord;
import io.argos.server.web.security.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Makes new session rows and hands out their tokens (16 §7.1-§7.3, §7.5): the row is prepared and
 * inserted inside the caller's transaction ({@link #newLogin}, {@link #rotation}), the JWT and the
 * cookies follow after the commit ({@link #deliver}). The database keeps the SHA-256 of the refresh
 * token only.
 *
 * <p>Lifetimes: idle {@code auth.refresh-idle-ttl} (8 h) and absolute {@code auth.refresh-max-ttl}
 * (24 h). A kiosk session has {@code security.kiosk.idle-ttl} (24 h) and {@code
 * security.kiosk.max-ttl} (30 days): only with {@code security.kiosk.enabled}, an account with
 * {@code kiosk_allowed}, the viewer role (16 §7.5, SEC-42) and a client address inside the
 * account's {@code kiosk_ip_allowlist}. A session that does not meet all of them is an ordinary
 * one.
 */
@Component
public class SessionIssuer {

  /** Stored length of {@code user_sessions.user_agent} (ADR-0010 C-117). */
  static final int MAX_USER_AGENT_LENGTH = 256;

  private static final Logger LOG = LoggerFactory.getLogger(SessionIssuer.class);

  /**
   * A session row with its refresh token, not delivered yet. {@link #toString()} hides the token.
   *
   * @param row the row to insert
   * @param refreshToken the token whose hash the row holds
   * @param idleLifetime the idle lifetime of the session
   */
  public record Pending(NewSession row, @Sensitive String refreshToken, Duration idleLifetime) {

    public Pending {
      Objects.requireNonNull(row, "row");
      Objects.requireNonNull(refreshToken, "refreshToken");
    }

    @Override
    public String toString() {
      return "Pending[" + row + "]";
    }
  }

  private final ArgosProperties.Auth auth;
  private final ArgosProperties.Security.Kiosk kiosk;
  private final AccessTokenService tokens;
  private final SessionCookies cookies;

  public SessionIssuer(
      ArgosProperties properties, AccessTokenService tokens, SessionCookies cookies) {
    this.auth = properties.auth();
    this.kiosk = properties.security().kiosk();
    this.tokens = tokens;
    this.cookies = cookies;
  }

  /** A row for a new login of {@code user}: a new family. */
  public Pending newLogin(UserRecord user, HttpServletRequest request, Instant now) {
    String ip = clientAddress(request).orElse(null);
    boolean isKiosk = kioskApplies(user, ip);
    Duration idle = isKiosk ? kiosk.idleTtl() : auth.refreshIdleTtl();
    Duration absolute = isKiosk ? kiosk.maxTtl() : auth.refreshMaxTtl();
    String refresh = SecureTokens.newToken();
    UUID id = UUID.randomUUID();
    return new Pending(
        new NewSession(
            id,
            user.id(),
            id,
            SecureTokens.sha256Hex(refresh),
            now,
            now.plus(idle),
            now.plus(absolute),
            null,
            userAgent(request),
            ip,
            isKiosk),
        refresh,
        idle);
  }

  /** The row that replaces {@code old} at a refresh: same family and absolute expiry. */
  public Pending rotation(SessionRow old, HttpServletRequest request, Instant now) {
    Duration idle = old.kiosk() ? kiosk.idleTtl() : auth.refreshIdleTtl();
    String refresh = SecureTokens.newToken();
    return new Pending(
        new NewSession(
            UUID.randomUUID(),
            old.userId(),
            old.familyId(),
            SecureTokens.sha256Hex(refresh),
            now,
            now.plus(idle),
            old.absoluteExpiresAt(),
            old.id(),
            userAgent(request),
            clientAddress(request).orElse(null),
            old.kiosk()),
        refresh,
        idle);
  }

  /**
   * Sets the cookies of an inserted row: a new access JWT ({@code sid} = the row), the refresh
   * token and, for a new session, a new CSRF token (16 §7.6: no token survives a login, SEC-43).
   *
   * @param withCsrf whether to issue a new CSRF cookie
   */
  public void deliver(Pending pending, UserRecord user, boolean withCsrf, HttpServletResponse res) {
    NewSession row = pending.row();
    Role role = user.role();
    cookies.setAccess(res, tokens.issue(row.userId(), row.id(), role, user.effectivePermissions()));
    cookies.setRefresh(res, pending.refreshToken(), pending.idleLifetime());
    if (withCsrf) {
      cookies.setCsrf(res, SecureTokens.newToken());
    }
  }

  /** Whether a login of {@code user} from {@code ip} gets a kiosk session (16 §7.5). */
  boolean kioskApplies(UserRecord user, String ip) {
    if (!kiosk.enabled() || !user.kioskAllowed() || user.role() != Role.VIEWER || ip == null) {
      return false;
    }
    Optional<InetAddress> address = CidrRange.literal(ip);
    if (address.isEmpty()) {
      return false;
    }
    for (String cidr : user.kioskCidrs()) {
      try {
        if (CidrRange.parse(cidr).contains(address.get())) {
          return true;
        }
      } catch (IllegalArgumentException wrongValue) {
        // Written by PATCH /admin/users (validated) or by hand: a wrong value allows nothing.
        LOG.debug("Skipping a kiosk address range that does not parse");
      }
    }
    return false;
  }

  /** The client address as an IP literal; the full address is a security record (C-117). */
  static Optional<String> clientAddress(HttpServletRequest request) {
    return CidrRange.literal(RateLimiter.clientAddress(request)).map(InetAddress::getHostAddress);
  }

  /** The client's {@code User-Agent}, cut to 256 characters, without control characters. */
  static String userAgent(HttpServletRequest request) {
    String value = request.getHeader(HttpHeaders.USER_AGENT);
    if (value == null || value.isEmpty()) {
      return null;
    }
    String cut =
        value.length() > MAX_USER_AGENT_LENGTH ? value.substring(0, MAX_USER_AGENT_LENGTH) : value;
    StringBuilder out = new StringBuilder(cut.length());
    cut.chars().forEach(c -> out.append(Character.isISOControl(c) ? ' ' : (char) c));
    return out.toString();
  }
}
