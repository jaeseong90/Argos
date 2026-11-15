/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.Role;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.servertest.security.HandlerReachedMarker;
import io.argos.servertest.security.matrix.OpenApiSpec;
import io.argos.servertest.security.matrix.PermissionMatrix;
import io.argos.servertest.security.matrix.PermissionMatrix.Identity;
import io.argos.servertest.security.matrix.PermissionMatrix.ServerErrors;
import io.argos.servertest.security.matrix.RequestFactory;
import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The generator itself, against a fake server: it asks for what {@code x-argos-role} says, and a
 * server that behaves differently fails it (a generator that always passes proves nothing).
 */
@Tag("req:SEC-49")
class PermissionMatrixGeneratorTest {

  private static final OpenApiSpec SPEC =
      OpenApiSpec.parse(
          """
          {"openapi":"3.1.0","paths":{
            "/api/v1/open":{"get":{"x-argos-role":"public"}},
            "/api/v1/mine/{id}":{"get":{"x-argos-role":"viewer",
                "parameters":[{"name":"id","in":"path","required":true,
                               "schema":{"type":"string","format":"uuid"}}]}},
            "/api/v1/things/{n}":{"put":{"x-argos-role":"operator","x-argos-audit":"none",
                "parameters":[{"name":"n","in":"path","required":true,
                               "schema":{"type":"integer"}},
                              {"name":"from","in":"query","required":true,
                               "schema":{"type":"string","format":"date-time"}},
                              {"name":"optional","in":"query",
                               "schema":{"type":"string"}}],
                "requestBody":{"content":{"application/json":{
                    "schema":{"$ref":"#/components/schemas/Thing"}}}}}},
            "/api/v1/admin":{"post":{"x-argos-role":"admin","x-argos-audit":"none"}}
          },"components":{"schemas":{"Thing":{"type":"object","required":["name","count","tags"],
            "properties":{"name":{"type":"string","minLength":3},"count":{"type":"integer",
            "minimum":5},"tags":{"type":"array","minItems":1,"items":{"type":"string"}},
            "note":{"type":"string"}}}}}}
          """);

  private static ApiResponse response(int status, boolean reached, String code) {
    Map<String, List<String>> headers =
        reached ? Map.of(HandlerReachedMarker.HEADER, List.of("true")) : Map.of();
    return new ApiResponse(
        "GET",
        "/",
        status,
        HttpHeaders.of(headers, (a, b) -> true),
        code == null ? "" : "{\"error\":{\"code\":\"" + code + "\"}}",
        List.of());
  }

  /** The correct server: answers as the role demands. */
  private static ApiResponse correct(Identity identity, RequestFactory.Request request) {
    Role role;
    if (request.path().startsWith("/api/v1/open")) {
      role = Role.PUBLIC;
    } else if (request.path().startsWith("/api/v1/mine")) {
      role = Role.VIEWER;
    } else if (request.path().startsWith("/api/v1/things")) {
      role = Role.OPERATOR;
    } else {
      role = Role.ADMIN;
    }
    PermissionMatrix.Expected expected = PermissionMatrix.Expected.of(identity, role);
    return switch (expected) {
      case ALLOW -> response(200, true, null);
      case DENY_401 -> response(401, false, "unauthenticated");
      case DENY_403 -> response(403, false, "forbidden");
    };
  }

  private static PermissionMatrix.Result run(
      OpenApiSpec spec, PermissionMatrix.Sender sender, Set<String> ownCredentials) {
    return PermissionMatrix.run(spec, sender, ownCredentials, ServerErrors.FAIL);
  }

  private static List<String> failures(PermissionMatrix.Sender sender) {
    return run(SPEC, sender, Set.of()).failures();
  }

  @Test
  @DisplayName(
      "T-API-010 the table has one row per operation, four cells, and a correct server passes")
  void correctServer() {
    PermissionMatrix.Result result = run(SPEC, PermissionMatrixGeneratorTest::correct, Set.of());
    assertThat(result.failures()).isEmpty();
    assertThat(result.rows()).hasSize(4);
    assertThat(result.rows()).allSatisfy(row -> assertThat(row.cells()).hasSize(4));
  }

