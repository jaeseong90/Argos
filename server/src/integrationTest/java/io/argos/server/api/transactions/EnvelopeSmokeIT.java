/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.transactions;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.Role;
import io.argos.server.testing.auth.ApiResponse;
import io.argos.server.testing.auth.ApiTestClient;
import io.argos.server.testing.querydata.QueryDataset;
import io.argos.server.testing.querydata.Samples;
import io.argos.servertest.security.matrix.OpenApiSpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The envelope and response contract in a smoke run of the real server (14 §2.5, §9 contract rows
 * "모든 operation의 응답이 스펙 스키마와 일치" and "봉투 필수 필드", DSN-14-04): every GET operation of the live
 * OpenAPI document, plus the two read-only POSTs of the transaction API, is called once as an
 * administrator with values from the shared query test data. On that one run:
 *
 * <ul>
 *   <li>every 2xx JSON response has {@code meta.request_id} (equal to {@code X-Request-Id}), and
 *       every operation that reads a ClickHouse table over a required {@code from}/{@code to} range
 *       (the time-series, statistics and raw range endpoints) has {@code meta.source}; every
 *       ClickHouse operation with a range (required, or reported in {@code meta.from} like a trace
 *       window) has the booleans {@code meta.truncated} and {@code meta.retention_exceeded};
 *   <li>every 2xx JSON response conforms to its response schema in the live document, the server's
 *       generated spec ({@link ResponseSchemaCheck}: types with {@code null}, {@code required},
 *       {@code properties}, {@code items}, {@code $ref});
 *   <li>every ClickHouse statement of the run's requests ran as {@code argos_reader} with the
 *       {@code SETTINGS} limits in its SQL text ({@link QueryLogCheck}, T-ST-053, ADR-0007 C-64).
 * </ul>
 *
 * Other POST, PUT, PATCH and DELETE operations change state and are not called. The transaction
 * operations must each answer 2xx JSON (they are not allowed to be skipped).
 */
@Tag("integration")
@Tag("req:DSN-14-04")
@Tag("req:SEC-49")
class EnvelopeSmokeIT {

  private static final Pattern PATH_PARAMETER = Pattern.compile("\\{([^}]+)}");

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** The operations of the transaction API (WO-23 lane transactions-contract). */
  private static final List<String> TRANSACTION_OPERATIONS =
      List.of(
          "xlog_get",
          "xlog_summarize",
          "transactions_search",
          "transactions_get",
          "transactions_baseline",
          "transactions_export",
          "traces_get",
          "active_get",
          "search_find");

  /**
   * One call of the smoke run.
   *
   * @param op the operation
   * @param response its answer
   * @param range whether the operation takes a required range (query {@code from}/{@code to} or the
   *     body of a read-only POST)
   */
  private record Call(OpenApiSpec.Operation op, ApiResponse response, boolean range) {

    /** A 2xx JSON answer: the calls the envelope and schema checks look at. */
    boolean json() {
      return response.status() / 100 == 2
          && response.header("Content-Type").orElse("").startsWith("application/json");
    }

    String requestId() {
      return response.header("X-Request-Id").orElse("");
    }

    String operationId() {
      return op.node().path("operationId").asString("");
    }
  }

  /** The live OpenAPI document: the generated spec of the running server. */
  private static JsonNode document;

  /** Every call of the run, in the order of the document. */
  private static List<Call> calls;

  /** The GET operations that were not called (no value for a path parameter). */
  private static List<String> notCalled;

