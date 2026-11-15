/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.AuditLog;
import io.argos.server.testing.auth.SessionTable;
import io.argos.server.testing.auth.TestUsers;
import io.argos.server.testing.auth.TestUsers.TestUser;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-SEC-48 and T-SV-048 (16 §7.9, SEC-48) through the API on the real server: every password
 * boundary, the hash in the database, the lockout progression against the injected clock, what a
 * failed login gives away (nothing), and the forced password change.
 */
@Tag("req:SEC-48")
class PasswordSecurityTest {

  private static final String PASSWORD = "/api/v1/auth/password";
  private static final String ME = "/api/v1/auth/me";
  private static final String USERS = "/api/v1/admin/users";
  private static final String LETTERS = "qwzxkvjpqwzxkvjp";

  private final ServerTestEnvironment env = AuthSuite.environment();
  private final ArgosTestServer server = AuthSuite.server();
  private final TestUsers users = AuthSuite.users();

  private static String letters(int n) {
    return LETTERS.repeat(n / LETTERS.length() + 1).substring(0, n);
  }

  private ApiTestClient login(TestUser user) {
    ApiTestClient client = server.client();
    assertThat(client.login(user.username(), user.password()).status()).isEqualTo(200);
    return client;
  }

  private static ApiResponse change(ApiTestClient client, String current, String next) {
    return client.put(PASSWORD, Map.of("current", current, "new", next));
  }

  private static void assertPolicy(ApiResponse response, String field, String reason) {
    assertThat(response.status()).as(response.toString()).isEqualTo(422);
    assertThat(response.errorCode()).contains("password_policy");
    assertThat(response.json().path("error").path("details").toString())
        .contains("\"field\":\"" + field + "\"")
        .contains("\"reason\":\"" + reason + "\"");
  }

  private ApiResponse wrongLogin(String username) {
    return server.client().login(username, TestUsers.newPassword(username));
  }

  /** {@code times} failed logins that answer 401, then one that answers 423. */
  private void failUntilLocked(String username) {
    for (int i = 1; i <= 9; i++) {
      ApiResponse response = wrongLogin(username);
      assertThat(response.status()).as("failure " + i).isEqualTo(401);
      assertThat(response.errorCode()).contains("unauthenticated");
    }
    ApiResponse tenth = wrongLogin(username);
    assertThat(tenth.status()).as("failure 10").isEqualTo(423);
    assertThat(tenth.errorCode()).contains("account_locked");
  }

  @Test
  @DisplayName(
      "T-SEC-48/T-SV-048 11 characters refused, 12 allowed, 128 allowed, 129 refused, each with its"
          + " reason code")
  void boundaries() {
    TestUser user = users.create(Role.VIEWER);
    ApiTestClient client = login(user);
    assertPolicy(change(client, user.password(), letters(11)), "new", "too_short");
    assertPolicy(change(client, user.password(), letters(129)), "new", "too_long");
    String twelve = letters(12);
    assertThat(change(client, user.password(), twelve).status()).isEqualTo(204);
    String longest = letters(128);
    assertThat(change(client, twelve, longest).status()).isEqualTo(204);
    assertThat(server.client().login(user.username(), longest).status()).isEqualTo(200);
    assertThat(server.client().login(user.username(), letters(127) + "!").status()).isEqualTo(401);
  }

  @Test
  @DisplayName(
      "T-SEC-48/T-SV-048 a common password, the user name, argos in any case, the same password:"
          + " refused")
  void contents() {
    TestUser user = users.create(Role.VIEWER);
    ApiTestClient client = login(user);
    assertPolicy(change(client, user.password(), "unbelievable"), "new", "denylisted");
    assertPolicy(change(client, user.password(), "UNBELIEVABLE"), "new", "denylisted");
    assertPolicy(
        change(client, user.password(), "xx" + user.username().toUpperCase(Locale.ROOT) + "xx"),
        "new",
        "contains_username");
    assertPolicy(
        change(client, user.password(), "myArGoSpassword"), "new", "contains_product_name");
    assertPolicy(change(client, user.password(), user.password()), "new", "same_as_current");
    assertPolicy(change(client, letters(20), letters(21)), "current", "incorrect");
    assertThat(client.put(PASSWORD, Map.of("current", user.password())).status()).isEqualTo(400);
    assertThat(client.get(ME).status()).as("a refused change leaves the session").isEqualTo(200);
  }

  @Test
  @DisplayName("T-SEC-48/T-SV-048 the database holds a bcrypt hash of cost 12: $2a$12$")
  void hashCost() {
    TestUser user = users.create(Role.VIEWER);
    ApiTestClient client = login(user);
    String next = TestUsers.newPassword(user.username());
    assertThat(change(client, user.password(), next).status()).isEqualTo(204);
    String stored = SessionTable.passwordHash(env, user.id());
    assertThat(stored).startsWith("$2a$12$").hasSize(60).doesNotContain(next);
  }