  @Test
  @DisplayName("T-API-010 the requests come from the document: parameters by type, a minimal body")
  void requests() {
    List<RequestFactory.Request> seen = new ArrayList<>();
    run(
        SPEC,
        (identity, request) -> {
          seen.add(request);
          return correct(identity, request);
        },
        Set.of());
    assertThat(seen).hasSize(16);

    assertThat(seen)
        .filteredOn(r -> r.path().startsWith("/api/v1/mine/"))
        .allSatisfy(
            r ->
                assertThat(r.path())
                    .matches(
                        "/api/v1/mine/[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"));
    RequestFactory.Request put =
        seen.stream().filter(r -> r.method().equals("PUT")).findFirst().orElseThrow();
    assertThat(put.path()).isEqualTo("/api/v1/things/1?from=2026-01-01T00%3A00%3A00Z");
    assertThat(put.body()).isEqualTo(Map.of("name", "xxx", "count", 5, "tags", List.of("x")));
    RequestFactory.Request post =
        seen.stream().filter(r -> r.method().equals("POST")).findFirst().orElseThrow();
    assertThat(post.body()).as("no schema: an empty object").isEqualTo(Map.of());
    assertThat(
            seen.stream().filter(r -> r.method().equals("GET")).map(RequestFactory.Request::body))
        .containsOnlyNulls();
  }

  @Test
  @DisplayName("T-SEC-49 a role check that lets a lower role through fails the run")
  void lowerRoleAllowed() {
    assertThat(
            failures(
                (identity, request) ->
                    request.path().startsWith("/api/v1/admin") && identity == Identity.VIEWER
                        ? response(200, true, null)
                        : correct(identity, request)))
        .containsExactly("POST /api/v1/admin as viewer: must be 403 forbidden, got 200 -");
  }

  @Test
  @DisplayName("T-SEC-49 a protected operation that serves anonymous requests fails the run")
  void anonymousAllowed() {
    assertThat(
            failures(
                (identity, request) ->
                    request.path().startsWith("/api/v1/admin") && identity == Identity.ANONYMOUS
                        ? response(200, true, null)
                        : correct(identity, request)))
        .containsExactly("POST /api/v1/admin as anonymous: must be 401, got 200 -");
  }

  @Test
  @DisplayName(
      "T-SEC-49 an allowed role that gets 403 fails the run, also when the handler said it")
  void allowedRoleRefused() {
    assertThat(
            failures(
                (identity, request) ->
                    request.path().startsWith("/api/v1/admin") && identity == Identity.ADMIN
                        ? response(403, false, "forbidden")
                        : correct(identity, request)))
        .containsExactly(
            "POST /api/v1/admin as admin: must reach the handler, got 403 forbidden before it");
    assertThat(
            failures(
                (identity, request) ->
                    request.path().startsWith("/api/v1/admin") && identity == Identity.ADMIN
                        ? response(403, true, "not_owner")
                        : correct(identity, request)))
        .containsExactly("POST /api/v1/admin as admin: must not be 403 not_owner");
  }

  @Test
  @DisplayName(
      "T-SEC-49 a 403 from the handler instead of the role check fails the run: it would have run"
          + " the handler for a role that must not reach it")
  void denyAfterHandler() {
    assertThat(
            failures(
                (identity, request) ->
                    request.path().startsWith("/api/v1/admin") && identity == Identity.VIEWER
                        ? response(403, true, "forbidden")
                        : correct(identity, request)))
        .containsExactly(
            "POST /api/v1/admin as viewer: 403 came from the handler, the role check let it"
                + " through");
  }

