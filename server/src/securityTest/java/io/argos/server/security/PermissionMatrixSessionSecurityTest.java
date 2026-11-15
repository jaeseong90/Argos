/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.Role;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.server.testing.auth.TestUsers;
import io.argos.servertest.security.HandlerReachedMarker;
import io.argos.servertest.security.matrix.OpenApiSpec;
import io.argos.servertest.security.matrix.PermissionMatrix;
import io.argos.servertest.security.matrix.PermissionMatrix.Identity;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-SEC-49 the whole way: the same generated matrix as the contract suite, but on the real server
 * with real users, logins and session cookies, real PostgreSQL and ClickHouse, nothing test-only
 * but the {@link HandlerReachedMarker}. A session can end during the run (the matrix also calls
 * logout), so an unsafe request is followed by a new login. The table goes to {@code
 * server/build/reports/permission-matrix-session.md}. Unlike the contract run, a 5xx in an allowed
 * cell fails here ({@link PermissionMatrix.ServerErrors#FAIL}, 14 §7.1).
 */
@Tag("req:SEC-49")
class PermissionMatrixSessionSecurityTest {

  @Test
  @DisplayName(
      "T-SEC-49 real sessions of every role on every operation: allowed reaches the handler, denied"
          + " is 401 or 403 before it")
  void matrixWithRealSessions() {
    // The users need the migrated schema: the suite's shared server migrates it. Started here too,
    // so the class also runs on its own (--tests), not only after another class of the suite.
    SecuritySuite.server();
    TestUsers users = new TestUsers(SecuritySuite.environment());
    Map<Role, TestUsers.TestUser> accounts = new EnumMap<>(Role.class);
    accounts.put(Role.VIEWER, users.create(Role.VIEWER));
    accounts.put(Role.OPERATOR, users.create(Role.OPERATOR));
    accounts.put(Role.ADMIN, users.create(Role.ADMIN, o -> o.permission(ArgosPrincipal.PII_VIEW)));

    try (ArgosTestServer server =
        ArgosTestServer.start(
            SecuritySuite.environment(),
            Map.of(
                "argos.ratelimit.login-per-ip-per-minute", "1000000",
                "argos.ratelimit.api-per-user-per-second", "1000000",
                "argos.ratelimit.api-burst-per-user", "1000000",
                "argos.ratelimit.export-per-user-per-minute", "1000000",
                "argos.ratelimit.csp-report-per-ip-per-minute", "1000000"),
            app -> app.addPrimarySources(List.of(HandlerReachedMarker.class)))) {
      Map<Identity, ApiTestClient> clients = new EnumMap<>(Identity.class);
      for (Identity identity : Identity.values()) {
        ApiTestClient client = SecuritySuite.client(server, null);
        if (identity.role() != null) {
          login(client, accounts.get(identity.role()));
        }
        clients.put(identity, client);
      }

      ApiResponse document = clients.get(Identity.VIEWER).get("/api/v1/openapi");
      assertThat(document.status()).isEqualTo(200);
      OpenApiSpec spec = OpenApiSpec.parse(document.body());

      PermissionMatrix.Result result =
          PermissionMatrix.run(
              spec,
              (identity, request) -> {
                ApiTestClient client = clients.get(identity);
                ApiResponse response =
                    client.send(request.method(), request.path(), request.body(), Map.of());
                if (identity.role() != null && !request.method().equals("GET")) {
                  login(client, accounts.get(identity.role()));
                }
                return response;
              },
              PermissionMatrix.ownCredentials(),
              // Real handlers and databases: an allowed request that fails with a 5xx is wrong.
              PermissionMatrix.ServerErrors.FAIL);
      result.writeReport(
          Path.of(
              System.getProperty("argos.repositoryRoot", ".."),
              "server",
              "build",
              "reports",
              "permission-matrix-session.md"),
          "Permission matrix (real sessions, security suite)");

      assertThat(result.failures()).isEmpty();
      assertThat(result.rows())
          .as("one row per operation of the served document")
          .hasSameSizeAs(spec.operations());
      assertThat(result.rows().stream().map(PermissionMatrix.Row::role).distinct())
          .as("the current API has public, viewer, operator and admin operations")
          .contains("public", "viewer", "operator", "admin");
    }
  }

  private static void login(ApiTestClient client, TestUsers.TestUser user) {
    client.cookies().clear();
    ApiResponse login = client.login(user.username(), user.password());
    assertThat(login.status()).as("login of " + user).isEqualTo(200);
  }
}
