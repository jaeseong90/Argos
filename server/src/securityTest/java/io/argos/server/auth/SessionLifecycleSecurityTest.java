/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.session.RevocationReason;
import io.argos.server.auth.session.SessionCleanupJob;
import io.argos.server.auth.session.SessionCookies;
import io.argos.server.auth.session.SessionRevokedEvent;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.AuditLog;
import io.argos.server.testing.auth.SessionTable;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.auth.TestUsers.TestUser;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-SEC-41, T-SEC-42 and the server hook of T-SEC-44 (16 §7.2-§7.5, 12 §7.2, SEC-41, SEC-42,
 * SEC-44) on the real server with real PostgreSQL: access expiry against the injected clock,
 * refresh rotation and reuse detection, every invalidation hook, the session limit, kiosk sessions.
 * Closing WebSockets on the event is WO-22's.
 */
@Tag("req:SEC-41")
@Tag("req:SEC-42")
class SessionLifecycleSecurityTest {

  private static final String ME = "/api/v1/auth/me";
  private static final String USERS = "/api/v1/admin/users";
  private static final String LOGOUT = "/api/v1/auth/logout";
  private static final String REFRESH = SessionCookies.REFRESH_PATH;

  private final ServerTestEnvironment env = AuthSuite.environment();
  private final ArgosTestServer server = AuthSuite.server();
  private final TestUsers users = AuthSuite.users();

  private ApiTestClient login(TestUser user) {
    ApiTestClient client = server.client();
    assertThat(client.login(user.username(), user.password()).status()).isEqualTo(200);
    return client;
  }

  private static String access(ApiTestClient client) {
    return client.cookie(SessionCookies.ACCESS_COOKIE).orElseThrow();
  }

  private static String refreshToken(ApiTestClient client) {
    return client.cookie(SessionCookies.REFRESH_COOKIE).orElseThrow();
  }

  private static void assertRefused(ApiResponse response, String code) {
    assertThat(response.status()).as(response.toString()).isEqualTo(401);
    assertThat(response.errorCode()).contains(code);
  }

  /** The events published since {@code from} (an earlier {@code events().size()}). */
  private static List<SessionRevokedEvent> eventsSince(int from) {
    List<SessionRevokedEvent> all = AuthSuite.events();
    return new ArrayList<>(all.subList(from, all.size()));
  }

  private TestUser admin() {
    return users.create(Role.ADMIN);
  }

  // --- T-SEC-41
  // ------------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "T-SEC-41 an access token ends after 15 minutes (injected clock): 401 session_expired")
  void accessExpiry() {
    TestUser user = users.create(Role.VIEWER);
    ApiTestClient client = login(user);
    assertThat(client.get(ME).status()).isEqualTo(200);
    AuthSuite.clock().advance(Duration.ofSeconds(899));
    assertThat(client.get(ME).status()).isEqualTo(200);
    AuthSuite.clock().advance(Duration.ofSeconds(1));
    assertRefused(client.get(ME), "session_expired");
    assertThat(client.post(REFRESH, null).status()).isEqualTo(200);
    assertThat(client.get(ME).status()).isEqualTo(200);
  }

  @Test
  @DisplayName(
      "T-SEC-41 a refresh rotates: new row in the same family, rotated_from, rotated_at, new sid in"
          + " the JWT, absolute expiry kept, idle expiry moved")
  void rotation() {
    TestUser user = users.create(Role.VIEWER);
    ApiTestClient client = login(user);
    String oldAccess = access(client);
    String oldRefresh = refreshToken(client);
    UUID oldSid = SessionTable.sid(oldAccess);
    SessionTable.Row before = SessionTable.byId(env, oldSid);
    assertThat(before.familyId()).isEqualTo(oldSid);
    assertThat(before.absoluteExpiresAt().minus(Duration.ofHours(24))).isEqualTo(before.issuedAt());
    assertThat(before.idleExpiresAt().minus(Duration.ofHours(8))).isEqualTo(before.issuedAt());

    AuthSuite.clock().advance(Duration.ofMinutes(10));
    ApiResponse response = client.post(REFRESH, null);
    assertThat(response.status()).isEqualTo(200);
    String newAccess = access(client);
    UUID newSid = SessionTable.sid(newAccess);
    assertThat(newSid).isNotEqualTo(oldSid);
    assertThat(refreshToken(client)).isNotEqualTo(oldRefresh);

    SessionTable.Row oldRow = SessionTable.byId(env, oldSid);
    SessionTable.Row newRow = SessionTable.byId(env, newSid);
    assertThat(oldRow.rotatedAt()).isNotNull();
    assertThat(oldRow.revokedAt()).isNull();
    assertThat(newRow.familyId()).isEqualTo(before.familyId());
    assertThat(newRow.rotatedFrom()).isEqualTo(oldSid);
    assertThat(newRow.rotatedAt()).isNull();
    assertThat(newRow.absoluteExpiresAt()).isEqualTo(before.absoluteExpiresAt());
    assertThat(newRow.idleExpiresAt()).isAfter(before.idleExpiresAt());
    assertThat(newRow.refreshHash()).isEqualTo(SessionTable.sha256Hex(refreshToken(client)));
    assertThat(client.get(ME).status()).isEqualTo(200);

    // The access token of the rotated row stays valid until it expires: it was not revoked.
    ApiTestClient oldTab = server.client();
    oldTab.cookies().put(SessionCookies.ACCESS_COOKIE, oldAccess, "/");
    assertThat(oldTab.get(ME).status()).isEqualTo(200);
  }

