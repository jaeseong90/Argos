/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.contract;

import io.argos.server.api.openapi.ArgosAudit;
import io.argos.server.audit.AuditEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/**
 * The lint rules of 14 §2.9 for the OpenAPI document (T-API-001; ADR-0010 C-151: JUnit in {@code
 * check} instead of Spectral, C-132 (3) for {@code x-argos-audit}). {@link #check} returns one line
 * per violation:
 *
 * <ol>
 *   <li>{@code x-argos-role} on every operation: {@code public}, {@code viewer}, {@code operator}
 *       or {@code admin} (SEC-49);
 *   <li>{@code x-argos-audit} on every POST, PUT, PATCH and DELETE; every value is the 16 §11.1
 *       event names of {@link AuditEvent} joined by commas, or {@code none} (C-132 (3));
 *   <li>a 2xx response on every operation, and a schema for every media type of a 2xx response (a
 *       response without a body, such as 204, declares no media type);
 *   <li>{@code operationId} on every operation, of the form {@code <resource>_<verb>} (snake case,
 *       at least two words) and unique;
 *   <li>{@code x-argos-untrusted: true} on the response properties that hold collected strings, by
 *       name ({@link #COLLECTED}; 14 §2.10 lists the kinds): every schema reachable from a 2xx
 *       response, except the envelope's {@code meta} (server values) and request bodies (input);
 *   <li>no PUT, PATCH or DELETE under {@code /api/v1/admin/audit} (SEC-82);
 *   <li>a 2xx response declares {@code application/json} (14 §7.4) or {@code text/csv} (14 §4.17)
 *       content only, never {@code *}{@code /*}; an operation with {@code x-argos-csv: true} has a
 *       {@code text/csv} content in a 2xx response;
 *   <li>every object schema reachable from a 2xx response (component schemas with properties, the
 *       envelope's {@code meta} included) has a {@code required} list: oasdiff reports the removal
 *       of a required response property only (O-R03), so an object without one could lose any
 *       property unnoticed. Exceptions are listed in {@link #REQUIRED_EXEMPT} with their reason;
 *   <li>no component schema is reachable from both a request body and a 2xx response: response
 *       schemas mark the always-written properties {@code required} and the nullable ones {@code
 *       null} (ArgosRecordSchemaConverter), which a request body must not say, so the request and
 *       the response have records of their own (review finding r1-new-1; the generator fails such a
 *       document too: ArgosRequestSchemaCustomizer).
 *   <li>every property that holds unsigned 64-bit keys ({@link #UINT64_PROPERTIES} by name in every
 *       schema, request and response, inline objects included; {@link #UINT64_SCHEMA_PROPERTIES}
 *       for generic names) is a JSON string with {@code pattern: '^[0-9]{1,20}$'}: the nullable
 *       form {@code type: [string, 'null']} and arrays whose items are such strings count; any
 *       other type (an integer, a {@code $ref}) fails. The path and query parameters of the same
 *       keys ({@link #UINT64_PARAMETERS}) too (ADR-0010 C-175: JSON numbers lose 64-bit values
 *       above 2^53 in a browser, so a generated client must keep them as strings).
 * </ol>
 */
public final class OpenApiLint {

  /**
   * Response object schemas that may have no {@code required} property, with the reason (rule 8).
   * Empty: every response object has at least one property that is always written.
   */
  static final Map<String, String> REQUIRED_EXEMPT = Map.of();

  /** The media types of 2xx response content (rule 7). */
  static final Set<String> RESPONSE_MEDIA_TYPES = Set.of("application/json", "text/csv");

  /** Roles of {@code x-argos-role}. */
  static final Set<String> ROLES = Set.of("public", "viewer", "operator", "admin");

  /** {@code <resource>_<verb>}. */
  static final Pattern OPERATION_ID = Pattern.compile("[a-z][a-z0-9]*(_[a-z0-9]+)+");