  @BeforeAll
  static void smokeRun() {
    QueryDataset data = TxApiFixture.shared().dataset();
    ApiTestClient admin = TxApiFixture.client(Role.ADMIN);
    ApiResponse doc = admin.get("/api/v1/openapi");
    assertThat(doc.status()).isEqualTo(200);
    document = JSON.readTree(doc.body());
    OpenApiSpec spec = OpenApiSpec.parse(doc.body());

    QueryDataset.App app = data.apps().get(0);
    Samples.Transaction tx =
        data.samples().transactions().stream()
            .filter(t -> !t.clamped() && !t.late() && t.appId() == app.appId())
            .findFirst()
            .orElse(data.samples().transactions().get(0));
    Map<String, String> values = new LinkedHashMap<>();
    values.put("appId", Integer.toString(app.appId()));
    values.put("instanceId", Integer.toString(app.instances().get(0).instanceId()));
    values.put("txId", tx.txIdText());
    values.put("traceId", data.samples().traces().get(0).traceIdHex());
    values.put("serviceId", Long.toUnsignedString(tx.serviceId()));
    first(
            "SELECT toString(sql_id) FROM argos.sql_stat_1m WHERE app_id = "
                + app.appId()
                + " LIMIT 1")
        .ifPresent(v -> values.put("sqlId", v));
    first(
            "SELECT toString(host_id) FROM argos.ext_stat_1m WHERE app_id = "
                + app.appId()
                + " LIMIT 1")
        .ifPresent(v -> values.put("hostId", v));
    first(
            "SELECT toString(error_group_id) FROM argos.xlog WHERE app_id = "
                + app.appId()
                + " AND error_group_id != 0 LIMIT 1")
        .ifPresent(v -> values.put("groupId", v));
    Instant to = data.end();
    Instant from = to.minus(Duration.ofMinutes(30));

    List<Call> made = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    for (OpenApiSpec.Operation op : spec.operations()) {
      boolean readOnlyPost =
          op.method().equals("POST")
              && (op.path().endsWith("/xlog/summary")
                  || op.path().endsWith("/transactions/search"));
      if (!op.method().equals("GET") && !readOnlyPost) {
        continue;
      }
      if (op.path().startsWith("/api/v1/openapi")) {
        continue;
      }
      String path = fill(op.path(), values);
      if (path == null) {
        skipped.add(op.key() + " (no value for a path parameter)");
        continue;
      }
      List<String> query = new ArrayList<>();
      // The two read-only POSTs take their required range in the body.
      boolean range = readOnlyPost;
      for (JsonNode p : op.node().path("parameters")) {
        String name = p.path("name").asString();
        if (!p.path("in").asString().equals("query")) {
          continue;
        }
        if (name.equals("from")) {
          query.add("from=" + from);
          // A range endpoint (14 §2.3: from/to required); optional ranges (search) are not.
          range = p.path("required").asBoolean(false);
        } else if (name.equals("to")) {
          query.add("to=" + to);
        } else if (name.equals("q")) {
          query.add("q=orders");
        } else if (name.equals("names")) {
          query.add("names=jvm_threads_live");
        } else if (p.path("required").asBoolean(false)) {
          JsonNode example = p.path("example");
          query.add(
              name
                  + "="
                  + URLEncoder.encode(
                      example.isMissingNode() ? "1" : example.asString(), StandardCharsets.UTF_8));
        }
      }
      String url = query.isEmpty() ? path : path + "?" + String.join("&", query);
      Object body = readOnlyPost ? Map.of("from", from.toString(), "to", to.toString()) : null;
      made.add(new Call(op, admin.send(op.method(), url, body, Map.of()), range));
    }
    calls = List.copyOf(made);
    notCalled = List.copyOf(skipped);
  }

  @Test
  @DisplayName(
      "T-API-001b every 2xx JSON response of a smoke run over the document has meta.request_id;"
          + " ClickHouse range operations have meta.source, meta.truncated and"
          + " meta.retention_exceeded")
  void everyOperationHasTheEnvelope() {
    int checked = 0;
    List<String> problems = new ArrayList<>();
    List<String> skipped = new ArrayList<>(notCalled);
    Set<String> checkedIds = new TreeSet<>();
    for (Call call : calls) {
      OpenApiSpec.Operation op = call.op();
      ApiResponse r = call.response();
      if (!call.json()) {
        skipped.add(op.key() + " -> " + r.status() + " " + r.header("Content-Type").orElse(""));
        continue;
      }
      checked++;
      checkedIds.add(call.operationId());
      JsonNode meta = r.json().path("meta");
      if (!meta.path("request_id").isString()
          || !meta.path("request_id").asString().equals(call.requestId())) {
        problems.add(op.key() + ": meta.request_id missing or not X-Request-Id");
      }
      if (call.range() && readsClickHouse(op) && !meta.path("source").isString()) {
        problems.add(op.key() + ": a ClickHouse range operation without meta.source");
      }
      // 14 §9 "봉투 필수 필드", DSN-14-04: every ClickHouse operation over a range (a required
      // from/to, or a range it chose itself and reports in meta.from, such as a trace window)
      // says whether rows were cut and whether the range reaches past the raw retention.
      if ((call.range() || meta.has("from")) && readsClickHouse(op)) {
        if (!meta.path("truncated").isBoolean()) {
          problems.add(op.key() + ": a ClickHouse range operation without boolean meta.truncated");
        }
        if (!meta.path("retention_exceeded").isBoolean()) {
          problems.add(
              op.key() + ": a ClickHouse range operation without boolean meta.retention_exceeded");
        }
      }
    }
    System.out.println("EnvelopeSmokeIT: checked " + checked + ", not 2xx JSON: " + skipped);
    assertThat(problems).isEmpty();
    // The transaction operations (this lane) must each have answered 2xx JSON, not been skipped.
    assertThat(checkedIds)
        .as("transaction API operations checked (skipped: %s)", skipped)
        .containsAll(TRANSACTION_OPERATIONS);
    assertThat(checked).as("2xx JSON responses checked").isGreaterThanOrEqualTo(20);
  }

