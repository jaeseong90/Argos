/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.audit.AuditEvent;
import io.argos.server.auth.Role;
import io.argos.server.auth.RoleMatrix;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.servertest.security.HandlerReachedMarker;
import io.argos.servertest.security.matrix.OpenApiSpec;
import io.argos.servertest.security.matrix.PermissionMatrix;
import io.argos.servertest.security.matrix.PermissionMatrix.Identity;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.function.support.RouterFunctionMapping;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;

/**
 * The OpenAPI document of the current server and the permission matrix generated from it (T-API-001
 * for the current endpoints, T-SEC-49, T-API-010; 14 §2.8, §7.1, 16 §7.10, ADR-0010 C-110). The
 * document comes from springdoc at run time; the committed file, its lint and the compatibility
 * diff are WO-23's. Expectations come from {@code x-argos-role} only: endpoints that WO-23 to WO-28
 * add are covered without a change here.
 *
 * <p>The matrix runs with {@link PermissionMatrix.ServerErrors#TOLERATE}: the handlers here run on
 * Mockito stubs ({@link ContractServer}), so a 500 in an allowed cell comes from a stub's answer
 * (today login and setup, whose stubbed collaborators make the handler fail), not from the role
 * layer, which the "handler reached" marker judges on its own. The real-session run of the security
 * suite ({@code PermissionMatrixSessionSecurityTest}) judges 5xx with {@link
 * PermissionMatrix.ServerErrors#FAIL}.
 *
 * <p>{@link #everyControllerIsLoaded()} guards the premise of both runs here: every controller of
 * the server is part of the contract web layer, so its handlers are in the document and the matrix.
 */
@Tag("req:SEC-49")
@Tag("req:DSN-12-11")
class PermissionMatrixContractTest {

  private static final Set<String> ROLES = Set.of("public", "viewer", "operator", "admin");

  private static ContractServer server;
  private static OpenApiSpec spec;

  @BeforeAll
  static void start() {
    server = ContractServer.start();
    spec = server.spec();
  }

  @AfterAll
  static void stop() {
    if (server != null) {
      server.close();
    }
  }

  @Test
  @DisplayName(
      "T-API-001a every operation of the document has x-argos-role, and every POST, PUT, PATCH and"
          + " DELETE has x-argos-audit")
  void everyOperationIsMarked() {
    assertThat(spec.operations())
        .as("the document lists the REST API, the CSP report endpoint among it")
        .extracting(OpenApiSpec.Operation::key)
        .contains("POST /api/v1/csp-report");

    List<String> problems = new ArrayList<>();
    Set<String> events =
        Arrays.stream(AuditEvent.values()).map(Enum::name).collect(Collectors.toSet());
    events.add(ArgosAudit.NONE);
    for (OpenApiSpec.Operation operation : spec.operations()) {
      if (operation.role().filter(ROLES::contains).isEmpty()) {
        problems.add(operation.key() + ": x-argos-role " + operation.role());
      }
      if (operation.unsafe() && operation.audit().isEmpty()) {
        problems.add(operation.key() + ": no x-argos-audit");
      }
      operation
          .audit()
          .ifPresent(
              value -> {
                if (!events.containsAll(List.of(value.split(",", -1)))) {
                  problems.add(operation.key() + ": x-argos-audit " + value + " is not an event");
                }
              });
    }
    assertThat(problems).isEmpty();
  }

  @Test
  @DisplayName(
      "T-API-001a POST /api/v1/admin/users documents username, roles, must_change_password and"
          + " password; no email, which nothing would store")
  void createUserBody() {
    OpenApiSpec.Operation create =
        spec.operations().stream()
            .filter(op -> op.key().equals("POST /api/v1/admin/users"))
            .findFirst()
            .orElseThrow();
    JsonNode schema =
        spec.resolve(
            create
                .node()
                .path("requestBody")
                .path("content")
                .path("application/json")
                .path("schema"));
    assertThat(schema.path("properties").propertyNames())
        .containsExactlyInAnyOrder("username", "roles", "must_change_password", "password");
  }