  /**
   * Property names that hold collected strings (14 §2.10: service names, URLs, SQL, parameters,
   * exceptions, stacks, headers, user ids, instance, host and thread names, dictionary texts; also
   * the HTTP method, application and environment names and versions that agents report) in every
   * schema of the document. Generic names such as {@code name} are not listed: they also name
   * server values (disks, metrics); such properties carry the extension by their DTO annotation.
   */
  static final Set<String> COLLECTED =
      Set.of(
          "service",
          "url",
          "url_path",
          "sql",
          "sql_text",
          "sql_normalized",
          "params",
          "error_class",
          "message_pattern",
          "top_frame",
          "sample_stack",
          "frames",
          "thread",
          "user_id",
          "request_id",
          "headers",
          "host",
          "path_pattern",
          "state_detail",
          "label",
          "http_method",
          "app",
          "env",
          "instance",
          "version",
          "target");

  /** The pattern of an unsigned 64-bit decimal (rule 10). */
  static final String UINT64_PATTERN = "^[0-9]{1,20}$";

  /**
   * Property names that hold unsigned 64-bit keys in every schema of the document (rule 10,
   * ADR-0010 C-175), with the evidence in the code:
   *
   * <ul>
   *   <li>transaction ids: {@code tx_id} is the Snowflake-like id with a 41-bit time prefix (10
   *       §9.5, D-11, C-121; {@code xlog.tx_id UInt64}, written by {@code Ids.text}); its
   *       references {@code parent_tx_id} (TraceNode, TraceMissing), {@code caller_tx_id}
   *       (TraceExternalNode), {@code root_tx_id} (Trace), {@code sample_normal_tx_id}
   *       (TransactionBaseline), and {@code critical_path} (Trace: the {@code tx_id}s from the
   *       root, {@code TraceService});
   *   <li>dictionary keys, xxh3_64 content hashes (11 §7; {@code UInt64} columns of V001 written by
   *       {@code Ids.text}, {@code Ids.keyOrNull} or {@code Values.id}): {@code service_id} ({@code
   *       xlog.service_id}), {@code version_id} ({@code app_version_id}), {@code error_class_id},
   *       {@code sql_id} ({@code sql_stat_*.sql_id}, {@code xlog.sql_ids}), {@code db_id} ({@code
   *       sql_stat_*.db_id}), {@code host_id} ({@code ext_stat_*.host_id}) and {@code name_id}
   *       ({@code profile_step.name_id}, TransactionStep);
   *   <li>the error group fingerprint {@code xxh3_64(...)} ({@code ErrorGroupFingerprinter}, C-131;
   *       {@code xlog.error_group_id UInt64}): {@code error_group_id} and {@code group_id}
   *       (ErrorGroup).
   * </ul>
   *
   * Not listed: {@code trace_id} (128 bits, {@code FixedString(16)}, 32 hex digits, W3C), {@code
   * span_id} and {@code parent_span_id} (64 bits, but written as 16 hex digits, W3C: {@code
   * Ids.spanHex}), {@code event_id} and the user {@code id} (UUIDs), {@code request_id} (a
   * collected header value), {@code app_id}, {@code instance_id} and {@code registered_from_app_id}
   * ({@code UInt32}: JSON numbers), the keys of {@code XlogDictionary} maps (JSON object keys,
   * strings anyway) and the {@code params} map of {@code SearchTarget} (route parameters of several
   * kinds).
   */
  static final Set<String> UINT64_PROPERTIES =
      Set.of(
          "tx_id",
          "parent_tx_id",
          "caller_tx_id",
          "root_tx_id",
          "sample_normal_tx_id",
          "critical_path",
          "service_id",
          "version_id",
          "error_class_id",
          "sql_id",
          "db_id",
          "host_id",
          "name_id",
          "error_group_id",
          "group_id");

  /**
   * {@code <schema>.<property>} of generic names that hold unsigned 64-bit keys (rule 10): {@code
   * StatsBreakdown.id} (the key of a breakdown row: service, SQL, host, version or error group key,
   * or an instance id as text, {@code Values.id}) and {@code XlogSummaryRequest.service} (service
   * keys, parsed by {@code Ids.parseUnsigned}).
   */
  static final Set<String> UINT64_SCHEMA_PROPERTIES =
      Set.of("StatsBreakdown.id", "XlogSummaryRequest.service");

