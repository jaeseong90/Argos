/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.mvc.Controller;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

/** {@link RequiresRole} enforcement per handler (DSN-12-11, 16 §7.10, SEC-49). */
@Tag("req:SEC-49")
class RoleAuthorizationInterceptorTest {

  private static final Object OWNER = new Object();

  private final RoleAuthorizationInterceptor interceptor =
      new RoleAuthorizationInterceptor(new HandlerRoles(List.of()), OWNER);

  /** A controller with a class-level role and method overrides. */
  @RequiresRole(Role.OPERATOR)
  static class OperatorController {
    public void classLevel() {}

    @RequiresRole(Role.ADMIN)
    public void adminOnly() {}

    @RequiresRole(Role.PUBLIC)
    public void open() {}

    @RequiresRole(Role.VIEWER)
    public void viewer() {}
  }

  /** A handler without any role. */
  static class Unannotated {
    public void handle() {}
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private static HandlerMethod handler(Object bean, String name) throws NoSuchMethodException {
    Method method = bean.getClass().getMethod(name);
    return new HandlerMethod(bean, method);
  }

  private static MockHttpServletRequest request() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/x");
    request.setAttribute(DispatcherServlet.WEB_APPLICATION_CONTEXT_ATTRIBUTE, OWNER);
    return request;
  }

  private static void loginAs(Role role) {
    ArgosAuthentication.authenticate(
        new ArgosPrincipal(UUID.randomUUID(), "u", UUID.randomUUID(), role, Set.of(), false));
  }

  private boolean call(String method) throws Exception {
    return interceptor.preHandle(
        request(), new MockHttpServletResponse(), handler(new OperatorController(), method));
  }

  private static ErrorCode codeOf(ThrowingCall call) {
    try {
      call.run();
    } catch (ApiException e) {
      return e.errorCode();
    } catch (Exception e) {
      throw new AssertionError(e);
    }
    return null;
  }

  @FunctionalInterface
  private interface ThrowingCall {
    void run() throws Exception;
  }