  @Test
  @DisplayName(
      "T-API-001a every handler under /api is in the document, so the matrix reaches it, and the"
          + " endpoints of WO-24 carry their roles")
  void documentCoversEveryHandler() {
    Set<String> documented =
        spec.operations().stream()
            .map(OpenApiSpec.Operation::key)
            .collect(Collectors.toCollection(HashSet::new));
    List<String> undocumented = new ArrayList<>();
    for (RequestMappingHandlerMapping mapping :
        server.context().getBeansOfType(RequestMappingHandlerMapping.class).values()) {
      mapping
          .getHandlerMethods()
          .forEach(
              (info, handler) -> {
                // springdoc serves the document itself; documentAccess() covers those two paths.
                if (handler.getBeanType().getName().startsWith("org.springdoc.")) {
                  return;
                }
                for (String path : info.getPatternValues()) {
                  if (!path.startsWith("/api/")) {
                    continue;
                  }
                  assertThat(info.getMethodsCondition().getMethods())
                      .as(path + " names its HTTP methods")
                      .isNotEmpty();
                  info.getMethodsCondition()
                      .getMethods()
                      .forEach(
                          method -> {
                            String key = method.name() + " " + path;
                            if (!documented.contains(key)) {
                              undocumented.add(key);
                            }
                          });
                }
              });
    }
    assertThat(undocumented).as("handlers the matrix would not reach").isEmpty();

    // A handler that is not a handler method is never in the document and cannot carry a role:
    // none may be mapped under /api (RoleMatrix refuses them at start; this is the matrix's view).
    List<String> nonMethodUnderApi = new ArrayList<>();
    server
        .context()
        .getBeansOfType(AbstractUrlHandlerMapping.class)
        .forEach(
            (bean, mapping) ->
                mapping
                    .getHandlerMap()
                    .forEach(
                        (pattern, handler) -> {
                          if (pattern.startsWith("/api")) {
                            nonMethodUnderApi.add(pattern + " -> " + handler + " (" + bean + ")");
                          }
                        }));
    server
        .context()
        .getBeansOfType(RouterFunctionMapping.class)
        .forEach(
            (bean, mapping) -> {
              if (mapping.getRouterFunction() != null) {
                nonMethodUnderApi.add(mapping.getRouterFunction() + " (" + bean + ")");
              }
            });
    assertThat(nonMethodUnderApi).as("non-method handlers the matrix cannot reach").isEmpty();

    Map<String, String> roles = new HashMap<>();
    spec.operations().forEach(op -> roles.put(op.key(), op.role().orElse("(none)")));
    assertThat(roles)
        .containsEntry("GET /api/v1/setup/status", "public")
        .containsEntry("POST /api/v1/setup/admin", "public")
        .containsEntry("GET /api/v1/auth/csrf", "public")
        .containsEntry("POST /api/v1/auth/login", "public")
        .containsEntry("POST /api/v1/auth/refresh", "public")
        .containsEntry("POST /api/v1/auth/logout", "viewer")
        .containsEntry("GET /api/v1/auth/me", "viewer")
        .containsEntry("PUT /api/v1/auth/password", "viewer")
        .containsEntry("GET /api/v1/admin/users", "admin")
        .containsEntry("POST /api/v1/admin/users", "admin")
        .containsEntry("PATCH /api/v1/admin/users/{id}", "admin")
        .containsEntry("POST /api/v1/admin/users/{id}/sessions/revoke", "admin")
        .containsEntry("POST /api/v1/admin/users/{id}/password/reset", "admin")
        .containsEntry("POST /api/v1/admin/users/{id}/unlock", "admin")
        .containsEntry("POST /api/v1/csp-report", "public");
  }

  @Test
  @DisplayName(
      "T-API-001a every controller of the server is in the contract web layer, so the document and"
          + " the matrix reach its handlers")
  void everyControllerIsLoaded() throws ClassNotFoundException {
    Set<Class<?>> handlerTypes = new HashSet<>();
    for (RequestMappingHandlerMapping mapping :
        server.context().getBeansOfType(RequestMappingHandlerMapping.class).values()) {
      mapping
          .getHandlerMethods()
          .values()
          .forEach(handler -> handlerTypes.add(handler.getBeanType()));
    }
    List<Class<?>> controllers = productionControllers();
    assertThat(controllers)
        .extracting(Class::getName)
        .as("not vacuous: the scan finds controllers outside the web and api packages too")
        .contains(
            "io.argos.server.auth.api.AuthController", "io.argos.server.web.CspReportController")
        .doesNotContain(RoleMatrixContractTest.MissingRole.class.getName());
    List<String> notLoaded =
        controllers.stream()
            .filter(PermissionMatrixContractTest::hasRequestMapping)
            .filter(type -> !handlerTypes.contains(type))
            .map(Class::getName)
            .toList();
    assertThat(notLoaded)
        .as("controllers of the server that ContractServer.WebLayer leaves out of the web layer")
        .isEmpty();
  }