  /** A refresh with {@code token} from a client that holds nothing else (another tab, a thief). */
  private ApiResponse refreshWith(String token) {
    ApiTestClient tab = server.client();
    tab.fetchCsrf();
    tab.cookies().put(SessionCookies.REFRESH_COOKIE, token, REFRESH);
    return tab.post(REFRESH, null);
  }

  @Test
  @DisplayName(
      "T-SEC-41 a rotated refresh token used again revokes every session of the user, not only its"
          + " family (SEC-41, 14 §2.2): two independent logins both die, 401 session_revoked, one"
          + " SESSION_REUSE_DETECTED row, one event per session")
  void reuse() {
    TestUser user = users.create(Role.VIEWER);
    ApiTestClient other = login(user);
    ApiTestClient client = login(user);
    UUID otherSid = SessionTable.sid(access(other));
    String stolen = refreshToken(client);
    assertThat(client.post(REFRESH, null).status()).isEqualTo(200);
    assertThat(client.post(REFRESH, null).status()).isEqualTo(200);
    UUID family = SessionTable.byId(env, SessionTable.sid(access(client))).familyId();
    UUID newest = SessionTable.sid(access(client));
    assertThat(SessionTable.byId(env, otherSid).familyId())
        .as("two independent sessions")
        .isNotEqualTo(family);
    int events = AuthSuite.events().size();
    int audits = AuditLog.rows(env, "SESSION_REUSE_DETECTED").size();

    ApiResponse replay = refreshWith(stolen);
    assertRefused(replay, "session_revoked");
    assertThat(replay.setCookie(SessionCookies.ACCESS_COOKIE).orElseThrow().deletes()).isTrue();
    assertThat(replay.setCookie(SessionCookies.REFRESH_COOKIE).orElseThrow().deletes()).isTrue();

    assertThat(SessionTable.ofFamily(env, family))
        .hasSize(3)
        .allSatisfy(
            row -> {
              assertThat(row.revokedAt()).isNotNull();
              assertThat(row.revokedReason()).isEqualTo("refresh_reuse");
            });
    assertThat(SessionTable.byId(env, otherSid).revokedReason())
        .as("the other login of the user is revoked too")
        .isEqualTo("refresh_reuse");
    assertRefused(client.get(ME), "session_revoked");
    assertRefused(client.post(REFRESH, null), "session_revoked");
    assertRefused(other.get(ME), "session_revoked");
    assertRefused(other.post(REFRESH, null), "session_revoked");

    List<SessionRevokedEvent> published = eventsSince(events);
    assertThat(published)
        .extracting(SessionRevokedEvent::sid)
        .containsExactlyInAnyOrder(newest, otherSid);
    assertThat(published)
        .allSatisfy(
            e -> {
              assertThat(e.userId()).isEqualTo(user.id());
              assertThat(e.reason()).isEqualTo(RevocationReason.REFRESH_REUSE);
            });
    assertThat(published)
        .filteredOn(e -> e.sid().equals(newest))
        .singleElement()
        .satisfies(e -> assertThat(e.sessionIds()).hasSize(3).contains(newest));
    assertThat(published)
        .filteredOn(e -> e.sid().equals(otherSid))
        .singleElement()
        .satisfies(e -> assertThat(e.sessionIds()).containsExactly(otherSid));

    List<AuditLog.Row> detected = AuditLog.rows(env, "SESSION_REUSE_DETECTED");
    assertThat(detected)
        .as("audited once; the later refreshes of the dead sessions are no reuse")
        .hasSize(audits + 1);
    assertThat(detected.get(audits).actorId()).isEqualTo(user.id().toString());
    assertThat(detected.get(audits).result()).isEqualTo("rejected");
    assertThat(detected.get(audits).reason()).isEqualTo("refresh_reuse");
  }

