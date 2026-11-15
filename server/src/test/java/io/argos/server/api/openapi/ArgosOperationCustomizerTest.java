/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.audit.AuditEvent;
import io.argos.server.auth.HandlerRoles;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.swagger.v3.oas.models.Operation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

/** The extensions of 14 §2.8 from the annotations of a handler. */
@Tag("req:SEC-49")
class ArgosOperationCustomizerTest {

  /** Annotated handlers. */
  static class Handlers {
    @RequiresRole(Role.OPERATOR)
    @ArgosPii
    @ArgosAudit(AuditEvent.USER_UPDATED)
    public void all() {}

    @RequiresRole(Role.PUBLIC)
    @ArgosAudit({AuditEvent.LOGIN_SUCCESS, AuditEvent.LOGIN_FAILED})
    public void events() {}

    @RequiresRole(Role.PUBLIC)
    @ArgosAudit(none = "nothing is stored")
    public void none() {}

    @RequiresRole(Role.VIEWER)
    public void plain() {}

    public void nothing() {}
  }

  /** Class-level annotations and a method override. */
  @RequiresRole(Role.ADMIN)
  @ArgosPii
  @ArgosAudit(AuditEvent.ROLE_CHANGED)
  static class AnnotatedClass {
    public void inherited() {}

    @RequiresRole(Role.VIEWER)
    @ArgosAudit(AuditEvent.USER_DISABLED)
    public void overridden() {}
  }

  private final ArgosOperationCustomizer customizer =
      new ArgosOperationCustomizer(new HandlerRoles(List.of()));

  private Map<String, Object> extensions(Object bean, String method) throws Exception {
    Operation operation =
        customizer.customize(
            new Operation(), new HandlerMethod(bean, bean.getClass().getMethod(method)));
    return operation.getExtensions() == null ? Map.of() : operation.getExtensions();
  }

  @Test
  @DisplayName("T-API-001a x-argos-role in lower case, x-argos-pii view, x-argos-audit event names")
  void values() throws Exception {
    Handlers bean = new Handlers();
    assertThat(extensions(bean, "all"))
        .containsOnly(
            Map.entry("x-argos-role", "operator"),
            Map.entry("x-argos-pii", "view"),
            Map.entry("x-argos-audit", "USER_UPDATED"));
    assertThat(extensions(bean, "events"))
        .containsOnly(
            Map.entry("x-argos-role", "public"),
            Map.entry("x-argos-audit", "LOGIN_SUCCESS,LOGIN_FAILED"));
    assertThat(extensions(bean, "none")).containsEntry("x-argos-audit", "none");
    assertThat(extensions(bean, "plain")).containsOnly(Map.entry("x-argos-role", "viewer"));
    assertThat(extensions(bean, "nothing")).isEmpty();
  }

  /** Annotations that T-API-001 has to report. */
  static class Invalid {
    @ArgosAudit
    public void neither() {}

    @ArgosAudit(value = AuditEvent.LOGOUT, none = "x")
    public void both() {}
  }

  @Test
  @DisplayName("T-API-001a an @ArgosAudit with neither events nor a reason, or both, emits nothing")
  void invalidAudit() throws Exception {
    Invalid bean = new Invalid();
    assertThat(extensions(bean, "neither")).doesNotContainKey("x-argos-audit");
    assertThat(extensions(bean, "both")).doesNotContainKey("x-argos-audit");
  }

  @Test
  @DisplayName("T-API-001a an annotation on the class applies, one on the method wins")
  void classLevel() throws Exception {
    AnnotatedClass bean = new AnnotatedClass();
    assertThat(extensions(bean, "inherited"))
        .containsOnly(
            Map.entry("x-argos-role", "admin"),
            Map.entry("x-argos-pii", "view"),
            Map.entry("x-argos-audit", "ROLE_CHANGED"));
    assertThat(extensions(bean, "overridden"))
        .containsEntry("x-argos-role", "viewer")
        .containsEntry("x-argos-audit", "USER_DISABLED");
  }
}
