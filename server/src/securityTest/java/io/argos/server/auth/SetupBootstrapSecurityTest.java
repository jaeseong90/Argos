/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.auth.api.SetupController;
import io.argos.server.auth.token.PrivateFiles;
import io.argos.server.bootstrap.StartupAbortedException;
import io.argos.server.testing.ServerTestEnvironment;
import io.argos.server.testing.ServerTestEnvironment.PgAccount;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.AuditLog;
import io.argos.server.testing.auth.MutableClock;
import io.argos.server.testing.auth.SessionTable;
import io.argos.server.testing.auth.TestUsers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-SEC-47 and T-SV-048 (16 §7.8, 14 §4.1, 17 §6.4, SEC-47, ADR-0010 C-106 (1)) on a server whose
 * database has no administrator, so each test has its own environment: the token's whole life (log
 * line, file, 403s, one use, 404 afterwards, expiry) and the unattended path.
 */
@Tag("req:SEC-47")
class SetupBootstrapSecurityTest {

  private static final String STATUS = "/api/v1/setup/status";
  private static final String ADMIN = "/api/v1/setup/admin";

  private static Path tokenFile(ServerTestEnvironment env) {
    return Paths.get(env.springProperties().get("argos.auth.setup-token-file"));
  }

  private static String readToken(ServerTestEnvironment env) throws IOException {
    return Files.readString(tokenFile(env), StandardCharsets.UTF_8).strip();
  }

  private static Map<String, Object> body(String token, String user, String password, String mode) {
    Map<String, Object> m = new LinkedHashMap<>();
    if (token != null) {
      m.put("setup_token", token);
    }
    m.put("username", user);
    m.put("password", password);
    m.put("ip_mode", mode);
    return m;
  }