  @Test
  @DisplayName(
      "T-SEC-48/T-SV-048 lockout: 10 failures lock for 15 minutes, then 30, then 60 (maximum); while"
          + " locked the right password is 423 too; a success resets the progression")
  void lockoutProgression() {
    TestUser user = users.create(Role.VIEWER);
    for (long minutes : List.of(15L, 30L, 60L, 60L)) {
      Instant now = AuthSuite.clock().instant();
      failUntilLocked(user.username());
      assertThat(SessionTable.lockedUntil(env, user.id()))
          .as("locked for " + minutes + " minutes")
          .isEqualTo(now.plus(Duration.ofMinutes(minutes)));
      ApiResponse right = server.client().login(user.username(), user.password());
      assertThat(right.status()).isEqualTo(423);
      assertThat(right.errorCode()).contains("account_locked");
      assertThat(right.setCookies()).isEmpty();
      assertThat(right.header("Retry-After")).as("nothing about how long").isEmpty();
      AuthSuite.clock().advance(Duration.ofMinutes(minutes).minusSeconds(1));
      assertThat(server.client().login(user.username(), user.password()).status())
          .as("one second before the end")
          .isEqualTo(423);
      AuthSuite.clock().advance(Duration.ofSeconds(1));
    }
    assertThat(AuditLog.rows(env, "ACCOUNT_LOCKED"))
        .filteredOn(r -> user.id().toString().equals(r.targetId()))
        .hasSize(4);

    assertThat(server.client().login(user.username(), user.password()).status()).isEqualTo(200);
    Instant now = AuthSuite.clock().instant();
    failUntilLocked(user.username());
    assertThat(SessionTable.lockedUntil(env, user.id()))
        .as("a success started the progression over")
        .isEqualTo(now.plus(Duration.ofMinutes(15)));
  }

  @Test
  @DisplayName("T-SEC-48/T-SV-048 a success resets the failure count: 9 + 9 failures do not lock")
  void successResetsCounter() {
    TestUser user = users.create(Role.VIEWER);
    for (int i = 0; i < 9; i++) {
      assertThat(wrongLogin(user.username()).status()).isEqualTo(401);
    }
    assertThat(server.client().login(user.username(), user.password()).status()).isEqualTo(200);
    for (int i = 0; i < 9; i++) {
      assertThat(wrongLogin(user.username()).status()).isEqualTo(401);
    }
    assertThat(server.client().login(user.username(), user.password()).status()).isEqualTo(200);
  }

  @Test
  @DisplayName("T-SEC-48/T-SV-048 an administrator's unlock lifts the lockout and its progression")
  void adminUnlock() {
    TestUser user = users.create(Role.VIEWER);
    failUntilLocked(user.username());
    TestUser admin = users.create(Role.ADMIN);
    ApiTestClient adminClient = login(admin);
    assertThat(adminClient.post(USERS + "/" + user.id() + "/unlock", null).status()).isEqualTo(204);
    assertThat(SessionTable.lockedUntil(env, user.id())).isNull();
    assertThat(AuditLog.rows(env, "ACCOUNT_UNLOCKED"))
        .anyMatch(r -> user.id().toString().equals(r.targetId()));
    assertThat(server.client().login(user.username(), user.password()).status()).isEqualTo(200);

    ApiTestClient viewer = login(users.create(Role.VIEWER));
    assertThat(viewer.post(USERS + "/" + user.id() + "/unlock", null).status()).isEqualTo(403);
  }

  @Test
  @DisplayName(
      "T-SEC-48/T-SV-048 unknown name, wrong password and disabled account answer the same status and"
          + " body; the audit row knows the difference")
  void noEnumerationByAnswer() {
    TestUser known = users.create(Role.VIEWER);
    TestUser disabled = users.create(Role.VIEWER, o -> o.enabled(false));
    String unknownName = TestUsers.newUsername();
    ApiResponse wrong = wrongLogin(known.username());
    ApiResponse unknown = wrongLogin(unknownName);
    ApiResponse off = server.client().login(disabled.username(), disabled.password());
    for (ApiResponse response : List.of(wrong, unknown, off)) {
      assertThat(response.status()).isEqualTo(401);
      assertThat(response.setCookies()).isEmpty();
      assertThat(withoutRequestId(response)).isEqualTo(withoutRequestId(wrong));
    }
    assertThat(wrong.body()).doesNotContain(known.username());
    List<AuditLog.Row> failed = AuditLog.rows(env, "LOGIN_FAILED");
    assertThat(failed)
        .anyMatch(
            r -> "bad_credentials".equals(r.reason()) && known.username().equals(r.actorName()));
    assertThat(failed)
        .anyMatch(r -> "unknown_user".equals(r.reason()) && unknownName.equals(r.actorName()));
    assertThat(failed)
        .anyMatch(
            r ->
                "account_disabled".equals(r.reason()) && disabled.username().equals(r.actorName()));
  }

  private static String withoutRequestId(ApiResponse response) {
    return response.body().replaceAll("\"request_id\":\"[^\"]*\"", "");
  }

