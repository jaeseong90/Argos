/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.api.openapi.ArgosUntrusted;
import io.argos.server.auth.ArgosPrincipal;
import io.argos.server.auth.Role;
import io.argos.server.session.api.IpMode;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The enums and untrusted markers that ADR-0010 C-191 (1) puts into the OpenAPI document for the
 * bodies of 14 §4.1 and §4.14 stay equal to what the server accepts and writes: the assignable
 * roles, the known permissions and the stored {@code ip_mode} names.
 */
@Tag("req:SEC-60")
class AuthDtosEnumsTest {

  private static final Set<String> ROLES =
      Arrays.stream(Role.values())
          .filter(Role::assignable)
          .map(Role::extensionValue)
          .collect(Collectors.toSet());

  private static final Set<String> IP_MODES =
      Arrays.stream(IpMode.values()).map(IpMode::storedName).collect(Collectors.toSet());

  /** Every record of AuthDtos with a component of this name. */
  private static List<RecordComponent> components(String name) {
    return Arrays.stream(AuthDtos.class.getDeclaredClasses())
        .filter(Class::isRecord)
        .flatMap(type -> Arrays.stream(type.getRecordComponents()))
        .filter(component -> component.getName().equals(name))
        .toList();
  }

  /**
   * The field of a record component: swagger's annotations do not target record components, so Java
   * keeps them on the field (and the accessor) only.
   */
  private static Field field(RecordComponent component) {
    try {
      return component.getDeclaringRecord().getDeclaredField(component.getName());
    } catch (NoSuchFieldException e) {
      throw new AssertionError(e);
    }
  }

  private static Set<String> itemValues(RecordComponent component) {
    ArraySchema array = field(component).getAnnotation(ArraySchema.class);
    assertThat(array)
        .as(component.getDeclaringRecord().getSimpleName() + "." + component.getName())
        .isNotNull();
    return Set.of(array.schema().allowableValues());
  }

  @Test
  @DisplayName(
      "T-API-001a every roles and permissions array of the auth bodies is an enum of the assignable"
          + " roles and the known permissions (C-191 (1))")
  void rolesAndPermissions() {
    List<RecordComponent> roles = components("roles");
    assertThat(roles)
        .extracting(c -> c.getDeclaringRecord().getSimpleName())
        .containsExactlyInAnyOrder(
            "UserView",
            "LoginResponse",
            "MeResponse",
            "AdminUserView",
            "CreateUserRequest",
            "PatchUserRequest");
    roles.forEach(component -> assertThat(itemValues(component)).isEqualTo(ROLES));

    List<RecordComponent> permissions = components("permissions");
    assertThat(permissions)
        .extracting(c -> c.getDeclaringRecord().getSimpleName())
        .containsExactlyInAnyOrder("UserView", "MeResponse", "AdminUserView", "PatchUserRequest");
    permissions.forEach(
        component -> assertThat(itemValues(component)).isEqualTo(ArgosPrincipal.KNOWN_PERMISSIONS));

    // A required request array stays required.
    RecordComponent createRoles =
        roles.stream()
            .filter(c -> c.getDeclaringRecord().getSimpleName().equals("CreateUserRequest"))
            .findFirst()
            .orElseThrow();
    assertThat(field(createRoles).getAnnotation(ArraySchema.class).arraySchema().requiredMode())
        .isEqualTo(Schema.RequiredMode.REQUIRED);
  }

  @Test
  @DisplayName(
      "T-API-001a ip_mode of POST /setup/admin is the enum of the stored modes (C-191 (1))")
  void ipMode() {
    List<RecordComponent> ipMode = components("ipMode");
    assertThat(ipMode).hasSize(1);
    Schema schema = field(ipMode.get(0)).getAnnotation(Schema.class);
    assertThat(Set.of(schema.allowableValues())).isEqualTo(IP_MODES);
    assertThat(schema.requiredMode()).isEqualTo(Schema.RequiredMode.REQUIRED);
  }

  @Test
  @DisplayName(
      "T-API-001a the user names of UserView and AdminUserView are x-argos-untrusted (16 §9.1,"
          + " C-191 (1))")
  void untrustedUsernames() {
    List<RecordComponent> usernames =
        components("username").stream()
            .filter(c -> c.getDeclaringRecord().getSimpleName().endsWith("View"))
            .toList();
    assertThat(usernames)
        .extracting(c -> c.getDeclaringRecord().getSimpleName())
        .containsExactlyInAnyOrder("UserView", "AdminUserView");
    usernames.forEach(
        component -> assertThat(field(component).getAnnotation(ArgosUntrusted.class)).isNotNull());
  }
}