  @Test
  @DisplayName(
      "T-SEC-47/T-SV-048 no administrator: a token in one log line and a file without group or"
          + " others access; without it, with another or wrong input nothing happens; with it the"
          + " administrator and a session exist, the file is gone, reuse is 403, everything else 404")
  void wizard() throws Exception {
    try (ServerTestEnvironment env = ServerTestEnvironment.start();
        ArgosTestServer server = ArgosTestServer.start(env)) {
      ApiTestClient client = server.client();

      ApiResponse status = client.get(STATUS);
      assertThat(status.status()).isEqualTo(200);
      assertThat(status.json().path("data").path("needs_setup").asBoolean()).isTrue();
      assertThat(status.json().path("data").path("server_version").isString()).isTrue();

      String token = readToken(env);
      assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]{43}");
      // The exact rw------- is SetupTokenFileTest's (skipped where there are no POSIX permissions).
      assertThat(PrivateFiles.loosePermissions(tokenFile(env)))
          .as("no group or others access to the token file")
          .isEmpty();
      List<String> lines =
          Arrays.stream(AuthSuite.serverLog().split("\n")).filter(l -> l.contains(token)).toList();
      assertThat(lines).as("exactly one log line holds the token").hasSize(1);
      assertThat(lines.get(0)).contains("Setup token");

      String admin = "first-admin";
      String password = TestUsers.newPassword(admin);

      assertThat(client.post(ADMIN, body(null, admin, password, "masked")).status())
          .as("no token")
          .isEqualTo(403);
      ApiResponse wrong =
          client.post(ADMIN, body(TestUsers.newPassword(null), admin, password, "masked"));
      assertThat(wrong.status()).as("wrong token").isEqualTo(403);
      assertThat(wrong.errorCode()).contains("forbidden");
      assertThat(client.post(ADMIN, null).status()).as("no body").isEqualTo(403);
      assertThat(status(client)).as("nothing happened").isTrue();

      assertThat(client.post(ADMIN, body(token, admin, "short", "masked")).status())
          .as("password policy")
          .isEqualTo(422);
      assertThat(client.post(ADMIN, body(token, admin, password, null)).status())
          .as("the IP mode must be chosen (SEC-31)")
          .isEqualTo(400);
      assertThat(client.post(ADMIN, body(token, admin, password, "everything")).status())
          .as("not an IP mode")
          .isEqualTo(400);
      assertThat(client.post(ADMIN, body(token, "bad name", password, "masked")).status())
          .as("not a user name")
          .isEqualTo(400);
      assertThat(tokenFile(env)).as("a refused request does not use the token up").exists();

      ApiResponse created =
          client.send(
              "POST",
              ADMIN,
              body(null, admin, password, "off"),
              Map.of(SetupController.SETUP_TOKEN_HEADER, token));
      assertThat(created.status()).as(created.toString()).isEqualTo(201);
      assertThat(created.json().path("data").path("user").path("username").asString())
          .isEqualTo(admin);
      assertThat(created.json().path("data").path("user").path("roles").get(0).asString())
          .isEqualTo("admin");
      assertThat(created.body()).doesNotContain(token).doesNotContain(password);
      assertThat(created.setCookie("argos_at")).isPresent();
      assertThat(created.setCookie("argos_rt")).isPresent();
      assertThat(created.setCookie("argos_csrf")).isPresent();

      assertThat(tokenFile(env)).as("the file is deleted").doesNotExist();
      assertThat(status(client)).isFalse();
      assertThat(SessionTable.setting(env, "default_ip_mode")).isEqualTo("off");

      ApiResponse me = client.get("/api/v1/auth/me");
      assertThat(me.status()).as("the response carried a login session").isEqualTo(200);
      assertThat(me.json().path("data").path("must_change_password").asBoolean()).isFalse();
      assertThat(client.get("/api/v1/admin/users").status()).isEqualTo(200);

      assertThat(AuditLog.rows(env, "SETUP_COMPLETED"))
          .anyMatch(r -> "success".equals(r.result()) && admin.equals(r.targetName()));
      assertThat(AuditLog.rows(env, "USER_CREATED")).anyMatch(r -> admin.equals(r.targetName()));

      ApiTestClient other = server.client();
      assertThat(other.post(ADMIN, body(token, "second", password, "full")).status())
          .as("the token used again")
          .isEqualTo(403);
      ApiResponse exists = other.post(ADMIN, body(null, "second", password, "full"));
      assertThat(exists.status()).as("administrator exists").isEqualTo(404);
      assertThat(
              other
                  .post(ADMIN, body(TestUsers.newPassword(null), "second", password, "full"))
                  .status())
          .isEqualTo(404);
      assertThat(other.post(ADMIN, null).status()).isEqualTo(404);
      assertThat(other.login("second", password).status()).isEqualTo(401);

      String log = AuthSuite.serverLog();
      assertThat(Arrays.stream(log.split("\n")).filter(l -> l.contains(token))).hasSize(1);
      assertThat(log).doesNotContain(password);
    }
  }

  private static boolean status(ApiTestClient client) {
    return client.get(STATUS).json().path("data").path("needs_setup").asBoolean();
  }

  @Test
  @DisplayName("T-SEC-47/T-SV-048 after 30 minutes the token is 403 and its file is gone")
  void expiry() throws Exception {
    MutableClock clock = new MutableClock();
    try (ServerTestEnvironment env = ServerTestEnvironment.start();
        ArgosTestServer server = ArgosTestServer.start(env, Map.of(), clock.installer())) {
      ApiTestClient client = server.client();
      String token = readToken(env);
      String password = TestUsers.newPassword("first-admin");
      clock.advance(Duration.ofMinutes(30).minusSeconds(1));
      assertThat(client.post(ADMIN, body(token, "first-admin", "short", "masked")).status())
          .as("still valid: the policy answers")
          .isEqualTo(422);
      clock.advance(Duration.ofSeconds(1));
      ApiResponse expired = client.post(ADMIN, body(token, "first-admin", password, "masked"));
      assertThat(expired.status()).isEqualTo(403);
      assertThat(expired.errorCode()).contains("forbidden");
      assertThat(tokenFile(env)).doesNotExist();
      assertThat(status(client)).isTrue();
    }
  }

  @Test
  @DisplayName(
      "T-SEC-47/T-SV-048 unattended: the administrator exists at start with a forced password change,"
          + " the IP mode is stored, no token anywhere, the wizard is 404")
  void unattended() throws Exception {
    try (ServerTestEnvironment env = ServerTestEnvironment.start()) {
      String admin = "ops-admin";
      String password = TestUsers.newPassword(admin);
      Path file = env.secretsDirectory().resolve("admin_password");
      Files.writeString(file, password + "\n", StandardCharsets.UTF_8);
      Map<String, Object> settings =
          Map.of(
              "argos.admin-username",
              admin,
              "argos.admin-password-file",
              file.toString(),
              "argos.privacy.default-ip-mode",
              "off");
      try (ArgosTestServer server = ArgosTestServer.start(env, settings)) {
        ApiTestClient client = server.client();
        assertThat(status(client)).isFalse();
        assertThat(tokenFile(env)).doesNotExist();
        assertThat(
                client
                    .post(ADMIN, body(TestUsers.newPassword(null), "x", password, "full"))
                    .status())
            .isEqualTo(404);
        assertThat(SessionTable.setting(env, "default_ip_mode")).isEqualTo("off");
        assertThat(AuditLog.rows(env, "SETUP_COMPLETED"))
            .anyMatch(r -> "system".equals(r.actorType()) && admin.equals(r.targetName()));

        ApiResponse login = client.login(admin, password);
        assertThat(login.status()).as("the line end of the file is not part of it").isEqualTo(200);
        assertThat(login.json().path("data").path("must_change_password").asBoolean()).isTrue();
        assertThat(client.get("/api/v1/admin/users").errorCode())
            .contains("password_change_required");
      }
      try (ArgosTestServer again = ArgosTestServer.start(env, settings)) {
        assertThat(status(again.client())).as("a restart changes nothing").isFalse();
        assertThat(again.client().login(admin, password).status()).isEqualTo(200);
      }
    }
  }

  @Test
  @DisplayName(
      "T-SEC-47/T-SV-048 unattended with a missing IP mode, a missing file or a weak password stops"
          + " the start and leaves no administrator")
  void unattendedRefusals() throws Exception {
    try (ServerTestEnvironment env = ServerTestEnvironment.start()) {
      Path file = env.secretsDirectory().resolve("admin_password");
      Files.writeString(file, TestUsers.newPassword("ops-admin"), StandardCharsets.UTF_8);
      assertStartRefused(
          env,
          Map.of("argos.admin-username", "ops-admin", "argos.admin-password-file", file.toString()),
          "ARGOS_IP_MODE");
      assertStartRefused(
          env,
          Map.of("argos.admin-username", "ops-admin", "argos.privacy.default-ip-mode", "masked"),
          "argos.admin-password-file");
      Path weak = env.secretsDirectory().resolve("weak_password");
      Files.writeString(weak, "unbelievable", StandardCharsets.UTF_8);
      assertStartRefused(
          env,
          Map.of(
              "argos.admin-password-file",
              weak.toString(),
              "argos.privacy.default-ip-mode",
              "masked"),
          "password policy");
    }
  }

  private static void assertStartRefused(
      ServerTestEnvironment env, Map<String, Object> settings, String message) {
    assertThatThrownBy(() -> ArgosTestServer.start(env, settings))
        .satisfies(
            t -> {
              Throwable c = t;
              while (c != null && !(c instanceof StartupAbortedException)) {
                c = c.getCause();
              }
              assertThat(c).as("the start sequence refused the start").isNotNull();
              assertThat(c.toString() + " " + stackMessages(t)).contains(message);
            });
    try (Connection c = env.connect(PgAccount.APP);
        Statement statement = c.createStatement();
        ResultSet rows = statement.executeQuery("SELECT count(*) FROM argos.users")) {
      rows.next();
      assertThat(rows.getInt(1)).as("no user was created").isZero();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String stackMessages(Throwable t) {
    StringBuilder out = new StringBuilder();
    for (Throwable c = t; c != null; c = c.getCause()) {
      out.append(c.getMessage()).append(' ');
    }
    return out.toString();
  }
}