  @Test
  @DisplayName(
      "T-SEC-41 the refresh token of a session that is over (logout, a rotated token of a session"
          + " logged out since, role change, expiry) is 401 session_revoked and no reuse: no"
          + " SESSION_REUSE_DETECTED, nothing more revoked")
  void revokedTokenIsNoReuse() {
    int audits = AuditLog.rows(env, "SESSION_REUSE_DETECTED").size();

    // Logout: the user's other session lives on.
    TestUser user = users.create(Role.VIEWER);
    ApiTestClient other = login(user);
    ApiTestClient client = login(user);
    String rotated = refreshToken(client);
    assertThat(client.post(REFRESH, null).status()).isEqualTo(200);
    String loggedOut = refreshToken(client);
    assertThat(client.post(LOGOUT, null).status()).isEqualTo(204);
    int events = AuthSuite.events().size();
    assertRefused(refreshWith(loggedOut), "session_revoked");
    assertRefused(refreshWith(rotated), "session_revoked");
    assertThat(eventsSince(events)).as("nothing more revoked").isEmpty();
    assertThat(other.get(ME).status()).as("the other session lives on").isEqualTo(200);

    // Role change.
    TestUser demoted = users.create(Role.OPERATOR);
    ApiTestClient demotedClient = login(demoted);
    String demotedToken = refreshToken(demotedClient);
    ApiTestClient admin = login(admin());
    assertThat(admin.patch(USERS + "/" + demoted.id(), Map.of("roles", List.of("viewer"))).status())
        .isEqualTo(200);
    events = AuthSuite.events().size();
    assertRefused(refreshWith(demotedToken), "session_revoked");
    assertThat(eventsSince(events)).isEmpty();

    // Expiry: the refresh after 8 idle hours revokes (expired); the same token again is no reuse.
    TestUser idle = users.create(Role.VIEWER);
    ApiTestClient idleClient = login(idle);
    String idleToken = refreshToken(idleClient);
    AuthSuite.clock().advance(Duration.ofHours(8));
    assertRefused(idleClient.post(REFRESH, null), "session_expired");
    events = AuthSuite.events().size();
    assertRefused(refreshWith(idleToken), "session_revoked");
    assertThat(eventsSince(events)).isEmpty();

    assertThat(AuditLog.rows(env, "SESSION_REUSE_DETECTED"))
        .as("none of these is a reuse")
        .hasSize(audits);
  }

  @Test
  @DisplayName(
      "T-SEC-41 a refresh after 8 idle hours or 24 hours in all: revoked, 401 session_expired")
  void refreshExpiry() {
    TestUser idle = users.create(Role.VIEWER);
    ApiTestClient idleClient = login(idle);
    UUID idleSid = SessionTable.sid(access(idleClient));
    AuthSuite.clock().advance(Duration.ofHours(8));
    assertRefused(idleClient.post(REFRESH, null), "session_expired");
    assertThat(SessionTable.byId(env, idleSid).revokedReason()).isEqualTo("expired");

    TestUser busy = users.create(Role.VIEWER);
    ApiTestClient busyClient = login(busy);
    for (int i = 0; i < 3; i++) {
      AuthSuite.clock().advance(Duration.ofHours(7));
      assertThat(busyClient.post(REFRESH, null).status()).isEqualTo(200);
    }
    AuthSuite.clock().advance(Duration.ofHours(3));
    assertRefused(busyClient.post(REFRESH, null), "session_expired");
    assertThat(SessionTable.ofUser(env, busy.id()))
        .filteredOn(row -> row.rotatedAt() == null)
        .hasSize(1)
        .allSatisfy(row -> assertThat(row.revokedReason()).isEqualTo("expired"));
  }

