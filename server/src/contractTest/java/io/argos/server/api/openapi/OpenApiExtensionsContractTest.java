/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.audit.AuditEvent;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.argos.servertest.security.matrix.OpenApiSpec;
import io.argos.servertest.security.matrix.PermissionMatrix;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The extensions the {@code OperationCustomizer} emits, on test-only endpoints of every role (14
 * §2.8): the values, the class-level annotation and the method override, and the matrix over all
 * four roles, whatever the server itself has at the moment.
 */
@Tag("req:SEC-49")
class OpenApiExtensionsContractTest {

  /** Test-only: one endpoint per role, with the annotations. Not found by any scan. */
  @RestController
  @RequiresRole(Role.ADMIN)
  public static class Samples {

    @GetMapping("/api/v1/test-samples/public")
    @RequiresRole(Role.PUBLIC)
    public Map<String, Object> open() {
      return Map.of();
    }

    @GetMapping("/api/v1/test-samples/viewer")
    @RequiresRole(Role.VIEWER)
    public Map<String, Object> viewer() {
      return Map.of();
    }

    @PostMapping("/api/v1/test-samples/operator")
    @RequiresRole(Role.OPERATOR)
    @ArgosAudit({AuditEvent.USER_UPDATED, AuditEvent.ROLE_CHANGED})
    public Map<String, Object> operator() {
      return Map.of();
    }

    /** Role from the class. */
    @GetMapping("/api/v1/test-samples/admin")
    @ArgosPii
    public Map<String, Object> admin() {
      return Map.of();
    }

    @PostMapping("/api/v1/test-samples/no-audit")
    @RequiresRole(Role.VIEWER)
    @ArgosAudit(none = "test")
    public Map<String, Object> noAudit() {
      return Map.of();
    }
  }

  private static OpenApiSpec.Operation operation(OpenApiSpec spec, String key) {
    return spec.operations().stream()
        .filter(o -> o.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new AssertionError(key + " is not in the document"));
  }

  @Test
  @DisplayName(
      "T-API-001a x-argos-role in lower case from @RequiresRole, x-argos-pii and x-argos-audit from"
          + " their annotations, and the matrix holds for all four levels")
  void extensions() {
    try (ContractServer server = ContractServer.start(Samples.class)) {
      OpenApiSpec spec = server.spec();

      OpenApiSpec.Operation open = operation(spec, "GET /api/v1/test-samples/public");
      assertThat(open.role()).contains("public");
      assertThat(open.audit()).isEmpty();
      assertThat(open.pii()).isEmpty();

      assertThat(operation(spec, "GET /api/v1/test-samples/viewer").role()).contains("viewer");

      OpenApiSpec.Operation operator = operation(spec, "POST /api/v1/test-samples/operator");
      assertThat(operator.role()).contains("operator");
      assertThat(operator.audit()).contains("USER_UPDATED,ROLE_CHANGED");

      OpenApiSpec.Operation admin = operation(spec, "GET /api/v1/test-samples/admin");
      assertThat(admin.role()).as("from the class annotation").contains("admin");
      assertThat(admin.pii()).contains(ArgosPii.VIEW);

      assertThat(operation(spec, "POST /api/v1/test-samples/no-audit").audit())
          .contains(ArgosAudit.NONE);

      PermissionMatrix.Result result =
          PermissionMatrix.run(
              spec,
              (identity, request) ->
                  server
                      .client(identity.role() == null ? null : identity.role().extensionValue())
                      .send(request.method(), request.path(), request.body(), Map.of()),
              ContractServer.handlerRefusals(),
              // Stubbed handlers, as in PermissionMatrixContractTest.
              PermissionMatrix.ServerErrors.TOLERATE);
      assertThat(result.failures()).isEmpty();
      assertThat(
              result.rows().stream()
                  .filter(row -> row.operation().path().startsWith("/api/v1/test-samples/"))
                  .map(PermissionMatrix.Row::role)
                  .distinct()
                  .toList())
          .containsExactlyInAnyOrderElementsOf(List.of("public", "viewer", "operator", "admin"));
    }
  }
}