  @Test
  @DisplayName("T-SEC-49a role inclusion: ADMIN > OPERATOR > VIEWER > PUBLIC")
  void inclusion() {
    // 16 §7.10: ADMIN ⊃ OPERATOR ⊃ VIEWER; every role may call PUBLIC endpoints.
    Map<Role, Set<Role>> allowed =
        Map.of(
            Role.PUBLIC, Set.of(Role.PUBLIC),
            Role.VIEWER, Set.of(Role.PUBLIC, Role.VIEWER),
            Role.OPERATOR, Set.of(Role.PUBLIC, Role.VIEWER, Role.OPERATOR),
            Role.ADMIN, Set.of(Role.PUBLIC, Role.VIEWER, Role.OPERATOR, Role.ADMIN));
    for (Role user : Role.values()) {
      for (Role required : Role.values()) {
        assertThat(user.includes(required))
            .as(user + " includes " + required)
            .isEqualTo(allowed.get(user).contains(required));
      }
    }
    assertThat(Role.ADMIN.includes(Role.VIEWER)).isTrue();
    assertThat(Role.VIEWER.includes(Role.OPERATOR)).isFalse();
    assertThat(Role.ADMIN.extensionValue()).isEqualTo("admin");
    assertThat(Role.PUBLIC.extensionValue()).isEqualTo("public");
    assertThat(Role.ofUserRole("OPERATOR")).isEqualTo(Role.OPERATOR);
    assertThat(Role.ofUserRole("viewer")).isEqualTo(Role.VIEWER);
    assertThatThrownBy(() -> Role.ofUserRole("public"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Role.ofUserRole("root")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName(
      "T-SEC-42a a revoked session (role change, deactivation): 401 session_revoked on every"
          + " protected endpoint whatever role it needs, before any role check; PUBLIC passes")
  void revokedSession() throws Exception {
    SecurityContextHolder.clearContext();
    OperatorController controller = new OperatorController();
    MockHttpServletRequest request = request();
    AuthenticationFailure.record(request, ErrorCode.SESSION_REVOKED);
    for (String method : List.of("viewer", "classLevel", "adminOnly")) {
      assertThat(
              codeOf(
                  () ->
                      interceptor.preHandle(
                          request, new MockHttpServletResponse(), handler(controller, method))))
          .as(method)
          .isEqualTo(ErrorCode.SESSION_REVOKED);
    }
    assertThat(
            interceptor.preHandle(
                request, new MockHttpServletResponse(), handler(controller, "open")))
        .isTrue();
    assertThat(AuthenticationFailure.of(request())).isEqualTo(ErrorCode.UNAUTHENTICATED);
  }

  @Test
  @DisplayName(
      "T-SEC-49a anonymous: PUBLIC passes, every other role is 401 with the recorded reason")
  void anonymous() throws Exception {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "k", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
    assertThat(call("open")).isTrue();
    assertThat(codeOf(() -> call("viewer"))).isEqualTo(ErrorCode.UNAUTHENTICATED);
    assertThat(codeOf(() -> call("classLevel"))).isEqualTo(ErrorCode.UNAUTHENTICATED);

    MockHttpServletRequest expired = request();
    AuthenticationFailure.record(expired, ErrorCode.SESSION_EXPIRED);
    assertThat(
            codeOf(
                () ->
                    interceptor.preHandle(
                        expired,
                        new MockHttpServletResponse(),
                        handler(new OperatorController(), "viewer"))))
        .isEqualTo(ErrorCode.SESSION_EXPIRED);
    assertThatThrownBy(() -> AuthenticationFailure.record(expired, ErrorCode.FORBIDDEN))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName(
      "T-SEC-49a a lower role is 403 forbidden; equal and higher roles pass; the method annotation"
          + " wins over the class")
  void roles() throws Exception {
    loginAs(Role.VIEWER);
    assertThat(call("viewer")).isTrue();
    assertThat(call("open")).isTrue();
    assertThat(codeOf(() -> call("classLevel"))).isEqualTo(ErrorCode.FORBIDDEN);
    assertThat(codeOf(() -> call("adminOnly"))).isEqualTo(ErrorCode.FORBIDDEN);

    loginAs(Role.OPERATOR);
    assertThat(call("classLevel")).isTrue();
    assertThat(codeOf(() -> call("adminOnly"))).isEqualTo(ErrorCode.FORBIDDEN);

    loginAs(Role.ADMIN);
    assertThat(call("classLevel")).isTrue();
    assertThat(call("adminOnly")).isTrue();
    assertThat(call("viewer")).isTrue();
  }

  @Test
  @DisplayName(
      "T-SEC-49a a handler without a role is refused (fail closed) unless a role source knows it")
  void unannotated() throws Exception {
    loginAs(Role.ADMIN);
    HandlerMethod unannotated = handler(new Unannotated(), "handle");
    assertThat(HandlerRoles.annotated(unannotated)).isEmpty();
    assertThat(
            codeOf(
                () -> interceptor.preHandle(request(), new MockHttpServletResponse(), unannotated)))
        .isEqualTo(ErrorCode.FORBIDDEN);

    HandlerRoleSource source =
        h -> h.getBeanType() == Unannotated.class ? Optional.of(Role.VIEWER) : Optional.empty();
    RoleAuthorizationInterceptor withSource =
        new RoleAuthorizationInterceptor(new HandlerRoles(List.of(source)), OWNER);
    assertThat(withSource.preHandle(request(), new MockHttpServletResponse(), unannotated))
        .isTrue();
    SecurityContextHolder.clearContext();
    assertThat(
            codeOf(
                () -> withSource.preHandle(request(), new MockHttpServletResponse(), unannotated)))
        .isEqualTo(ErrorCode.UNAUTHENTICATED);
  }

  @Test
  @DisplayName(
      "requests of another context (management port) are not checked, whatever their handler")
  void scope() throws Exception {
    MockHttpServletRequest management = new MockHttpServletRequest("GET", "/actuator/health");
    management.setAttribute(DispatcherServlet.WEB_APPLICATION_CONTEXT_ATTRIBUTE, new Object());
    assertThat(
            interceptor.preHandle(
                management, new MockHttpServletResponse(), handler(new Unannotated(), "handle")))
        .isTrue();
    HttpRequestHandler plain = (req, res) -> {};
    assertThat(interceptor.preHandle(management, new MockHttpServletResponse(), plain))
        .as("a non-method handler dispatched by the management context")
        .isTrue();
    assertThat(interceptor.preHandle(management, new MockHttpServletResponse(), new Object()))
        .isTrue();
  }

  /** A handler that authenticates every request itself (the hook of WO-22's handshake). */
  static class SelfAuthenticating implements HttpRequestHandler, SelfAuthenticatingHandler {
    @Override
    public void handleRequest(HttpServletRequest request, HttpServletResponse response) {}
  }

  @Test
  @DisplayName(
      "T-SEC-49a a handler that is not a @RequestMapping method is refused (fail closed), for"
          + " anonymous and administrators alike")
  void nonMethodHandlers() {
    HttpRequestHandler plain = (req, res) -> {};
    Controller controller = (req, res) -> null;
    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "k", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
    for (Object handler : List.of(plain, controller, new Object())) {
      assertThat(
              codeOf(
                  () -> interceptor.preHandle(request(), new MockHttpServletResponse(), handler)))
          .as(handler.getClass().getName())
          .isEqualTo(ErrorCode.FORBIDDEN);
    }
    loginAs(Role.ADMIN);
    assertThat(codeOf(() -> interceptor.preHandle(request(), new MockHttpServletResponse(), plain)))
        .isEqualTo(ErrorCode.FORBIDDEN);
    RoleAuthorizationInterceptor everyRequest =
        new RoleAuthorizationInterceptor(new HandlerRoles(List.of()), null);
    assertThat(
            codeOf(() -> everyRequest.preHandle(request(), new MockHttpServletResponse(), plain)))
        .as("without an owner every request is checked")
        .isEqualTo(ErrorCode.FORBIDDEN);
  }

  @Test
  @DisplayName("T-SEC-49a static resources and a SelfAuthenticatingHandler pass without a session")
  void allowedNonMethodHandlers() throws Exception {
    SecurityContextHolder.clearContext();
    assertThat(
            interceptor.preHandle(
                request(), new MockHttpServletResponse(), new ResourceHttpRequestHandler()))
        .isTrue();
    assertThat(
            interceptor.preHandle(
                request(), new MockHttpServletResponse(), new SelfAuthenticating()))
        .isTrue();
  }

  @Test
  @DisplayName("the principal: user roles only, permissions copied, current() from the context")
  void principal() {
    assertThatThrownBy(
            () ->
                new ArgosPrincipal(
                    UUID.randomUUID(), "u", UUID.randomUUID(), Role.PUBLIC, Set.of(), false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ArgosPrincipal.current()).isEmpty();
    ArgosPrincipal admin =
        new ArgosPrincipal(
            UUID.randomUUID(),
            "admin",
            UUID.randomUUID(),
            Role.ADMIN,
            Set.of(ArgosPrincipal.PII_VIEW),
            true);
    ArgosAuthentication.authenticate(admin);
    assertThat(ArgosPrincipal.current()).contains(admin);
    assertThat(admin.hasPermission("pii.view")).isTrue();
    assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
        .extracting(Object::toString)
        .containsExactly("ROLE_ADMIN", "pii.view");
    assertThat(SecurityContextHolder.getContext().getAuthentication().getCredentials()).isNull();
  }
}
