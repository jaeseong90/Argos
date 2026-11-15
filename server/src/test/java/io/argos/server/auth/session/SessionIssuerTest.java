/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.AuthTestProperties;
import io.argos.server.auth.Role;
import io.argos.server.auth.token.AccessTokenService;
import io.argos.server.auth.token.JwtKey;
import io.argos.server.auth.token.JwtKeyRing;
import io.argos.server.auth.token.SecureTokens;
import io.argos.server.auth.users.Usernames;
import io.argos.server.config.ArgosProperties;
import io.argos.server.storage.postgres.auth.UserRecord;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** What a new session row holds (16 §7.1, §7.3, §7.5, ADR-0010 C-117). */
@Tag("req:SEC-42")
class SessionIssuerTest {

  private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");

  private static SessionIssuer issuer(boolean kiosk) {
    ArgosProperties properties =
        AuthTestProperties.of(Map.of("argos.security.kiosk.enabled", Boolean.toString(kiosk)));
    JwtKeyRing ring = new JwtKeyRing();
    ring.install(JwtKey.generate(), null);
    return new SessionIssuer(
        properties,
        new AccessTokenService(ring, Clock.systemUTC(), properties),
        new SessionCookies(properties));
  }

  private static UserRecord user(Role role, boolean kioskAllowed, List<String> cidrs) {
    return new UserRecord(
        UUID.randomUUID(),
        "someone",
        "$2a$12$hash",
        role,
        true,
        false,
        0,
        null,
        0,
        kioskAllowed,
        cidrs,
        Set.of(),
        NOW,
        NOW);
  }

  private static MockHttpServletRequest from(String address) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
    request.setRemoteAddr(address);
    return request;
  }

  @Test
  @DisplayName(
      "T-SEC-42a a login: a new family, the hash of the token only, idle 8 h, absolute 24 h, the"
          + " full client address (C-117)")
  void ordinaryLogin() {
    SessionIssuer.Pending p =
        issuer(false).newLogin(user(Role.VIEWER, false, List.of()), from("198.51.100.7"), NOW);
    assertThat(p.row().familyId()).isEqualTo(p.row().id());
    assertThat(p.row().id().version()).isEqualTo(4);
    assertThat(p.row().refreshHash())
        .isEqualTo(SecureTokens.sha256Hex(p.refreshToken()))
        .hasSize(64);
    assertThat(p.refreshToken()).hasSize(43);
    assertThat(p.row().idleExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(8)));
    assertThat(p.row().absoluteExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
    assertThat(p.row().expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(8)));
    assertThat(p.row().ip()).isEqualTo("198.51.100.7");
    assertThat(p.row().kiosk()).isFalse();
    assertThat(p.toString()).doesNotContain(p.refreshToken()).doesNotContain(p.row().refreshHash());
  }

  @Test
  @DisplayName("T-SEC-42a kiosk needs the switch, the approval, the viewer role and the address")
  void kioskConditions() {
    List<String> ranges = List.of("10.0.0.0/8", "not-a-range", "2001:db8::/32");
    SessionIssuer on = issuer(true);
    SessionIssuer off = issuer(false);
    UserRecord approved = user(Role.VIEWER, true, ranges);
    assertThat(on.kioskApplies(approved, "10.1.2.3")).isTrue();
    assertThat(on.kioskApplies(approved, "2001:db8::1")).isTrue();
    assertThat(on.kioskApplies(approved, "11.1.2.3")).isFalse();
    assertThat(on.kioskApplies(approved, null)).isFalse();
    assertThat(off.kioskApplies(approved, "10.1.2.3")).isFalse();
    assertThat(on.kioskApplies(user(Role.VIEWER, false, ranges), "10.1.2.3")).isFalse();
    assertThat(on.kioskApplies(user(Role.OPERATOR, true, ranges), "10.1.2.3")).isFalse();
    assertThat(on.kioskApplies(user(Role.ADMIN, true, ranges), "10.1.2.3")).isFalse();

    SessionIssuer.Pending kiosk = on.newLogin(approved, from("10.1.2.3"), NOW);
    assertThat(kiosk.row().kiosk()).isTrue();
    assertThat(kiosk.row().idleExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
    assertThat(kiosk.row().absoluteExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
    assertThat(kiosk.idleLifetime()).isEqualTo(Duration.ofHours(24));
  }

  @Test
  @DisplayName(
      "T-SEC-42a User-Agent is cut at 256 characters (C-117), without control characters; an odd"
          + " client address is not stored")
  void userAgentAndAddress() {
    MockHttpServletRequest request = from("127.0.0.1");
    assertThat(SessionIssuer.userAgent(request)).isNull();
    request.addHeader("User-Agent", "a".repeat(300));
    assertThat(SessionIssuer.userAgent(request)).hasSize(256);
    MockHttpServletRequest control = from("127.0.0.1");
    control.addHeader("User-Agent", "a\u0000b\nc");
    assertThat(SessionIssuer.userAgent(control)).isEqualTo("a b c");
    assertThat(SessionIssuer.clientAddress(from("not an address"))).isEmpty();
    assertThat(SessionIssuer.clientAddress(from("[::1]"))).isPresent();
  }

  @Test
  @DisplayName("T-SEC-42a a user name: letters, digits, . _ @ + -, at most 64 code points")
  void usernames() {
    assertThat(Usernames.valid("ops.team-1@example.test")).isTrue();
    assertThat(Usernames.valid("운영자")).isTrue();
    assertThat(Usernames.valid("x".repeat(64))).isTrue();
    assertThat(Usernames.valid("x".repeat(65))).isFalse();
    for (String bad : new String[] {null, "", " x", "a b", "<b>", "-x", "a\nb", "a/b"}) {
      assertThat(Usernames.valid(bad)).as(String.valueOf(bad)).isFalse();
    }
  }
}
