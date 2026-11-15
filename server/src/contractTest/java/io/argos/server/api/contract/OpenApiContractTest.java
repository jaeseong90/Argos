/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.contract;

import static java.util.stream.Collectors.toSet;
import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.api.openapi.ArgosRecordSchemaConverter;
import io.argos.server.api.openapi.ArgosUnsignedIdCustomizer;
import io.argos.server.api.openapi.ContractServer;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.servertest.security.matrix.OpenApiSpec;
import io.argos.servertest.security.matrix.PermissionMatrix;
import io.argos.servertest.security.matrix.PermissionMatrix.Identity;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The committed OpenAPI document (ADR-0003 C-28, ADR-0010 C-151; 14 §2.8, §2.9, §8): it equals a
 * fresh generation, it passes the 14 §2.9 lint ({@link OpenApiLint}), the lint is not vacuous, the
 * WO-23 operations carry their extensions, and the permission matrix generated from the committed
 * file holds (T-API-010, C-110: the generator reads the committed spec too).
 */
@Tag("req:SEC-49")
@Tag("req:DSN-14-10")
@Tag("req:O-R03")
class OpenApiContractTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private static ContractServer server;
  private static String generated;
  private static JsonNode document;

  @BeforeAll
  static void start() {
    server = ContractServer.start();
    generated = OpenApiDocument.generate(server);
    document = OpenApiDocument.readYaml(generated);
  }

  @AfterAll
  static void stop() {
    if (server != null) {
      server.close();
    }
  }

  @Test
  @DisplayName(
      "T-API-001 the committed docs/api/openapi.yaml equals the generated document (run"
          + " ./gradlew :server:generateOpenApi after an API change)")
  void committedEqualsGenerated() {
    String committed = OpenApiDocument.readCommitted();
    assertThat(committed)
        .as(OpenApiDocument.COMMITTED + " is missing: run ./gradlew :server:generateOpenApi")
        .isNotNull();
    if (!committed.equals(generated)) {
      String[] a = committed.split("\n", -1);
      String[] b = generated.split("\n", -1);
      int line = 0;
      while (line < Math.min(a.length, b.length) && a[line].equals(b[line])) {
        line++;
      }
      throw new AssertionError(
          OpenApiDocument.COMMITTED
              + " differs from the generated document at line "
              + (line + 1)
              + ": run ./gradlew :server:generateOpenApi and commit the result\n  committed: "
              + (line < a.length ? a[line] : "<end>")
              + "\n  generated: "
              + (line < b.length ? b[line] : "<end>"));
    }
  }

  @Test
  @DisplayName(
      "T-API-001 14 §2.9 lint: x-argos-role everywhere, x-argos-audit on state changes (16 §11.1"
          + " names or none), 2xx schemas, operationId <resource>_<verb>, x-argos-untrusted on"
          + " collected strings, no writes under /admin/audit, 2xx content application/json or"
          + " text/csv (text/csv on x-argos-csv), required on every response object, no component"
          + " reachable from both a request body and a 2xx response, unsigned 64-bit keys as"
          + " strings with pattern ^[0-9]{1,20}$ (rule 10, ADR-0010 C-175)")
  void lint() {
    assertThat(document.path("openapi").asString()).startsWith("3.");
    assertThat(document.path("info").path("version").asString())
        .isEqualTo(OpenApiDocument.API_VERSION);
    assertThat(OpenApiLint.check(document)).isEmpty();
  }

  @Test
  @DisplayName(
      "T-API-001 rule 10 (ADR-0010 C-175): every listed 64-bit key occurs in the document, the"
          + " generator (ArgosUnsignedIdCustomizer) and the lint use the same names, and the"
          + " document carries the pattern on response, request, nullable and array forms")
  void unsignedIdsAreStrings() {
    assertThat(OpenApiLint.UINT64_PROPERTIES)
        .as("lint rule 10 names = ArgosUnsignedIdCustomizer.PROPERTIES")
        .isEqualTo(ArgosUnsignedIdCustomizer.PROPERTIES);
    assertThat(OpenApiLint.UINT64_PATTERN).isEqualTo(ArgosUnsignedIdCustomizer.PATTERN);
    assertThat(
            OpenApiLint.UINT64_PARAMETERS.stream().filter(p -> !p.contains(" ")).collect(toSet()))
        .as("lint rule 10 parameter names = ArgosUnsignedIdCustomizer.PARAMETERS")
        .isEqualTo(ArgosUnsignedIdCustomizer.PARAMETERS);
    JsonNode schemas = document.path("components").path("schemas");
    Set<String> found = new TreeSet<>();
    for (Map.Entry<String, JsonNode> schema : schemas.properties()) {
      for (Map.Entry<String, JsonNode> property :
          schema.getValue().path("properties").properties()) {
        if (OpenApiLint.UINT64_PROPERTIES.contains(property.getKey())) {
          found.add(property.getKey());
        }
        if (OpenApiLint.UINT64_SCHEMA_PROPERTIES.contains(
            schema.getKey() + "." + property.getKey())) {
          found.add(schema.getKey() + "." + property.getKey());
        }
      }
    }
    Set<String> listed = new TreeSet<>(OpenApiLint.UINT64_PROPERTIES);
    listed.addAll(OpenApiLint.UINT64_SCHEMA_PROPERTIES);
    assertThat(found).as("rule 10 names that occur in the document").isEqualTo(listed);
    String pattern = ArgosUnsignedIdCustomizer.PATTERN;
    JsonNode detailTx = schemas.path("TransactionDetail").path("properties").path("tx_id");
    assertThat(detailTx.path("type").asString()).isEqualTo("string");
    assertThat(detailTx.path("pattern").asString()).isEqualTo(pattern);
    JsonNode caller = schemas.path("TransactionCaller").path("properties").path("tx_id");
    assertThat(caller.path("type").toString()).contains("null");
    assertThat(caller.path("pattern").asString()).isEqualTo(pattern);
    assertThat(
            schemas
                .path("XlogPoints")
                .path("properties")
                .path("tx_id")
                .path("items")
                .path("pattern")
                .asString())
        .isEqualTo(pattern);
    assertThat(
            schemas
                .path("TransactionSearchFilters")
                .path("properties")
                .path("service_id")
                .path("items")
                .path("pattern")
                .asString())
        .as("request form")
        .isEqualTo(pattern);
    assertThat(
            schemas.path("StatsBreakdown").path("properties").path("id").path("pattern").asString())
        .isEqualTo(pattern);
    assertThat(schemas.path("App").path("properties").path("app_id").path("type").asString())
        .as("a key below 2^53 stays a number")
        .isEqualTo("integer");
  }

  @Test
  @DisplayName("T-API-001 the lint catches each rule's violation (not vacuous)")
  void lintIsNotVacuous() {
    ObjectNode copy = (ObjectNode) document.deepCopy();
    ObjectNode paths = (ObjectNode) copy.path("paths");
    ((ObjectNode) paths.path("/api/v1/transactions/{txId}").path("get")).remove("x-argos-role");
    ((ObjectNode) paths.path("/api/v1/apps/{appId}/xlog/summary").path("post"))
        .remove("x-argos-audit");
    ((ObjectNode) paths.path("/api/v1/apps/{appId}/transactions/search").path("post"))
        .put("x-argos-audit", "transaction.export");
    ((ObjectNode) paths.path("/api/v1/traces/{traceId}").path("get"))
        .put("operationId", "getTrace");
    ((ObjectNode) paths.path("/api/v1/search").path("get")).remove("responses");
    ((ObjectNode) paths.path("/api/v1/search").path("get"))
        .putObject("responses")
        .putObject("200")
        .put("description", "a body without a schema")
        .putObject("content")
        .putObject("application/json");
    ObjectNode audit = paths.putObject("/api/v1/admin/audit/{id}");
    ObjectNode delete = audit.putObject("delete");
    delete.put("x-argos-role", "admin");
    delete.put("x-argos-audit", "none");
    delete.put("operationId", "audit_delete");
    delete.putObject("responses").putObject("204").put("description", "gone");
    ((ObjectNode)
            copy.path("components")
                .path("schemas")
                .path("TransactionDetail")
                .path("properties")
                .path("service"))
        .remove("x-argos-untrusted");
    // Rule 7: a */* response and a CSV operation without text/csv (api-contract-security-4).
    ObjectNode xlogContent =
        (ObjectNode)
            paths
                .path("/api/v1/apps/{appId}/xlog")
                .path("get")
                .path("responses")
                .path("200")
                .path("content");
    xlogContent.set("*/*", xlogContent.path("application/json").deepCopy());
    ((ObjectNode)
            paths
                .path("/api/v1/apps/{appId}/transactions/search")
                .path("post")
                .path("responses")
                .path("200")
                .path("content"))
        .remove("text/csv");
    // Rule 8: a response object without required properties (api-contract-security-1).
    ((ObjectNode) copy.path("components").path("schemas").path("QueryMeta")).remove("required");
    // Rule 9: a request body component that a 2xx response also reaches (r1-new-1).
    ((ObjectNode)
            copy.path("components")
                .path("schemas")
                .path("TransactionSearchResponse")
                .path("properties"))
        .putObject("echo")
        .put("$ref", "#/components/schemas/TransactionSearchFilters");
    // Rule 10: 64-bit keys as a number, a nullable number, strings without the pattern (array
    // items, a request property, a generic name, a path parameter) (ADR-0010 C-175).
    ObjectNode schemas = (ObjectNode) copy.path("components").path("schemas");
    ((ObjectNode) schemas.path("TransactionDetail").path("properties"))
        .putObject("tx_id")
        .put("type", "integer")
        .put("format", "int64");
    ObjectNode callerTx =
        (ObjectNode) schemas.path("TransactionCaller").path("properties").path("tx_id");
    callerTx.putArray("type").add("integer").add("null");
    callerTx.remove("pattern");
    ((ObjectNode) schemas.path("XlogPoints").path("properties").path("tx_id").path("items"))
        .remove("pattern");
    ((ObjectNode) schemas.path("TransactionSearchFilters").path("properties").path("sql_id"))
        .remove("pattern");
    ((ObjectNode) schemas.path("StatsBreakdown").path("properties").path("id")).remove("pattern");
    for (JsonNode parameter :
        paths.path("/api/v1/transactions/{txId}").path("get").path("parameters")) {
      if (parameter.path("name").asString("").equals("txId")) {
        ((ObjectNode) parameter.path("schema")).remove("pattern");
      }
    }

    List<String> problems = OpenApiLint.check(copy);
    assertThat(problems)
        .anyMatch(p -> p.startsWith("GET /api/v1/transactions/{txId}: x-argos-role"))
        .anyMatch(p -> p.startsWith("POST /api/v1/apps/{appId}/xlog/summary: x-argos-audit"))
        .anyMatch(p -> p.contains("transaction.export is not a 16 §11.1 event"))
        .anyMatch(p -> p.contains("operationId 'getTrace'"))
        .anyMatch(
            p -> p.startsWith("GET /api/v1/search: response 200 application/json has no schema"))
        .anyMatch(p -> p.startsWith("DELETE /api/v1/admin/audit/{id}: /admin/audit is read-only"))
        .anyMatch(p -> p.contains("TransactionDetail.service: collected string"))
        .anyMatch(
            p ->
                p.startsWith(
                    "GET /api/v1/apps/{appId}/xlog: response 200 media type '*/*' is not"
                        + " application/json or text/csv"))
        .anyMatch(
            p ->
                p.startsWith(
                    "POST /api/v1/apps/{appId}/transactions/search: x-argos-csv without a"
                        + " text/csv 2xx response"))
        .anyMatch(p -> p.startsWith("schema QueryMeta: response object without required"))
        .anyMatch(
            p ->
                p.startsWith(
                    "schema TransactionSearchFilters: reachable from a request body and a 2xx"
                        + " response"))
        .anyMatch(p -> p.startsWith("schema TransactionDetail.tx_id: unsigned 64-bit key"))
        .anyMatch(p -> p.startsWith("schema TransactionCaller.tx_id: unsigned 64-bit key"))
        .anyMatch(p -> p.startsWith("schema XlogPoints.tx_id: unsigned 64-bit key"))
        .anyMatch(p -> p.startsWith("schema TransactionSearchFilters.sql_id: unsigned 64-bit key"))
        .anyMatch(p -> p.startsWith("schema StatsBreakdown.id: unsigned 64-bit key"))
        .anyMatch(
            p ->
                p.startsWith(
                    "GET /api/v1/transactions/{txId}: parameter txId: unsigned 64-bit key"));
  }

  @Test
  @DisplayName(
      "T-API-002a request body schemas of the committed document keep only their explicit required"
          + " properties and no added null; required lists the fields the server always rejects"
          + " with 400 invalid_request (reason required) when missing, conditional ones are"
          + " described instead (ADR-0010 C-179)")
  void requestBodySchemas() {
    String committed = OpenApiDocument.readCommitted();
    assertThat(committed).as(OpenApiDocument.COMMITTED).isNotNull();
    assertThat(committed).doesNotContain(ArgosRecordSchemaConverter.REQUEST_FORM);
    JsonNode schemas = OpenApiDocument.readYaml(committed).path("components").path("schemas");
    // C-179 (a criterion change decided by the PM, not a weakening): before it the WO-24 request
    // schemas had no required list although the server checks these fields unconditionally
    // (LoginService, PasswordChangeService, SetupService, UserAdministration,
    // AdminUsersController).
    Map<String, List<String>> expected = new LinkedHashMap<>();
    expected.put("TransactionSearchRequest", List.of());
    expected.put("TransactionSearchFilters", List.of());
    expected.put("PatchUserRequest", List.of());
    expected.put("KioskSessionPatch", List.of());
    expected.put("CreateUserRequest", List.of("roles", "username"));
    expected.put("SetupAdminRequest", List.of("ip_mode", "password", "username"));
    expected.put("LoginRequest", List.of("password", "username"));
    expected.put("PasswordChangeRequest", List.of("current", "new"));
    expected.put("ResetPasswordRequest", List.of("password"));
    expected.put("XlogSummaryRequest", List.of("from", "to"));
    expected.forEach(
        (name, required) -> {
          JsonNode schema = schemas.path(name);
          assertThat(schema.path("properties").isEmpty()).as(name + " has properties").isFalse();
          List<String> actual = new ArrayList<>();
          schema.path("required").forEach(r -> actual.add(r.asString()));
          assertThat(actual).as(name + ".required").containsExactlyElementsOf(required);
          for (Map.Entry<String, JsonNode> property : schema.path("properties").properties()) {
            assertThat(property.getValue().path("type").toString())
                .as(name + "." + property.getKey() + " type")
                .doesNotContain("\"null\"");
            assertThat(property.getValue().has("anyOf"))
                .as(name + "." + property.getKey() + " anyOf with null")
                .isFalse();
          }
        });
    // Conditional requirements are in the descriptions, not in required (C-179 (2)).
    for (List<String> conditional :
        List.of(
            List.of("SetupAdminRequest", "setup_token"),
            List.of("KioskSessionPatch", "allowed"),
            List.of("KioskSessionPatch", "ip_cidrs"),
            List.of("TransactionSearchRequest", "from"),
            List.of("TransactionSearchRequest", "to"))) {
      assertThat(
              schemas
                  .path(conditional.get(0))
                  .path("properties")
                  .path(conditional.get(1))
                  .path("description")
                  .asString())
          .as(conditional + " describes when it is required")
          .contains("equired");
    }
    // The nested request record has the request form too, and the response form stays.
    assertThat(
            schemas
                .path("TransactionSearchRequest")
                .path("properties")
                .path("filters")
                .path("$ref")
                .asString())
        .isEqualTo("#/components/schemas/TransactionSearchFilters");
    assertThat(schemas.path("TransactionSearchResponse").path("required").toString())
        .contains("data")
        .contains("meta");
  }

  @Test
  @Tag("req:SEC-56")
  @Tag("req:SEC-63")
  @DisplayName(
      "T-API-001 the transaction operations carry x-argos-csv, -limits, -source, -pii and -audit")
  void transactionExtensions() {
    JsonNode paths = document.path("paths");
    JsonNode search = paths.path("/api/v1/apps/{appId}/transactions/search").path("post");
    assertThat(search.path("operationId").asString()).isEqualTo("transactions_search");
    assertThat(search.path("x-argos-csv").asBoolean()).isTrue();
    assertThat(search.path("x-argos-audit").asString()).isEqualTo("EXPORT_CSV");
    assertThat(search.path("x-argos-limits").path("rows_max").asInt()).isEqualTo(1000);
    assertThat(search.path("x-argos-limits").path("range_max").asString()).isEqualTo("24h");
    JsonNode detail = paths.path("/api/v1/transactions/{txId}").path("get");
    assertThat(detail.path("x-argos-pii").asString()).isEqualTo("view");
    assertThat(detail.path("x-argos-audit").asString()).isEqualTo("SENSITIVE_VIEW");
    assertThat(detail.path("x-argos-role").asString()).isEqualTo("viewer");
    JsonNode export = paths.path("/api/v1/transactions/{txId}/export").path("get");
    assertThat(export.path("x-argos-audit").asString()).isEqualTo("EXPORT_CSV,SENSITIVE_VIEW");
    JsonNode xlog = paths.path("/api/v1/apps/{appId}/xlog").path("get");
    assertThat(xlog.path("x-argos-limits").path("rows_max").asInt()).isEqualTo(100_000);
    assertThat(xlog.path("x-argos-source").path(0).asString()).isEqualTo("xlog");
    assertThat(
            paths
                .path("/api/v1/apps/{appId}/xlog/summary")
                .path("post")
                .path("x-argos-audit")
                .asString())
        .isEqualTo("none");
    assertThat(
            paths
                .path("/api/v1/traces/{traceId}")
                .path("get")
                .path("x-argos-limits")
                .path("range_max")
                .asString())
        .isEqualTo("30d");
    JsonNode detailSchema = document.path("components").path("schemas").path("TransactionDetail");
    assertThat(
            detailSchema.path("properties").path("service").path("x-argos-untrusted").asBoolean())
        .isTrue();
    assertThat(detailSchema.path("properties").path("tx_id").path("type").toString())
        .contains("string");
    assertThat(detailSchema.path("required").toString()).contains("tx_id").contains("profile");
  }

  @Test
  @DisplayName(
      "T-API-001 every operation that reads a store (x-argos-source names a table) carries"
          + " x-argos-limits (14 §2.8); only memory-only operations have none")
  void storeReadsCarryLimits() {
    List<String> missing = new ArrayList<>();
    int reads = 0;
    for (Map.Entry<String, JsonNode> path : document.path("paths").properties()) {
      for (Map.Entry<String, JsonNode> item : path.getValue().properties()) {
        JsonNode op = item.getValue();
        boolean readsStore = false;
        for (JsonNode source : op.path("x-argos-source")) {
          readsStore |= !source.asString("").startsWith("memory:");
        }
        if (!readsStore) {
          continue;
        }
        reads++;
        JsonNode limits = op.path("x-argos-limits");
        if (!limits.isObject() || limits.isEmpty()) {
          missing.add(item.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey());
        }
      }
    }
    assertThat(reads).as("operations with x-argos-source").isGreaterThan(20);
    assertThat(missing).as("store-reading operations without x-argos-limits").isEmpty();
  }

  @Test
  @DisplayName(
      "T-API-010 the permission matrix generated from the committed docs/api/openapi.yaml: every"
          + " operation x anonymous and the three roles; allowed reaches the handler, denied is"
          + " 401/403 before it")
  void permissionMatrixOfTheCommittedSpec() {
    String committed = OpenApiDocument.readCommitted();
    assertThat(committed).as(OpenApiDocument.COMMITTED).isNotNull();
    OpenApiSpec spec =
        OpenApiSpec.parse(JSON.writeValueAsString(OpenApiDocument.readYaml(committed)));
    assertThat(spec.operations()).as("operations of the committed spec").hasSizeGreaterThan(20);
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
    result.writeReport(
        OpenApiDocument.repositoryRoot()
            .resolve(Path.of("server", "build", "reports", "permission-matrix-committed.md")),
        "Permission matrix (committed docs/api/openapi.yaml, contract web layer)");
    assertThat(result.failures()).isEmpty();
    assertThat(result.rows()).hasSameSizeAs(spec.operations());
  }
}
