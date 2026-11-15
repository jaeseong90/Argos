/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.argos.server.auth.RoleMatrix;
import io.argos.server.bootstrap.StartupAbortedException;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code RoleMatrix} on a started application (12 §7.3, DSN-12-11, T-SEC-49): a handler without
 * {@code @RequiresRole} fails the start, and it is found wherever it is added. The start sequence
 * runs before the embedded server binds its ports (StartupOrderIT), so such a server never answers.
 */
@Tag("req:SEC-49")
@Tag("req:DSN-12-11")
class RoleMatrixContractTest {

  /** Test-only: one handler with a role, two without. */
  @RestController
  public static class MissingRole {

    @GetMapping("/api/v1/test-role-matrix/annotated")
    @RequiresRole(Role.VIEWER)
    public Map<String, Object> annotated() {
      return Map.of();
    }

    @GetMapping("/api/v1/test-role-matrix/forgotten")
    public Map<String, Object> forgotten() {
      return Map.of();
    }

    @GetMapping("/some/other/path")
    public Map<String, Object> outsideApi() {
      return Map.of();
    }
  }

  /** Test-only: the role on the class is enough. */
  @RestController
  @RequiresRole(Role.ADMIN)
  public static class RoleOnClass {

    @GetMapping("/api/v1/test-role-matrix/class-role")
    public Map<String, Object> classRole() {
      return Map.of();
    }
  }

  @Test
  @DisplayName("T-SEC-49 a controller without @RequiresRole fails the start and is named")
  void missingRoleFailsTheStart() {
    assertThatThrownBy(() -> ContractServer.start(MissingRole.class))
        .isInstanceOf(StartupAbortedException.class)
        .hasMessageContaining("role-matrix")
        .hasMessageContaining("2 REST handler(s) have no @RequiresRole")
        .hasMessageContaining(MissingRole.class.getName() + "#forgotten()")
        .hasMessageContaining(MissingRole.class.getName() + "#outsideApi()")
        .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain("#annotated"));
  }

  /**
   * The roles of every overload of {@code method} ({@code class#method}) in {@code table}, whose
   * keys are signatures: a library handler is named without fixing its parameter list here.
   */
  private static Set<String> rolesOf(Map<String, String> table, String method) {
    return table.entrySet().stream()
        .filter(entry -> entry.getKey().startsWith(method + "("))
        .map(Map.Entry::getValue)
        .collect(Collectors.toSet());
  }

  @Test
  @DisplayName(
      "T-SEC-49 a role on the class satisfies the check; every current handler of the server has"
          + " one")
  void everyHandlerHasARole() {
    try (ContractServer server = ContractServer.start(RoleOnClass.class)) {
      RoleMatrix matrix = server.context().getBean(RoleMatrix.class);
      assertThat(matrix.missing()).isEmpty();
      Map<String, String> table = matrix.table();
      assertThat(table).containsEntry(RoleOnClass.class.getName() + "#classRole()", "admin");
      String springdoc = "org.springdoc.webmvc.api.OpenApiWebMvcResource#";
      assertThat(rolesOf(table, springdoc + "openapiJson"))
          .as("springdoc's JSON document handler is served to viewers")
          .containsExactly("viewer");
      assertThat(rolesOf(table, springdoc + "openapiYaml"))
          .as("springdoc's YAML document handler is served to viewers")
          .containsExactly("viewer");
      assertThat(table).doesNotContainValue("(none)");
    }
  }
}