  /**
   * Path and query parameters that carry unsigned 64-bit keys (rule 10): by name, or {@code
   * <METHOD> <path> <name>} for a generic name ({@code service} of the XLog points: service keys).
   */
  static final Set<String> UINT64_PARAMETERS =
      Set.of(
          "txId",
          "serviceId",
          "sqlId",
          "hostId",
          "groupId",
          "db_id",
          "GET /api/v1/apps/{appId}/xlog service");

  /** The end of a rule 10 problem. */
  private static final String UINT64_PROBLEM =
      ": unsigned 64-bit key is not a string with pattern '"
          + UINT64_PATTERN
          + "' (ADR-0010 C-175)";

  private static final Set<String> UNSAFE = Set.of("post", "put", "patch", "delete");
  private static final Set<String> METHODS =
      Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

  private OpenApiLint() {}

  /** The violations of {@code document}; empty when it passes. */
  public static List<String> check(JsonNode document) {
    List<String> problems = new ArrayList<>();
    Set<String> events =
        Arrays.stream(AuditEvent.values()).map(Enum::name).collect(Collectors.toSet());
    Set<String> operationIds = new HashSet<>();
    List<JsonNode> responseSchemas = new ArrayList<>();
    List<JsonNode> requestSchemas = new ArrayList<>();
    for (Map.Entry<String, JsonNode> path : document.path("paths").properties()) {
      for (Map.Entry<String, JsonNode> item : path.getValue().properties()) {
        String method = item.getKey();
        if (!METHODS.contains(method)) {
          continue;
        }
        String key = method.toUpperCase(java.util.Locale.ROOT) + " " + path.getKey();
        JsonNode op = item.getValue();
        String role = op.path("x-argos-role").asString("");
        if (!ROLES.contains(role)) {
          problems.add(key + ": x-argos-role missing or unknown (" + role + ")");
        }
        JsonNode audit = op.path("x-argos-audit");
        if (UNSAFE.contains(method) && (audit.isMissingNode() || audit.asString("").isBlank())) {
          problems.add(key + ": x-argos-audit missing on a state-changing operation");
        }
        if (!audit.isMissingNode()) {
          String value = audit.asString("");
          if (!value.equals(ArgosAudit.NONE)) {
            for (String event : value.split(",", -1)) {
              if (!events.contains(event)) {
                problems.add(key + ": x-argos-audit value " + event + " is not a 16 §11.1 event");
              }
            }
          }
        }
        for (Map.Entry<String, JsonNode> media :
            op.path("requestBody").path("content").properties()) {
          requestSchemas.add(media.getValue().path("schema"));
        }
        boolean success = false;
        boolean csv = false;
        for (Map.Entry<String, JsonNode> response : op.path("responses").properties()) {
          if (!response.getKey().startsWith("2")) {
            continue;
          }
          success = true;
          JsonNode content = response.getValue().path("content");
          for (Map.Entry<String, JsonNode> media : content.properties()) {
            if (!RESPONSE_MEDIA_TYPES.contains(media.getKey())) {
              problems.add(
                  key
                      + ": response "
                      + response.getKey()
                      + " media type '"
                      + media.getKey()
                      + "' is not application/json or text/csv");
            }
            csv |= media.getKey().equals("text/csv");
            JsonNode schema = media.getValue().path("schema");
            if (schema.isMissingNode() || schema.isEmpty()) {
              problems.add(
                  key
                      + ": response "
                      + response.getKey()
                      + " "
                      + media.getKey()
                      + " has no schema");
            } else {
              responseSchemas.add(schema);
            }
          }
        }
        if (!success) {
          problems.add(key + ": no 2xx response");
        }
        if (op.path("x-argos-csv").asBoolean(false) && !csv) {
          problems.add(key + ": x-argos-csv without a text/csv 2xx response (14 §4.17)");
        }
        String operationId = op.path("operationId").asString("");
        if (!OPERATION_ID.matcher(operationId).matches()) {
          problems.add(key + ": operationId '" + operationId + "' is not <resource>_<verb>");
        } else if (!operationIds.add(operationId)) {
          problems.add(key + ": operationId '" + operationId + "' is not unique");
        }
        if (path.getKey().startsWith("/api/v1/admin/audit")
            && Set.of("put", "patch", "delete").contains(method)) {
          problems.add(key + ": /admin/audit is read-only (SEC-82)");
        }
        List<JsonNode> parameters = new ArrayList<>();
        path.getValue().path("parameters").forEach(parameters::add);
        op.path("parameters").forEach(parameters::add);
        for (JsonNode parameter : parameters) {
          String name = parameter.path("name").asString("");
          if ((UINT64_PARAMETERS.contains(name) || UINT64_PARAMETERS.contains(key + " " + name))
              && !unsignedId(parameter.path("schema"))) {
            problems.add(key + ": parameter " + name + UINT64_PROBLEM);
          }
        }
      }
    }
    JsonNode schemas = document.path("components").path("schemas");
    Set<String> reachable = new java.util.TreeSet<>();
    for (JsonNode schema : responseSchemas) {
      collect(schema, schemas, reachable);
    }
    for (Map.Entry<String, JsonNode> schema : schemas.properties()) {
      if (!reachable.contains(schema.getKey())) {
        continue;
      }
      for (Map.Entry<String, JsonNode> property :
          schema.getValue().path("properties").properties()) {
        if (COLLECTED.contains(property.getKey())
            && holdsStrings(property.getValue())
            && !property.getValue().path("x-argos-untrusted").asBoolean(false)) {
          problems.add(
              "schema "
                  + schema.getKey()
                  + "."
                  + property.getKey()
                  + ": collected string without x-argos-untrusted");
        }
      }
    }
    Set<String> objects = new java.util.TreeSet<>();
    for (JsonNode schema : responseSchemas) {
      collectAll(schema, schemas, objects);
    }
    for (String name : objects) {
      JsonNode schema = schemas.path(name);
      if (!schema.path("properties").isEmpty()
          && schema.path("required").isEmpty()
          && !REQUIRED_EXEMPT.containsKey(name)) {
        problems.add(
            "schema "
                + name
                + ": response object without required properties (oasdiff sees only the removal"
                + " of a required property)");
      }
    }
    Set<String> inputs = new java.util.TreeSet<>();
    for (JsonNode schema : requestSchemas) {
      collectAll(schema, schemas, inputs);
    }
    for (String name : inputs) {
      if (objects.contains(name)) {
        problems.add(
            "schema "
                + name
                + ": reachable from a request body and a 2xx response (split the request and"
                + " response records)");
      }
    }
    for (Map.Entry<String, JsonNode> schema : schemas.properties()) {
      unsignedIds(schema.getKey(), schema.getValue(), true, problems, 0);
    }
    return problems;
  }