  @Test
  @DisplayName("T-SEC-41 no refresh cookie, or one the server never issued: 401 unauthenticated")
  void refreshWithoutToken() {
    ApiTestClient client = server.client();
    client.fetchCsrf();
    assertRefused(client.post(REFRESH, null), "unauthenticated");
    client.cookies().put(SessionCookies.REFRESH_COOKIE, "x".repeat(43), REFRESH);
    assertRefused(client.post(REFRESH, null), "unauthenticated");
    client.cookies().put(SessionCookies.REFRESH_COOKIE, "short", REFRESH);
    assertRefused(client.post(REFRESH, null), "unauthenticated");
  }

  @Test
  @DisplayName("T-SEC-41 a forged or malformed access cookie is unauthenticated, public paths work")
  void forgedAccessCookie() {
    ApiTestClient client = server.client();
    client.cookies().put(SessionCookies.ACCESS_COOKIE, "abc.def.ghi", "/");
    assertRefused(client.get(ME), "unauthenticated");
    assertThat(client.fetchCsrf().status()).isEqualTo(200);
    assertThat(client.get("/api/v1/setup/status").status()).isEqualTo(200);
  }

  // --- T-SEC-42
  // ------------------------------------------------------------------------------------

  @Test
  @DisplayName("T-SEC-42 after logout the same access and refresh tokens are 401 session_revoked")
  void logout() {
    TestUser user = users.create(Role.VIEWER);
    ApiTestClient client = login(user);
    String oldAccess = access(client);
    String oldRefresh = refreshToken(client);
    UUID sid = SessionTable.sid(oldAccess);
    int events = AuthSuite.events().size();

    assertThat(client.post(LOGOUT, null).status()).isEqualTo(204);

    assertThat(SessionTable.byId(env, sid).revokedReason()).isEqualTo("logout");
    assertRefused(client.get(ME), "unauthenticated");
    client.fetchCsrf();
    client.cookies().put(SessionCookies.ACCESS_COOKIE, oldAccess, "/");
    client.cookies().put(SessionCookies.REFRESH_COOKIE, oldRefresh, REFRESH);
    assertRefused(client.get(ME), "session_revoked");
    assertRefused(client.post(REFRESH, null), "session_revoked");

    List<SessionRevokedEvent> published = eventsSince(events);
    assertThat(published).hasSize(1);
    assertThat(published.get(0).sid()).isEqualTo(sid);
    assertThat(published.get(0).reason()).isEqualTo(RevocationReason.LOGOUT);
    assertThat(AuditLog.rows(env, "LOGOUT")).anyMatch(r -> sid.toString().equals(r.targetId()));
    assertThat(AuditLog.rows(env, "SESSION_REVOKED"))
        .anyMatch(r -> sid.toString().equals(r.targetId()) && "logout".equals(r.reason()));
  }

  @Test
  @DisplayName(
      "T-SEC-42 role downgrade: every session of the user is revoked and the next request with the"
          + " old cookies is 401 session_revoked on every endpoint, reached by the new role or not;"
          + " after a new login the new role applies: 403 where it does not reach")
  void roleDowngrade() {
    TestUser target = users.create(Role.ADMIN);
    ApiTestClient targetClient = login(target);
    ApiTestClient secondTab = login(target);
    assertThat(targetClient.get(USERS).status()).isEqualTo(200);
    UUID sid = SessionTable.sid(access(targetClient));
    UUID secondSid = SessionTable.sid(access(secondTab));
    ApiTestClient admin = login(admin());
    int events = AuthSuite.events().size();

    ApiResponse patch = admin.patch(USERS + "/" + target.id(), Map.of("roles", List.of("viewer")));
    assertThat(patch.status()).isEqualTo(200);

    ApiResponse next = targetClient.get(USERS);
    assertRefused(next, "session_revoked");
    assertThat(next.status())
        .as("the next request, without waiting for the cache, where the new role does not reach")
        .isEqualTo(401);
    assertRefused(targetClient.get(ME), "session_revoked");
    assertRefused(targetClient.post(REFRESH, null), "session_revoked");
    assertRefused(secondTab.get(USERS), "session_revoked");
    assertRefused(secondTab.get(ME), "session_revoked");

    assertThat(SessionTable.byId(env, sid).revokedReason()).isEqualTo("role_changed");
    assertThat(SessionTable.byId(env, secondSid).revokedReason()).isEqualTo("role_changed");
    List<SessionRevokedEvent> published = eventsSince(events);
    assertThat(published)
        .extracting(SessionRevokedEvent::sid)
        .containsExactlyInAnyOrder(sid, secondSid);
    assertThat(published)
        .allSatisfy(e -> assertThat(e.reason()).isEqualTo(RevocationReason.ROLE_CHANGED));

    ApiTestClient again = login(target);
    assertThat(again.get(ME).json().path("data").path("roles").get(0).asString())
        .isEqualTo("viewer");
    ApiResponse forbidden = again.get(USERS);
    assertThat(forbidden.status()).as("after the new login: the new role").isEqualTo(403);
    assertThat(forbidden.errorCode()).contains("forbidden");
    assertThat(SessionTable.claims(access(again)).path("role").asString()).isEqualTo("viewer");
  }