  @Test
  @DisplayName(
      "T-SEC-49 an operation that checks its own credential may answer 401, only from the handler;"
          + " a stale list entry fails")
  void ownCredential() {
    PermissionMatrix.Sender handlerSaysNo =
        (identity, request) ->
            request.path().startsWith("/api/v1/open")
                ? response(401, true, "unauthenticated")
                : correct(identity, request);
    assertThat(run(SPEC, handlerSaysNo, Set.of("GET /api/v1/open")).failures()).isEmpty();
    assertThat(run(SPEC, handlerSaysNo, Set.of()).failures()).hasSize(4);

    PermissionMatrix.Sender filterSaysNo =
        (identity, request) ->
            request.path().startsWith("/api/v1/open")
                ? response(403, false, "csrf_invalid")
                : correct(identity, request);
    assertThat(run(SPEC, filterSaysNo, Set.of("GET /api/v1/open")).failures()).hasSize(4);

    assertThat(
            run(SPEC, PermissionMatrixGeneratorTest::correct, Set.of("GET /api/v1/gone"))
                .failures())
        .containsExactly(
            PermissionMatrix.OWN_CREDENTIALS_RESOURCE
                + " lists an unknown operation: GET /api/v1/gone");
  }

  @Test
  @DisplayName(
      "T-SEC-49 a 5xx in an allowed cell fails the run when server errors count (14 §7.1: allowed is"
          + " 2xx or a 4xx other than 403), and only that is tolerated on stubs")
  void serverErrorInAllowedCell() {
    PermissionMatrix.Sender adminGets500 =
        (identity, request) ->
            request.path().startsWith("/api/v1/admin") && identity == Identity.ADMIN
                ? response(500, true, "internal")
                : correct(identity, request);
    assertThat(PermissionMatrix.run(SPEC, adminGets500, Set.of(), ServerErrors.FAIL).failures())
        .containsExactly("POST /api/v1/admin as admin: must not be 5xx, got 500 internal");
    assertThat(PermissionMatrix.run(SPEC, adminGets500, Set.of(), ServerErrors.TOLERATE).failures())
        .isEmpty();

    PermissionMatrix.Sender ownCredential500 =
        (identity, request) ->
            request.path().startsWith("/api/v1/open")
                ? response(500, true, "internal")
                : correct(identity, request);
    assertThat(
            PermissionMatrix.run(
                    SPEC, ownCredential500, Set.of("GET /api/v1/open"), ServerErrors.FAIL)
                .failures())
        .as("the own-credential exception covers 401 and 403 only")
        .hasSize(4)
        .allMatch(failure -> failure.endsWith("must not be 5xx, got 500 internal"));

    PermissionMatrix.Sender deniedGets500 =
        (identity, request) ->
            request.path().startsWith("/api/v1/admin") && identity == Identity.VIEWER
                ? response(500, false, "internal")
                : correct(identity, request);
    assertThat(
            PermissionMatrix.run(SPEC, deniedGets500, Set.of(), ServerErrors.TOLERATE).failures())
        .as("tolerating 5xx never loosens a denied cell")
        .containsExactly("POST /api/v1/admin as viewer: must be 403 forbidden, got 500 internal");
    PermissionMatrix.Sender allowedNotReached500 =
        (identity, request) ->
            request.path().startsWith("/api/v1/admin") && identity == Identity.ADMIN
                ? response(500, false, "internal")
                : correct(identity, request);
    assertThat(
            PermissionMatrix.run(SPEC, allowedNotReached500, Set.of(), ServerErrors.TOLERATE)
                .failures())
        .as("nor an allowed cell whose handler was never reached")
        .containsExactly(
            "POST /api/v1/admin as admin: must reach the handler, got 500 internal before it");
  }

  @Test
  @DisplayName("T-API-001a an operation without a valid x-argos-role fails the run")
  void noRole() {
    OpenApiSpec spec =
        OpenApiSpec.parse(
            "{\"paths\":{\"/a\":{\"get\":{}},\"/b\":{\"get\":{\"x-argos-role\":\"root\"}}}}");
    assertThat(run(spec, (i, r) -> response(200, true, null), Set.of()).failures())
        .containsExactly("GET /a: no valid x-argos-role", "GET /b: no valid x-argos-role");
  }

  @Test
  @DisplayName("the own-credential list names a reason for every entry")
  void listHasReasons() {
    assertThat(PermissionMatrix.ownCredentialReasons())
        .isNotEmpty()
        .allSatisfy(
            (operation, reason) -> {
              assertThat(operation).matches("(GET|POST|PUT|PATCH|DELETE) /api/v1/.+");
              assertThat(reason).isNotBlank();
            });
  }
}
