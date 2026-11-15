/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** T-SEC-50a: the object-level tools of 16 §7.11. */
@Tag("req:SEC-50")
class ObjectAccessPolicyTest {

  private static final UUID OWNER = UUID.randomUUID();

  private static ArgosPrincipal principal(UUID id, Role role) {
    return new ArgosPrincipal(id, "name", UUID.randomUUID(), role, Set.of(), false);
  }

  private static void assertNotOwner(ArgosPrincipal principal, UUID owner) {
    assertThatThrownBy(() -> ObjectAccessPolicy.requireOwnerOrAdmin(principal, owner))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_OWNER));
  }

  @Test
  @DisplayName("T-SEC-50a the owner and an administrator pass; anybody else is 403 not_owner")
  void ownerOrAdmin() {
    for (Role role : new Role[] {Role.VIEWER, Role.OPERATOR, Role.ADMIN}) {
      assertThatCode(() -> ObjectAccessPolicy.requireOwnerOrAdmin(principal(OWNER, role), OWNER))
          .doesNotThrowAnyException();
    }
    assertThatCode(
            () ->
                ObjectAccessPolicy.requireOwnerOrAdmin(
                    principal(UUID.randomUUID(), Role.ADMIN), OWNER))
        .doesNotThrowAnyException();
    assertNotOwner(principal(UUID.randomUUID(), Role.VIEWER), OWNER);
    assertNotOwner(principal(UUID.randomUUID(), Role.OPERATOR), OWNER);
  }

  @Test
  @DisplayName("T-SEC-50a an object without an owner is for administrators only")
  void noOwner() {
    assertNotOwner(principal(UUID.randomUUID(), Role.OPERATOR), null);
    assertThatCode(
            () ->
                ObjectAccessPolicy.requireOwnerOrAdmin(
                    principal(UUID.randomUUID(), Role.ADMIN), null))
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("T-SEC-50a UUIDv4 only: version, variant and the 36-character form are checked")
  void uuidV4() {
    for (int i = 0; i < 100; i++) {
      String generated = UUID.randomUUID().toString();
      assertThat(ObjectAccessPolicy.requireUuidV4(generated, "id"))
          .isEqualTo(UUID.fromString(generated));
      assertThat(ObjectAccessPolicy.isUuidV4(generated.toUpperCase(java.util.Locale.ROOT)))
          .isTrue();
    }
    for (String bad :
        new String[] {
          null,
          "",
          "1",
          "1-1-1-1-1",
          "00000000-0000-0000-0000-000000000000",
          "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
          "6ba7b810-9dad-41d1-c0b4-00c04fd430c8",
          "6ba7b8109dad41d180b400c04fd430c8",
          "6ba7b810-9dad-41d1-80b4-00c04fd430c8 ",
          "{6ba7b810-9dad-41d1-80b4-00c04fd430c8}"
        }) {
      assertThat(ObjectAccessPolicy.isUuidV4(bad)).as(String.valueOf(bad)).isFalse();
      assertThatThrownBy(() -> ObjectAccessPolicy.requireUuidV4(bad, "command_id"))
          .as(String.valueOf(bad))
          .isInstanceOfSatisfying(
              ApiException.class,
              e -> {
                assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
                assertThat(e.details()).hasSize(1);
                assertThat(e.details().get(0).field()).isEqualTo("command_id");
                assertThat(e.details().get(0).reason()).isEqualTo("invalid_format");
              });
    }
  }

  @Test
  @DisplayName("T-SEC-50a AppAccessPolicy allows every application in 1.0, but never no principal")
  void applications() {
    AppAccessPolicy policy = new AppAccessPolicy();
    assertThatCode(() -> policy.check(principal(UUID.randomUUID(), Role.VIEWER), 42L))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> policy.check(null, 42L))
        .isInstanceOfSatisfying(
            ApiException.class,
            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED));
  }
}
