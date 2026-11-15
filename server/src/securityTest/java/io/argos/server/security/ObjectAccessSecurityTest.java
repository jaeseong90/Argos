/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.Role;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.auth.ArgosTestServer;
import io.argos.servertest.security.HeaderAuthentication;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * T-SEC-50a, the tool part of T-SEC-50 (16 §7.11): {@code ObjectAccessPolicy} and {@code
 * AppAccessPolicy} behind a test-only resource on the real server. That another user's command
 * result is 403 is WO-26's part.
 */
@Tag("req:SEC-50")
class ObjectAccessSecurityTest {

  private static final String RESOURCES = "/api/v1/test-edge/resources/";

  private final ArgosTestServer server = SecuritySuite.server();

  @BeforeEach
  void resetLimits() {
    SecuritySuite.resetLimits(server);
  }

  private ApiTestClient as(String role) {
    return SecuritySuite.client(server, role);
  }

  private static String ownedBy(String id, UUID owner) {
    return RESOURCES + id + "?owner=" + owner;
  }

  @Test
  @DisplayName("T-SEC-50a the owner gets the object, an administrator too, anybody else 403")
  void ownerOrAdmin() {
    String id = UUID.randomUUID().toString();
    UUID owner = HeaderAuthentication.userOf(Role.VIEWER);

    assertThat(as("viewer").get(ownedBy(id, owner)).status()).as("owner").isEqualTo(200);
    assertThat(as("admin").get(ownedBy(id, owner)).status()).as("admin").isEqualTo(200);

    ApiResponse other = as("operator").get(ownedBy(id, owner));
    assertThat(other.status()).as("a higher role that is not the owner").isEqualTo(403);
    assertThat(other.errorCode()).contains("not_owner");

    ApiResponse anotherViewer =
        as("viewer")
            .header(HeaderAuthentication.USER_HEADER, UUID.randomUUID().toString())
            .get(ownedBy(id, owner));
    assertThat(anotherViewer.status()).isEqualTo(403);
    assertThat(anotherViewer.errorCode()).contains("not_owner");

    assertThat(server.client().get(ownedBy(id, owner)).status())
        .as("no session: the role check comes first")
        .isEqualTo(401);
  }

  @Test
  @DisplayName(
      "T-SEC-50a a path identifier must be a UUIDv4: other forms are 400 and the value is not"
          + " echoed")
  void uuidV4() {
    UUID owner = HeaderAuthentication.userOf(Role.VIEWER);
    assertThat(as("viewer").get(ownedBy(UUID.randomUUID().toString(), owner)).status())
        .isEqualTo(200);
    for (String bad :
        new String[] {
          "1",
          "1-1-1-1-1",
          "not-a-uuid",
          "00000000-0000-0000-0000-000000000000",
          "6ba7b810-9dad-11d1-80b4-00c04fd430c8", // version 1
          "6ba7b810-9dad-41d1-c0b4-00c04fd430c8", // version 4, wrong variant
          "6ba7b8109dad41d180b400c04fd430c8"
        }) {
      ApiResponse response = as("viewer").get(ownedBy(bad, owner));
      assertThat(response.status()).as(bad).isEqualTo(400);
      assertThat(response.errorCode()).as(bad).contains("invalid_request");
      assertThat(response.json().path("error").path("details").get(0).path("reason").asString())
          .isEqualTo("invalid_format");
      if (bad.length() > 1) { // a single digit is in the request id by chance
        assertThat(response.body()).as(bad).doesNotContain(bad);
      }
    }
  }

  @Test
  @DisplayName("T-SEC-50a AppAccessPolicy is the one hook and allows every application in 1.0")
  void applications() {
    assertThat(as("viewer").get("/api/v1/test-edge/apps/42").status()).isEqualTo(200);
    assertThat(server.client().get("/api/v1/test-edge/apps/42").status()).isEqualTo(401);
  }
}