  @Test
  @DisplayName(
      "T-SEC-48/T-SV-048 lockout does not tell either: an unknown name also answers 423 at the"
          + " tenth failure")
  void noEnumerationByLockout() {
    String unknownName = TestUsers.newUsername();
    failUntilLocked(unknownName);
    ApiResponse locked = wrongLogin(unknownName);
    TestUser known = users.create(Role.VIEWER);
    failUntilLocked(known.username());
    ApiResponse lockedKnown = wrongLogin(known.username());
    assertThat(locked.status()).isEqualTo(423);
    assertThat(withoutRequestId(locked)).isEqualTo(withoutRequestId(lockedKnown));
  }

  @Test
  @DisplayName(
      "T-SEC-48/T-SV-048 an unknown name costs a bcrypt check like a known one (reference timing on"
          + " a shared machine: within a factor of 3)")
  void noEnumerationByTime() {
    TestUser known = users.create(Role.VIEWER);
    server.client().fetchCsrf();
    long knownMedian = median(() -> wrongLoginOnce(known.username()));
    long unknownMedian = median(() -> wrongLoginOnce(TestUsers.newUsername()));
    assertThat((double) unknownMedian)
        .as("unknown " + unknownMedian + " ms, known " + knownMedian + " ms")
        .isGreaterThan(knownMedian / 3.0)
        .isLessThan(knownMedian * 3.0);
  }

  private void wrongLoginOnce(String username) {
    wrongLogin(username);
  }

  private static long median(Runnable call) {
    List<Long> samples = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      long start = System.nanoTime();
      call.run();
      samples.add((System.nanoTime() - start) / 1_000_000);
    }
    Collections.sort(samples);
    return samples.get(samples.size() / 2);
  }

  @Test
  @DisplayName("T-SEC-48/T-SV-048 login without a name or password is 400, over-long ones are 401")
  void loginBounds() {
    ApiTestClient client = server.client();
    client.fetchCsrf();
    assertThat(client.post(ApiTestClient.LOGIN_PATH, Map.of()).status()).isEqualTo(400);
    assertThat(client.post(ApiTestClient.LOGIN_PATH, null).status()).isEqualTo(400);
    assertThat(client.post(ApiTestClient.LOGIN_PATH, Map.of("username", "x")).status())
        .isEqualTo(400);
    assertThat(client.post(ApiTestClient.LOGIN_PATH, Map.of("password", "x")).status())
        .isEqualTo(400);
    assertThat(client.login("n".repeat(65), letters(20)).status()).isEqualTo(401);
    TestUser known = users.create(Role.VIEWER);
    assertThat(client.login(known.username(), letters(100_000)).status()).isEqualTo(401);
  }

  @Test
  @DisplayName(
      "T-SEC-48/T-SV-048 while the password must change every other API is 403"
          + " password_change_required; me, password and logout work, then everything does")
  void mustChange() {
    TestUser admin = users.create(Role.ADMIN, o -> o.mustChangePassword(true));
    ApiTestClient client = server.client();
    ApiResponse login = client.login(admin.username(), admin.password());
    assertThat(login.status()).isEqualTo(200);
    assertThat(login.json().path("data").path("must_change_password").asBoolean()).isTrue();

    int before = AuditLog.rows(env, "MUST_CHANGE_ENFORCED").size();
    ApiResponse blocked = client.get(USERS);
    assertThat(blocked.status()).isEqualTo(403);
    assertThat(blocked.errorCode()).contains("password_change_required");
    assertThat(client.post(USERS, Map.of()).errorCode()).contains("password_change_required");
    assertThat(client.get("/api/v1/openapi").errorCode()).contains("password_change_required");
    assertThat(AuditLog.rows(env, "MUST_CHANGE_ENFORCED"))
        .as("audited once per session")
        .hasSize(before + 1);

    ApiResponse me = client.get(ME);
    assertThat(me.status()).isEqualTo(200);
    assertThat(me.json().path("data").path("must_change_password").asBoolean()).isTrue();
    assertThat(client.fetchCsrf().status()).isEqualTo(200);
    assertThat(client.post("/api/v1/auth/refresh", null).status()).isEqualTo(200);

    String next = TestUsers.newPassword(admin.username());
    assertThat(change(client, admin.password(), next).status()).isEqualTo(204);
    assertThat(client.get(USERS).status()).isEqualTo(200);
    assertThat(client.get(ME).json().path("data").path("must_change_password").asBoolean())
        .isFalse();

    TestUser leaving = users.create(Role.VIEWER, o -> o.mustChangePassword(true));
    ApiTestClient leavingClient = login(leaving);
    assertThat(leavingClient.post("/api/v1/auth/logout", null).status()).isEqualTo(204);
  }

  @Test
  @DisplayName("T-SEC-48/T-SV-048 guessing the current password through a session also locks")
  void passwordChangeCountsFailures() {
    TestUser user = users.create(Role.VIEWER);
    ApiTestClient client = login(user);
    for (int i = 1; i <= 9; i++) {
      assertPolicy(change(client, letters(13 + i), letters(30)), "current", "incorrect");
    }
    ApiResponse tenth = change(client, letters(40), letters(30));
    assertThat(tenth.status()).isEqualTo(423);
    assertThat(tenth.errorCode()).contains("account_locked");
    assertThat(server.client().login(user.username(), user.password()).status()).isEqualTo(423);
  }
}
