/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.argos.server.bootstrap.StartupPhaseException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.web.HttpRequestHandler;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;
import org.springframework.web.servlet.function.support.RouterFunctionMapping;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;
import org.springframework.web.servlet.mvc.ParameterizableViewController;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

/**
 * {@link RoleMatrix}'s check of handlers that are not {@code @RequestMapping} methods (DSN-12-11,
 * SEC-49); the started application is in contractTest ({@code NonMethodHandlerContractTest}).
 */
@Tag("req:SEC-49")
@Tag("req:DSN-12-11")
class NonMethodHandlersTest {

  /** A URL mapping whose handlers a test registers. */
  static final class UrlMapping extends AbstractUrlHandlerMapping {
    UrlMapping add(String pattern, Object handler) {
      registerHandler(pattern, handler);
      return this;
    }
  }

  /** A handler that authenticates every request itself. */
  static final class SelfAuthenticating implements HttpRequestHandler, SelfAuthenticatingHandler {
    @Override
    public void handleRequest(HttpServletRequest request, HttpServletResponse response) {}
  }

  private static Map<String, HandlerMapping> mappings(Object... beanNameThenMapping) {
    Map<String, HandlerMapping> mappings = new LinkedHashMap<>();
    for (int i = 0; i < beanNameThenMapping.length; i += 2) {
      mappings.put((String) beanNameThenMapping[i], (HandlerMapping) beanNameThenMapping[i + 1]);
    }
    return mappings;
  }

  @Test
  @DisplayName(
      "T-SEC-49 static resources and a SelfAuthenticatingHandler pass outside /api; method mappings"
          + " are RoleMatrix#missing's")
  void allowed() {
    UrlMapping resources =
        new UrlMapping()
            .add("/**", new ResourceHttpRequestHandler())
            .add("/webjars/**", new ResourceHttpRequestHandler());
    UrlMapping ws = new UrlMapping().add("/ws", new SelfAuthenticating());
    RouterFunctionMapping noRouter = new RouterFunctionMapping();
    assertThat(
            NonMethodHandlers.refused(
                mappings(
                    "resourceHandlerMapping",
                    resources,
                    "wsMapping",
                    ws,
                    "routerFunctionMapping",
                    noRouter,
                    "requestMappingHandlerMapping",
                    new RequestMappingHandlerMapping())))
        .isEmpty();
  }

  @Test
  @DisplayName(
      "T-SEC-49 a bean-name handler, an HttpRequestHandler, a view controller, anything under /api"
          + " and a router function are refused, named by pattern, type and mapping")
  void refused() {
    HttpRequestHandler probe = (request, response) -> response.setStatus(200);
    UrlMapping beanNames =
        new UrlMapping()
            .add("/api/v1/review-probe", probe)
            .add("/plain", probe)
            .add("/", new ParameterizableViewController());
    UrlMapping apiResources =
        new UrlMapping()
            .add("/api/files/**", new ResourceHttpRequestHandler())
            .add("/api/ws", new SelfAuthenticating());
    RouterFunctionMapping router =
        new RouterFunctionMapping(
            RouterFunctions.route()
                .POST("/api/v1/review-router", request -> ServerResponse.ok().build())
                .build());
    HandlerMapping unknown = request -> null;
    List<String> refused =
        NonMethodHandlers.refused(
            mappings(
                "beanNameHandlerMapping",
                beanNames,
                "apiResources",
                apiResources,
                "routerFunctionMapping",
                router,
                "custom",
                unknown));
    assertThat(refused)
        .hasSize(7)
        .anyMatch(
            r -> r.startsWith("/api/v1/review-probe -> ") && r.endsWith("(beanNameHandlerMapping)"))
        .anyMatch(r -> r.startsWith("/plain -> "))
        .anyMatch(
            r ->
                r.equals(
                    "/ -> "
                        + ParameterizableViewController.class.getName()
                        + " (beanNameHandlerMapping)"))
        .anyMatch(
            r -> r.startsWith("/api/files/** -> " + ResourceHttpRequestHandler.class.getName()))
        .anyMatch(r -> r.startsWith("/api/ws -> " + SelfAuthenticating.class.getName()))
        .anyMatch(r -> r.startsWith("router function ") && r.contains("/api/v1/review-router"))
        .anyMatch(
            r -> r.startsWith("handler mapping of unsupported type ") && r.endsWith("(custom)"));
  }

  @Test
  @DisplayName("T-SEC-49 RoleMatrix fails the start for a refused handler and names it")
  void failsTheStart() {
    try (GenericApplicationContext context = new GenericApplicationContext()) {
      // A real context holding the one mapping as a ready-made bean (no initialisation callbacks).
      context
          .getBeanFactory()
          .registerSingleton(
              "beanNameHandlerMapping",
              new UrlMapping().add("/api/v1/review-probe", (HttpRequestHandler) (q, s) -> {}));
      context.refresh();
      RoleMatrix matrix = new RoleMatrix(context, new HandlerRoles(List.of()));
      assertThat(matrix.missing()).isEmpty();
      assertThat(matrix.refusedNonMethodHandlers()).hasSize(1);
      assertThatThrownBy(matrix::run)
          .isInstanceOf(StartupPhaseException.class)
          .hasMessageContaining(
              "1 handler(s) are not @RequestMapping methods and cannot carry @RequiresRole")
          .hasMessageContaining("/api/v1/review-probe -> ")
          .hasMessageNotContaining("REST handler(s) have no @RequiresRole");
    }
  }
}