  /**
   * Rule 10 on the properties of {@code schema} (component {@code component}) and of the inline
   * object schemas inside it; {@code top} is true for the component's own properties, where the
   * qualified names of {@link #UINT64_SCHEMA_PROPERTIES} apply.
   */
  private static void unsignedIds(
      String component, JsonNode schema, boolean top, List<String> problems, int depth) {
    if (schema == null || !schema.isObject() || schema.has("$ref") || depth > 32) {
      return;
    }
    for (Map.Entry<String, JsonNode> property : schema.path("properties").properties()) {
      String name = property.getKey();
      boolean listed =
          UINT64_PROPERTIES.contains(name)
              || (top && UINT64_SCHEMA_PROPERTIES.contains(component + "." + name));
      if (listed && !unsignedId(property.getValue())) {
        problems.add("schema " + component + "." + name + UINT64_PROBLEM);
      }
      unsignedIds(component, property.getValue(), false, problems, depth + 1);
    }
    unsignedIds(component, schema.path("items"), false, problems, depth + 1);
    unsignedIds(component, schema.path("additionalProperties"), false, problems, depth + 1);
    for (String composition : List.of("allOf", "anyOf", "oneOf")) {
      for (JsonNode part : schema.path(composition)) {
        unsignedIds(component, part, false, problems, depth + 1);
      }
    }
  }

