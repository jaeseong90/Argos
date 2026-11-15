/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.bootstrap.PhaseOrder;
import io.argos.server.bootstrap.StartupPhaseException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** {@link RoleMatrix} over given handler mappings; the started application is in contractTest. */
@Tag("req:SEC-49")
@Tag("req:DSN-12-11")
class RoleMatrixTest {

  /** Handlers with and without a role. */
  static class Handlers {
    @RequiresRole(Role.VIEWER)
    public void annotated() {}

    public void missing() {}

    public void known() {}
  }

  /** A role on the class. */
  @RequiresRole(Role.ADMIN)
  static class AnnotatedClass {
    public void inherited() {}
  }

  /** Overloads: a role on one of them says nothing about the other. */
  static class Overloads {
    @RequiresRole(Role.VIEWER)
    public void get() {}

    public void get(String id) {}
  }

  /** More handlers without a role than the message lists. */
  static class Many {
    public void m1() {}

    public void m2() {}

    public void m3() {}

    public void m4() {}

    public void m5() {}

    public void m6() {}

    public void m7() {}

    public void m8() {}

    public void m9() {}
  }

  private static HandlerMethod handler(Object bean, String name) throws NoSuchMethodException {
    Method method = bean.getClass().getMethod(name);
    return new HandlerMethod(bean, method);
  }

  private static RequestMappingHandlerMapping mapping(HandlerMethod... handlers) {
    Map<RequestMappingInfo, HandlerMethod> methods = new LinkedHashMap<>();
    for (HandlerMethod handler : handlers) {
      methods.put(RequestMappingInfo.paths("/" + handler.getMethod().getName()).build(), handler);
    }
    return mapping(methods);
  }

  /** A handler-method mapping that lists exactly the given methods. */
  private static final class FixedMapping extends RequestMappingHandlerMapping {

    private final Map<RequestMappingInfo, HandlerMethod> methods;

    FixedMapping(Map<RequestMappingInfo, HandlerMethod> methods) {
      this.methods = Collections.unmodifiableMap(new LinkedHashMap<>(methods));
    }

    @Override
    public Map<RequestMappingInfo, HandlerMethod> getHandlerMethods() {
      return methods;
    }
  }

  private static RequestMappingHandlerMapping mapping(
      Map<RequestMappingInfo, HandlerMethod> methods) {
    return new FixedMapping(methods);
  }

  /**
   * A matrix over a real application context that holds {@code mapping} as a ready-made bean (no
   * initialisation callbacks, so the mapping keeps exactly its methods). The context stays open: it
   * holds no resources.
   */
  @SuppressWarnings("resource")
  private static RoleMatrix matrix(
      RequestMappingHandlerMapping mapping, HandlerRoleSource... sources) {
    GenericApplicationContext context = new GenericApplicationContext();
    context.getBeanFactory().registerSingleton("mapping", mapping);
    context.refresh();
    return new RoleMatrix(context, new HandlerRoles(List.of(sources)));
  }

  @Test
  @DisplayName(
      "T-SEC-49 a handler without a role is named; with one on the method or class it is not")
  void missing() throws Exception {
    Handlers bean = new Handlers();
    RoleMatrix matrix =
        matrix(
            mapping(
                handler(bean, "annotated"),
                handler(bean, "missing"),
                handler(new AnnotatedClass(), "inherited")));
    assertThat(matrix.missing()).containsExactly(Handlers.class.getName() + "#missing()");
    assertThat(matrix.table())
        .containsEntry(Handlers.class.getName() + "#annotated()", "viewer")
        .containsEntry(Handlers.class.getName() + "#missing()", "(none)")
        .containsEntry(AnnotatedClass.class.getName() + "#inherited()", "admin");
  }

  @Test
  @DisplayName(
      "T-SEC-49 an overload without a role is named by its signature, though the overload mapped"
          + " first has one")
  void overloads() throws Exception {
    Overloads bean = new Overloads();
    Map<RequestMappingInfo, HandlerMethod> methods = new LinkedHashMap<>();
    methods.put(
        RequestMappingInfo.paths("/a").build(),
        new HandlerMethod(bean, Overloads.class.getMethod("get")));
    methods.put(
        RequestMappingInfo.paths("/b").build(),
        new HandlerMethod(bean, Overloads.class.getMethod("get", String.class)));
    RoleMatrix matrix = matrix(mapping(methods));

    String type = Overloads.class.getName();
    assertThat(matrix.missing()).containsExactly(type + "#get(java.lang.String)");
    assertThat(matrix.table())
        .hasSize(2)
        .containsEntry(type + "#get()", "viewer")
        .containsEntry(type + "#get(java.lang.String)", "(none)");
    assertThatThrownBy(matrix::run)
        .isInstanceOf(StartupPhaseException.class)
        .hasMessageContaining("1 REST handler(s) have no @RequiresRole")
        .hasMessageContaining(type + "#get(java.lang.String)");
  }

  @Test
  @DisplayName("T-SEC-49 the check is a step of the first start phase and fails with the names")
  void failsTheStart() throws Exception {
    RoleMatrix matrix = matrix(mapping(handler(new Handlers(), "missing")));
    assertThat(matrix.order()).isEqualTo(PhaseOrder.CONFIG);
    assertThat(matrix.name()).isEqualTo("role-matrix");
    assertThatThrownBy(matrix::run)
        .isInstanceOf(StartupPhaseException.class)
        .hasMessageContaining("1 REST handler(s) have no @RequiresRole")
        .hasMessageContaining(Handlers.class.getName() + "#missing()");
  }

  @Test
  @DisplayName("T-SEC-49 a long list is cut in the message but counted; a clean application passes")
  void limitsAndPass() throws Exception {
    assertThatCode(() -> matrix(mapping(handler(new AnnotatedClass(), "inherited"))).run())
        .doesNotThrowAnyException();

    Many many = new Many();
    HandlerMethod[] handlers = new HandlerMethod[9];
    for (int i = 0; i < handlers.length; i++) {
      handlers[i] = handler(many, "m" + (i + 1));
    }
    assertThatThrownBy(matrix(mapping(handlers))::run)
        .isInstanceOf(StartupPhaseException.class)
        .hasMessageContaining("9 REST handler(s)")
        .hasMessageContaining(Many.class.getName() + "#m8()")
        .hasMessageEndingWith(", ...");
  }

  @Test
  @DisplayName("T-SEC-49 a role source answers for a handler that cannot carry the annotation")
  void roleSource() throws Exception {
    HandlerMethod known = handler(new Handlers(), "known");
    HandlerRoleSource source =
        handler ->
            handler.getMethod().getName().equals("known")
                ? Optional.of(Role.VIEWER)
                : Optional.empty();
    assertThat(matrix(mapping(known), source).missing()).isEmpty();
    assertThat(matrix(mapping(known)).missing()).hasSize(1);
  }
}