  @Test
  @Tag("req:O-R03")
  @DisplayName(
      "T-API-001c every 2xx JSON response of the smoke run conforms to its response schema in the"
          + " live (generated) OpenAPI document: type with null, required, properties, items and"
          + " $ref (14 §9 contract row)")
  void everyResponseMatchesItsSchema() {
    ResponseSchemaCheck check = new ResponseSchemaCheck(document);
    List<String> problems = new ArrayList<>();
    Set<String> checkedIds = new TreeSet<>();
    for (Call call : calls) {
      if (!call.json()) {
        continue;
      }
      JsonNode operation =
          document
              .path("paths")
              .path(call.op().path())
              .path(call.op().method().toLowerCase(Locale.ROOT));
      int status = call.response().status();
      JsonNode schema = ResponseSchemaCheck.responseSchema(operation, status);
      if (schema.isMissingNode() || schema.isEmpty()) {
        problems.add(call.op().key() + ": no application/json schema for status " + status);
        continue;
      }
      checkedIds.add(call.operationId());
      for (String problem : check.check(call.response().json(), schema)) {
        problems.add(call.op().key() + " " + problem);
      }
    }
    System.out.println(
        "EnvelopeSmokeIT: " + checkedIds.size() + " responses checked against their schema");
    assertThat(problems).isEmpty();
    assertThat(checkedIds)
        .as("transaction API operations checked against their schema")
        .containsAll(TRANSACTION_OPERATIONS);
    assertThat(checkedIds).as("responses checked against their schema").hasSizeGreaterThan(19);
  }

  @Test
  @Tag("req:SEC-76")
  @DisplayName(
      "T-ST-053b every ClickHouse statement of the smoke run's requests (system.query_log,"
          + " query_id <request id>-<n>) ran as argos_reader with SETTINGS max_execution_time in"
          + " the SQL text and max_execution_time, max_rows_to_read, max_result_rows and"
          + " max_memory_usage set")
  void everyStatementIsLimited() throws InterruptedException {
    // The request id correlates by query_id (ADR-0010 C-174: no log_comment); this test checks
    // the query id, the account and the limits.
    Map<String, Call> byRequest = new LinkedHashMap<>();
    List<String> mustAppear = new ArrayList<>();
    for (Call call : calls) {
      if (call.requestId().isEmpty()) {
        continue;
      }
      byRequest.put(call.requestId(), call);
      // The transaction detail reads raw xlog rows (no cache): its statements must be logged.
      if (call.json() && call.operationId().equals("transactions_get")) {
        mustAppear.add(call.requestId());
      }
    }
    assertThat(mustAppear).as("the transaction detail answered 2xx JSON").isNotEmpty();
    // Operations that read no ClickHouse table (PostgreSQL or memory only, cached answers) have
    // no statement; every statement that is there must be limited.
    Map<String, List<QueryLogCheck.Row>> rows = QueryLogCheck.rows(byRequest.keySet(), mustAppear);
    List<String> problems = new ArrayList<>();
    int statements = 0;
    for (Map.Entry<String, List<QueryLogCheck.Row>> e : rows.entrySet()) {
      Call call = byRequest.get(e.getKey());
      statements += e.getValue().size();
      problems.addAll(
          QueryLogCheck.problems(call == null ? e.getKey() : call.op().key(), e.getValue()));
    }
    System.out.println(
        "EnvelopeSmokeIT: "
            + statements
            + " ClickHouse statements of "
            + rows.size()
            + " of "
            + byRequest.size()
            + " requests checked in system.query_log");
    assertThat(problems).isEmpty();
    assertThat(rows.keySet()).as("requests with ClickHouse statements").containsAll(mustAppear);
    assertThat(rows).as("requests with ClickHouse statements").hasSizeGreaterThanOrEqualTo(10);
  }

  private static boolean readsClickHouse(OpenApiSpec.Operation op) {
    for (JsonNode table : op.node().path("x-argos-source")) {
      String t = table.asString();
      if (!t.startsWith("postgres:") && !t.startsWith("memory:")) {
        return true;
      }
    }
    return false;
  }

  private static String fill(String path, Map<String, String> values) {
    Matcher m = PATH_PARAMETER.matcher(path);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String value = values.get(m.group(1));
      if (value == null) {
        return null;
      }
      m.appendReplacement(out, Matcher.quoteReplacement(value));
    }
    m.appendTail(out);
    return out.toString();
  }

  private static Optional<String> first(String sql) {
    List<List<String>> rows = TxApiFixture.reader(sql);
    return rows.isEmpty() || rows.get(0).isEmpty()
        ? Optional.empty()
        : Optional.of(rows.get(0).get(0));
  }
}