  /**
   * A string with the 64-bit pattern, its nullable form ({@code type: [string, 'null']}, or {@code
   * anyOf}/{@code oneOf} whose non-null alternatives are such strings), or an array of them.
   */
  static boolean unsignedId(JsonNode schema) {
    if (schema == null || !schema.isObject() || schema.has("$ref")) {
      return false;
    }
    JsonNode alternatives = schema.has("anyOf") ? schema.path("anyOf") : schema.path("oneOf");
    if (alternatives.isArray() && !alternatives.isEmpty()) {
      boolean value = false;
      for (JsonNode alternative : alternatives) {
        if (onlyNull(alternative)) {
          continue;
        }
        if (!unsignedId(alternative)) {
          return false;
        }
        value = true;
      }
      return value;
    }
    if (typeIs(schema, "array")) {
      return unsignedString(schema.path("items"));
    }
    return unsignedString(schema);
  }

  /** {@code type: string} or {@code [string, 'null']} with the 64-bit pattern. */
  private static boolean unsignedString(JsonNode schema) {
    if (schema == null || !schema.isObject() || schema.has("$ref") || !typeIs(schema, "string")) {
      return false;
    }
    for (JsonNode t : schema.path("type")) {
      if (!t.asString("").equals("string") && !t.asString("").equals("null")) {
        return false;
      }
    }
    return UINT64_PATTERN.equals(schema.path("pattern").asString(""));
  }

  /** {@code type: 'null'} or {@code type: ['null']}. */
  private static boolean onlyNull(JsonNode schema) {
    JsonNode t = schema.path("type");
    if (t.isArray()) {
      return t.size() == 1 && t.get(0).asString("").equals("null");
    }
    return t.asString("").equals("null");
  }

  /** Adds every component schema that {@code schema} refers to, transitively (rule 8). */
  private static void collectAll(JsonNode schema, JsonNode schemas, Set<String> reachable) {
    if (schema == null || schema.isMissingNode() || !(schema.isObject() || schema.isArray())) {
      return;
    }
    String ref = schema.path("$ref").asString("");
    if (ref.startsWith("#/components/schemas/")) {
      String name = ref.substring("#/components/schemas/".length());
      if (reachable.add(name)) {
        collectAll(schemas.path(name), schemas, reachable);
      }
      return;
    }
    if (schema.isArray()) {
      schema.forEach(e -> collectAll(e, schemas, reachable));
      return;
    }
    for (Map.Entry<String, JsonNode> e : schema.properties()) {
      collectAll(e.getValue(), schemas, reachable);
    }
  }

  /**
   * Adds the component schemas that {@code schema} refers to, transitively, except through a
   * property named {@code meta} (the envelope's server values).
   */
  private static void collect(JsonNode schema, JsonNode schemas, Set<String> reachable) {
    if (schema == null || schema.isMissingNode()) {
      return;
    }
    if (schema.isArray()) {
      schema.forEach(e -> collect(e, schemas, reachable));
      return;
    }
    if (!schema.isObject()) {
      return;
    }
    String ref = schema.path("$ref").asString("");
    if (ref.startsWith("#/components/schemas/")) {
      String name = ref.substring("#/components/schemas/".length());
      if (reachable.add(name)) {
        collect(schemas.path(name), schemas, reachable);
      }
      return;
    }
    for (Map.Entry<String, JsonNode> e : schema.properties()) {
      if (e.getKey().equals("properties")) {
        for (Map.Entry<String, JsonNode> property : e.getValue().properties()) {
          if (!property.getKey().equals("meta")) {
            collect(property.getValue(), schemas, reachable);
          }
        }
      } else if (e.getValue().isObject() || e.getValue().isArray()) {
        collect(e.getValue(), schemas, reachable);
      }
    }
  }

  /** A string, an array of strings or a map of strings (not a {@code $ref} to an object). */
  private static boolean holdsStrings(JsonNode property) {
    if (property.has("$ref")) {
      return false;
    }
    if (typeIs(property, "string")) {
      return true;
    }
    if (typeIs(property, "array")) {
      return typeIs(property.path("items"), "string");
    }
    if (typeIs(property, "object")) {
      return typeIs(property.path("additionalProperties"), "string");
    }
    return false;
  }

  /** {@code type: t} or {@code type: [t, "null"]} (OpenAPI 3.0 and 3.1). */
  private static boolean typeIs(JsonNode schema, String type) {
    JsonNode t = schema.path("type");
    if (t.isArray()) {
      for (JsonNode e : t) {
        if (e.asString("").equals(type)) {
          return true;
        }
      }
      return false;
    }
    return t.asString("").equals(type);
  }
}