  @Test
  @DisplayName("T-SEC-42 the role comes from the record, not from the JWT claim (12 §7.2)")
  void roleFromRecord() throws SQLException {
    TestUser target = users.create(Role.VIEWER);
    ApiTestClient client = login(target);
    assertThat(client.get(USERS).status()).isEqualTo(403);
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement update =
            c.prepareStatement("UPDATE argos.users SET role = 'ADMIN' WHERE id = ?")) {
      update.setObject(1, target.id());
      update.executeUpdate();
    }
    Instant deadline = Instant.now().plusSeconds(8);
    int status = 0;
    while (Instant.now().isBefore(deadline) && status != 200) {
      status = client.get(USERS).status();
    }
    assertThat(status).as("within the 5 s of the session cache").isEqualTo(200);
    assertThat(SessionTable.claims(access(client)).path("role").asString()).isEqualTo("viewer");
  }

  @Test
  @DisplayName(
      "T-SEC-42 disabling an account: 401 at the next request on every endpoint, no login, no"
          + " refresh")
  void disable() {
    TestUser target = users.create(Role.VIEWER);
    ApiTestClient targetClient = login(target);
    UUID sid = SessionTable.sid(access(targetClient));
    ApiTestClient admin = login(admin());
    int events = AuthSuite.events().size();

    assertThat(admin.patch(USERS + "/" + target.id(), Map.of("enabled", false)).status())
        .isEqualTo(200);

    assertRefused(targetClient.get(USERS), "session_revoked");
    assertRefused(targetClient.get(ME), "session_revoked");
    assertRefused(targetClient.post(REFRESH, null), "session_revoked");
    ApiResponse refused = server.client().login(target.username(), target.password());
    assertRefused(refused, "unauthenticated");
    List<SessionRevokedEvent> published = eventsSince(events);
    assertThat(published).hasSize(1);
    assertThat(published.get(0).sid()).isEqualTo(sid);
    assertThat(published.get(0).reason()).isEqualTo(RevocationReason.ACCOUNT_DISABLED);
    assertThat(AuditLog.rows(env, "USER_DISABLED"))
        .anyMatch(r -> target.id().toString().equals(r.targetId()));
  }

  @Test
  @DisplayName(
      "T-SEC-42 the sixth session revokes the oldest (session_limit), exactly one event, five stay")
  void sessionLimit() {
    TestUser user = users.create(Role.VIEWER);
    List<ApiTestClient> browsers = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      browsers.add(login(user));
      AuthSuite.clock().advance(Duration.ofSeconds(1));
    }
    // A rotation must not make the oldest login look new.
    assertThat(browsers.get(0).post(REFRESH, null).status()).isEqualTo(200);
    UUID oldestSid = SessionTable.sid(access(browsers.get(0)));
    UUID oldestFamily = SessionTable.byId(env, oldestSid).familyId();
    int events = AuthSuite.events().size();

    ApiTestClient sixth = login(user);

    assertRefused(browsers.get(0).get(ME), "session_revoked");
    for (int i = 1; i < 5; i++) {
      assertThat(browsers.get(i).get(ME).status()).as("browser " + i).isEqualTo(200);
    }
    assertThat(sixth.get(ME).status()).isEqualTo(200);
    assertThat(SessionTable.ofFamily(env, oldestFamily))
        .allSatisfy(row -> assertThat(row.revokedReason()).isEqualTo("session_limit"));
    List<SessionRevokedEvent> published = eventsSince(events);
    assertThat(published).hasSize(1);
    assertThat(published.get(0).sid()).isEqualTo(oldestSid);
    assertThat(published.get(0).reason()).isEqualTo(RevocationReason.SESSION_LIMIT);
  }

  @Test
  @DisplayName(
      "T-SEC-42 kiosk: viewer + approval + allowed CIDR gets idle 24 h, absolute 30 days and an"
          + " audit row; a foreign CIDR or another role gets an ordinary session")
  void kiosk() {
    TestUser kiosk = users.create(Role.VIEWER, o -> o.kiosk("127.0.0.0/8"));
    ApiTestClient client = login(kiosk);
    SessionTable.Row row = SessionTable.byId(env, SessionTable.sid(access(client)));
    assertThat(row.kiosk()).isTrue();
    assertThat(row.idleExpiresAt().minus(Duration.ofHours(24))).isEqualTo(row.issuedAt());
    assertThat(row.absoluteExpiresAt().minus(Duration.ofDays(30))).isEqualTo(row.issuedAt());
    assertThat(client.last().setCookie(SessionCookies.REFRESH_COOKIE).orElseThrow().maxAge())
        .contains(86400L);
    assertThat(kioskIssued(kiosk))
        .as("the login that issues the kiosk session is audited")
        .singleElement()
        .satisfies(r -> assertThat(r.targetId()).isEqualTo(row.id().toString()));
    assertThat(client.get(ME).json().path("data").path("kiosk_allowed").asBoolean()).isTrue();

    TestUser foreign = users.create(Role.VIEWER, o -> o.kiosk("10.0.0.0/8"));
    SessionTable.Row foreignRow = SessionTable.byId(env, SessionTable.sid(access(login(foreign))));
    assertThat(foreignRow.kiosk()).isFalse();
    assertThat(foreignRow.absoluteExpiresAt().minus(Duration.ofHours(24)))
        .isEqualTo(foreignRow.issuedAt());

    TestUser operator = users.create(Role.OPERATOR, o -> o.kiosk("127.0.0.0/8"));
    assertThat(SessionTable.byId(env, SessionTable.sid(access(login(operator)))).kiosk()).isFalse();

    TestUser none = users.create(Role.VIEWER);
    assertThat(SessionTable.byId(env, SessionTable.sid(access(login(none)))).kiosk()).isFalse();
  }

  @Test
  @DisplayName(
      "T-SEC-42 kiosk sessions are off unless argos.security.kiosk.enabled; withdrawing the approval"
          + " ends the kiosk sessions")
  void kioskSwitch() {
    TestUser kiosk = users.create(Role.VIEWER, o -> o.kiosk("127.0.0.0/8"));
    try (ArgosTestServer off =
        ArgosTestServer.start(env, Map.of("argos.ratelimit.login-per-ip-per-minute", "1000"))) {
      ApiTestClient client = off.client();
      assertThat(client.login(kiosk.username(), kiosk.password()).status()).isEqualTo(200);
      assertThat(SessionTable.byId(env, SessionTable.sid(access(client))).kiosk()).isFalse();
    }
    ApiTestClient kioskClient = login(kiosk);
    UUID sid = SessionTable.sid(access(kioskClient));
    assertThat(SessionTable.byId(env, sid).kiosk()).isTrue();
    int events = AuthSuite.events().size();
    ApiTestClient admin = login(admin());
    assertThat(
            admin
                .patch(
                    USERS + "/" + kiosk.id(),
                    Map.of("kiosk_session", Map.of("allowed", false, "ip_cidrs", List.of())))
                .status())
        .isEqualTo(200);
    assertRefused(kioskClient.get(ME), "session_revoked");
    List<SessionRevokedEvent> published = eventsSince(events);
    assertThat(published).hasSize(1);
    assertThat(published.get(0).reason()).isEqualTo(RevocationReason.ADMIN_TERMINATED);
  }

  /** The {@code KIOSK_SESSION_ISSUED} audit rows of a user. */
  private List<AuditLog.Row> kioskIssued(TestUser user) {
    return AuditLog.rows(env, "KIOSK_SESSION_ISSUED").stream()
        .filter(r -> user.id().toString().equals(r.actorId()))
        .toList();
  }

  @Test
  @DisplayName(
      "T-SEC-42 a kiosk session refreshed outside its allowed ranges is revoked: no 30 days for a"
          + " stolen token; KIOSK_SESSION_ISSUED is written by the issuing login only, not by a"
          + " refresh")
  void kioskRefreshOutsideRanges() {
    TestUser kiosk = users.create(Role.VIEWER, o -> o.kiosk("127.0.0.0/8"));
    ApiTestClient client = login(kiosk);
    UUID sid = SessionTable.sid(access(client));
    assertThat(kioskIssued(kiosk))
        .as("the login that issues the kiosk session is audited")
        .singleElement()
        .satisfies(r -> assertThat(r.targetId()).isEqualTo(sid.toString()));
    assertThat(client.post(REFRESH, null).status()).isEqualTo(200);
    UUID rotated = SessionTable.sid(access(client));
    assertThat(SessionTable.byId(env, rotated).kiosk()).isTrue();
    assertThat(kioskIssued(kiosk))
        .as("the refresh re-checks the conditions and writes no KIOSK_SESSION_ISSUED")
        .hasSize(1)
        .noneMatch(r -> "refresh".equals(r.reason()));
    ApiTestClient admin = login(admin());
    assertThat(
            admin
                .patch(
                    USERS + "/" + kiosk.id(),
                    Map.of(
                        "kiosk_session",
                        Map.of("allowed", true, "ip_cidrs", List.of("10.0.0.0/8"))))
                .status())
        .isEqualTo(200);
    int events = AuthSuite.events().size();
    assertRefused(client.post(REFRESH, null), "session_revoked");
    assertThat(SessionTable.byId(env, sid).revokedReason()).isEqualTo("kiosk_not_allowed");
    assertThat(SessionTable.byId(env, rotated).revokedReason()).isEqualTo("kiosk_not_allowed");
    List<SessionRevokedEvent> published = eventsSince(events);
    assertThat(published).hasSize(1);
    assertThat(published.get(0).reason()).isEqualTo(RevocationReason.KIOSK_NOT_ALLOWED);
    assertThat(kioskIssued(kiosk)).hasSize(1);
  }

  // --- T-SEC-44 server hook, the remaining invalidation hooks of 16 §7.4
  // ---------------------------

  @Test
  @DisplayName(
      "T-SEC-44 an administrator ends a user's sessions: one event per session, with sid and reason")
  void adminTermination() {
    TestUser target = users.create(Role.VIEWER);
    ApiTestClient one = login(target);
    ApiTestClient two = login(target);
    UUID sidOne = SessionTable.sid(access(one));
    UUID sidTwo = SessionTable.sid(access(two));
    ApiTestClient admin = login(admin());
    int events = AuthSuite.events().size();

    assertThat(admin.post(USERS + "/" + target.id() + "/sessions/revoke", null).status())
        .isEqualTo(204);

    assertRefused(one.get(ME), "session_revoked");
    assertRefused(two.get(ME), "session_revoked");
    List<SessionRevokedEvent> published = eventsSince(events);
    assertThat(published)
        .extracting(SessionRevokedEvent::sid)
        .containsExactlyInAnyOrder(sidOne, sidTwo);
    assertThat(published)
        .allSatisfy(
            e -> {
              assertThat(e.reason()).isEqualTo(RevocationReason.ADMIN_TERMINATED);
              assertThat(e.userId()).isEqualTo(target.id());
              assertThat(e.sessionIds()).containsExactly(e.sid());
            });
    assertThat(AuditLog.rows(env, "SESSION_REVOKED"))
        .anyMatch(
            r -> sidOne.toString().equals(r.targetId()) && "admin_terminated".equals(r.reason()));
    assertThat(admin.post(USERS + "/" + target.id() + "/sessions/revoke", null).status())
        .isEqualTo(204);
    assertThat(eventsSince(events)).as("nothing more to revoke, no more events").hasSize(2);
  }

  @Test
  @DisplayName(
      "T-SEC-44 a password change revokes every session and replaces the caller's: others 401, the"
          + " old token 401, the new cookies work, one event per session")
  void passwordChange() {
    TestUser user = users.create(Role.VIEWER);
    ApiTestClient other = login(user);
    ApiTestClient client = login(user);
    String oldAccess = access(client);
    UUID sidOther = SessionTable.sid(access(other));
    UUID sidOld = SessionTable.sid(oldAccess);
    String oldCsrf = client.cookie(SessionCookies.CSRF_COOKIE).orElseThrow();
    int events = AuthSuite.events().size();

    String next = TestUsers.newPassword(user.username());
    ApiResponse changed =
        client.put("/api/v1/auth/password", Map.of("current", user.password(), "new", next));
    assertThat(changed.status()).isEqualTo(204);
    assertThat(changed.body()).isEmpty();

    assertRefused(other.get(ME), "session_revoked");
    assertThat(client.get(ME).status()).as("the caller stays logged in").isEqualTo(200);
    assertThat(access(client)).isNotEqualTo(oldAccess);
    assertThat(client.cookie(SessionCookies.CSRF_COOKIE).orElseThrow()).isNotEqualTo(oldCsrf);
    ApiTestClient oldTab = server.client();
    oldTab.cookies().put(SessionCookies.ACCESS_COOKIE, oldAccess, "/");
    assertRefused(oldTab.get(ME), "session_revoked");

    assertThat(eventsSince(events))
        .extracting(SessionRevokedEvent::sid)
        .containsExactlyInAnyOrder(sidOther, sidOld);
    assertThat(eventsSince(events))
        .allSatisfy(e -> assertThat(e.reason()).isEqualTo(RevocationReason.PASSWORD_CHANGED));
    assertThat(AuditLog.rows(env, "PASSWORD_CHANGED"))
        .anyMatch(r -> user.id().toString().equals(r.targetId()) && "success".equals(r.result()));
    assertRefused(server.client().login(user.username(), user.password()), "unauthenticated");
    assertThat(server.client().login(user.username(), next).status()).isEqualTo(200);
  }

  @Test
  @DisplayName(
      "T-SEC-44 an administrator's password reset ends the sessions and forces a change at the next"
          + " login")
  void adminReset() {
    TestUser target = users.create(Role.VIEWER);
    ApiTestClient targetClient = login(target);
    UUID sid = SessionTable.sid(access(targetClient));
    ApiTestClient admin = login(admin());
    int events = AuthSuite.events().size();
    String temporary = TestUsers.newPassword(target.username());

    assertThat(
            admin
                .post(USERS + "/" + target.id() + "/password/reset", Map.of("password", temporary))
                .status())
        .isEqualTo(204);

    assertRefused(targetClient.get(ME), "session_revoked");
    List<SessionRevokedEvent> published = eventsSince(events);
    assertThat(published).hasSize(1);
    assertThat(published.get(0).sid()).isEqualTo(sid);
    assertThat(published.get(0).reason()).isEqualTo(RevocationReason.PASSWORD_CHANGED);
    assertThat(AuditLog.rows(env, "PASSWORD_RESET"))
        .anyMatch(r -> target.id().toString().equals(r.targetId()));
    ApiTestClient again = server.client();
    ApiResponse login = again.login(target.username(), temporary);
    assertThat(login.status()).isEqualTo(200);
    assertThat(login.json().path("data").path("must_change_password").asBoolean()).isTrue();
    assertThat(again.get(USERS).errorCode()).contains("password_change_required");
  }

  @Test
  @DisplayName(
      "T-SEC-42 a revocation written to the database elsewhere is seen within the 5 s cache")
  void cacheTtl() throws SQLException {
    TestUser user = users.create(Role.VIEWER);
    ApiTestClient client = login(user);
    assertThat(client.get(ME).status()).isEqualTo(200);
    try (Connection c = env.connect(PgAccount.APP);
        PreparedStatement update =
            c.prepareStatement(
                "UPDATE argos.user_sessions SET revoked_at = now(), revoked_reason = 'logout'"
                    + " WHERE user_id = ?")) {
      update.setObject(1, user.id());
      update.executeUpdate();
    }
    Instant deadline = Instant.now().plusSeconds(8);
    int status = 200;
    while (Instant.now().isBefore(deadline) && status == 200) {
      status = client.get(ME).status();
    }
    assertThat(status).isEqualTo(401);
  }

  @Test
  @DisplayName("T-SEC-42c session rows are deleted 7 days after they ended (C-117); live rows stay")
  void cleanup() {
    TestUser ended = users.create(Role.VIEWER);
    ApiTestClient endedClient = login(ended);
    UUID endedSid = SessionTable.sid(access(endedClient));
    assertThat(endedClient.post(LOGOUT, null).status()).isEqualTo(204);
    server.bean(SessionCleanupJob.class).run();
    assertThat(SessionTable.byId(env, endedSid)).as("kept while recent").isNotNull();

    AuthSuite.clock().advance(Duration.ofDays(7).plusSeconds(1));
    TestUser live = users.create(Role.VIEWER);
    UUID liveSid = SessionTable.sid(access(login(live)));
    server.bean(SessionCleanupJob.class).run();
    assertThat(SessionTable.byId(env, endedSid)).as("older than 7 days").isNull();
    assertThat(SessionTable.byId(env, liveSid)).isNotNull();
  }
}
