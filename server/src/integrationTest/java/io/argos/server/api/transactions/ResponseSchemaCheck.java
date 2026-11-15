/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.transactions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Checks a JSON response against its schema in the server's own OpenAPI 3.1 document (14 §9
 * contract row "every operation's response matches the spec schema", O-R03): {@code type} (one or a
 * list, {@code "null"} included), {@code required}, {@code properties}, {@code
 * additionalProperties}, {@code items}, {@code $ref} into {@code components/schemas}, {@code
 * anyOf}, {@code oneOf}, {@code allOf}, {@code enum} and the {@code pattern} of a string (the
 * 64-bit keys of ADR-0010 C-175: a full-string match as JSON Schema's unanchored search with the
 * {@code ^...$} the document writes). Formats, lengths and numeric bounds are not checked. A small
 * checker in the test sources, so that the build needs no JSON Schema library (and no Jackson 2
 * bridge).
 */
final class ResponseSchemaCheck {

  private static final String COMPONENTS = "#/components/schemas/";

  private final JsonNode schemas;

  /** A checker for the component schemas of {@code document}. */
  ResponseSchemaCheck(JsonNode document) {
    this.schemas = document.path("components").path("schemas");
  }

  /**
   * The JSON schema of the 2xx {@code application/json} response {@code status} of an operation.
   */
  static JsonNode responseSchema(JsonNode operation, int status) {
    JsonNode responses = operation.path("responses");
    JsonNode response = responses.path(Integer.toString(status));
    if (response.isMissingNode()) {
      response = responses.path("2XX");
    }
    if (response.isMissingNode()) {
      response = responses.path("default");
    }
    return response.path("content").path("application/json").path("schema");
  }

  /** The differences between {@code value} and {@code schema}; empty when it conforms. */
  List<String> check(JsonNode value, JsonNode schema) {
    List<String> problems = new ArrayList<>();
    check(value, schema, "$", problems, 0);
    return problems;
  }

  private void check(JsonNode value, JsonNode schema, String at, List<String> out, int depth) {
    if (schema == null || schema.isMissingNode() || !schema.isObject() || depth > 64) {
      return;
    }
    String ref = schema.path("$ref").asString("");
    if (!ref.isEmpty()) {
      if (!ref.startsWith(COMPONENTS) || !schemas.has(ref.substring(COMPONENTS.length()))) {
        out.add(at + ": unresolvable $ref " + ref);
        return;
      }
      check(value, schemas.path(ref.substring(COMPONENTS.length())), at, out, depth + 1);
      return;
    }
    for (JsonNode part : schema.path("allOf")) {
      check(value, part, at, out, depth + 1);
    }
    if (schema.has("anyOf") && !matchesAny(value, schema.path("anyOf"), at, depth)) {
      out.add(at + ": matches no anyOf alternative (" + kind(value) + ")");
      return;
    }
    if (schema.has("oneOf") && !matchesAny(value, schema.path("oneOf"), at, depth)) {
      out.add(at + ": matches no oneOf alternative (" + kind(value) + ")");
      return;
    }
    JsonNode type = schema.path("type");
    if (!type.isMissingNode() && !typeMatches(value, type)) {
      out.add(at + ": " + kind(value) + " where the schema says " + type);
      return;
    }
    String pattern = schema.path("pattern").asString("");
    if (!pattern.isEmpty() && value.isString() && !matches(pattern, value.asString())) {
      out.add(at + ": " + value + " does not match the pattern " + pattern);
    }
    JsonNode allowed = schema.path("enum");
    if (allowed.isArray() && !allowed.isEmpty()) {
      boolean found = false;
      for (JsonNode a : allowed) {
        found |= a.equals(value) || (a.isNull() && value.isNull());
      }
      if (!found) {
        out.add(at + ": " + value + " is not one of " + allowed);
      }
    }
    if (value.isObject()) {
      for (JsonNode name : schema.path("required")) {
        if (!value.has(name.asString())) {
          out.add(at + ": required property " + name.asString() + " is missing");
        }
      }
      JsonNode properties = schema.path("properties");
      JsonNode additional = schema.path("additionalProperties");
      for (Map.Entry<String, JsonNode> e : value.properties()) {
        String child = at + "." + e.getKey();
        if (properties.has(e.getKey())) {
          check(e.getValue(), properties.path(e.getKey()), child, out, depth + 1);
        } else if (additional.isObject()) {
          check(e.getValue(), additional, child, out, depth + 1);
        }
      }
    } else if (value.isArray() && schema.has("items")) {
      for (int i = 0; i < value.size(); i++) {
        check(value.get(i), schema.path("items"), at + "[" + i + "]", out, depth + 1);
      }
    }
  }

  private boolean matchesAny(JsonNode value, JsonNode alternatives, String at, int depth) {
    for (JsonNode alternative : alternatives) {
      List<String> problems = new ArrayList<>();
      check(value, alternative, at, problems, depth + 1);
      if (problems.isEmpty()) {
        return true;
      }
    }
    return false;
  }

  /**
   * JSON Schema {@code pattern}: an unanchored search (ECMA 262; the patterns in use are plain).
   */
  private static boolean matches(String pattern, String value) {
    return Pattern.compile(pattern).matcher(value).find();
  }

  /** {@code type: t} or {@code type: [t, ...]}; {@code integer} is a number without a fraction. */
  private static boolean typeMatches(JsonNode value, JsonNode type) {
    if (type.isArray()) {
      for (JsonNode t : type) {
        if (typeMatches(value, t.asString(""))) {
          return true;
        }
      }
      return false;
    }
    return typeMatches(value, type.asString(""));
  }

  private static boolean typeMatches(JsonNode value, String type) {
    return switch (type) {
      case "null" -> value.isNull();
      case "string" -> value.isString();
      case "boolean" -> value.isBoolean();
      case "integer" ->
          value.isIntegralNumber()
              || (value.isNumber() && value.doubleValue() == Math.rint(value.doubleValue()));
      case "number" -> value.isNumber();
      case "array" -> value.isArray();
      case "object" -> value.isObject();
      default -> true;
    };
  }

  private static String kind(JsonNode value) {
    if (value.isNull()) {
      return "null";
    }
    if (value.isString()) {
      return "string";
    }
    if (value.isBoolean()) {
      return "boolean";
    }
    if (value.isNumber()) {
      return "number";
    }
    if (value.isArray()) {
      return "array";
    }
    return value.isObject() ? "object" : value.getNodeType().toString();
  }
}