  /**
   * The {@code @Controller} classes ({@code @RestController} among them) of the server's production
   * output, wherever they are in {@code io.argos.server}: the test-only controllers of this and
   * other suites are left out by their class location.
   */
  private static List<Class<?>> productionControllers() throws ClassNotFoundException {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
    String production = location(RoleMatrix.class);
    ClassLoader loader = PermissionMatrixContractTest.class.getClassLoader();
    List<Class<?>> controllers = new ArrayList<>();
    for (BeanDefinition candidate : scanner.findCandidateComponents("io.argos.server")) {
      Class<?> type = Class.forName(candidate.getBeanClassName(), false, loader);
      if (production.equals(location(type))) {
        controllers.add(type);
      }
    }
    return controllers;
  }

  private static String location(Class<?> type) {
    return type.getProtectionDomain().getCodeSource().getLocation().toExternalForm();
  }

  /** Whether {@code type} declares a handler; one without has nothing to load. */
  private static boolean hasRequestMapping(Class<?> type) {
    return Arrays.stream(ReflectionUtils.getUniqueDeclaredMethods(type))
        .anyMatch(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class));
  }

  @Test
  @DisplayName(
      "T-SEC-49/T-API-010 anonymous and every role on every operation: allowed reaches the handler,"
          + " denied is 401 or 403 before it")
  void permissionMatrix() {
    Map<Identity, ApiTestClient> clients = new EnumMap<>(Identity.class);
    for (Identity identity : Identity.values()) {
      clients.put(
          identity,
          server.client(identity.role() == null ? null : identity.role().extensionValue()));
    }
    PermissionMatrix.Result result =
        PermissionMatrix.run(
            spec,
            (identity, request) ->
                clients
                    .get(identity)
                    .send(request.method(), request.path(), request.body(), Map.of()),
            ContractServer.handlerRefusals(),
            PermissionMatrix.ServerErrors.TOLERATE);
    Path report =
        Path.of(
            System.getProperty("argos.repositoryRoot", ".."),
            "server",
            "build",
            "reports",
            "permission-matrix.md");
    result.writeReport(report, "Permission matrix (web layer, contract suite)");

    assertThat(result.failures()).isEmpty();
    assertThat(result.rows()).hasSameSizeAs(spec.operations());
    Set<String> covered = new HashSet<>();
    result.rows().forEach(row -> covered.add(row.role()));
    assertThat(covered).as("the table is not vacuous").contains(Role.PUBLIC.extensionValue());
  }

  @Test
  @DisplayName(
      "T-SEC-49 the document is for viewers only (14 §8), and springdoc's default paths are gone")
  void documentAccess() {
    for (String path : List.of("/api/v1/openapi", "/api/v1/openapi.yaml")) {
      assertThat(server.client(null).get(path).status()).as("anonymous " + path).isEqualTo(401);
      ApiResponse viewer = server.client("viewer").get(path);
      assertThat(viewer.status()).as("viewer " + path).isEqualTo(200);
      assertThat(viewer.header(HandlerReachedMarker.HEADER)).isPresent();
      assertThat(viewer.header("Cache-Control")).contains("no-store");
    }
    assertThat(server.client("viewer").get("/api/v1/openapi.yaml").body())
        .startsWith("openapi:")
        .contains("x-argos-role");
    assertThat(server.client("viewer").get("/api/v1/openapi").header("Content-Type").orElseThrow())
        .isEqualToIgnoringCase("application/json;charset=UTF-8");
    for (String path :
        List.of(
            "/v3/api-docs", "/v3/api-docs.yaml", "/swagger-ui.html", "/swagger-ui/index.html")) {
      ApiResponse response = server.client("admin").get(path);
      assertThat(response.status()).as(path).isEqualTo(404);
      assertThat(response.body()).as(path).doesNotContain("openapi");
    }
  }
}
