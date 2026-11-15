/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.servertest.security.matrix;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An OpenAPI document as data, for the tests generated from it (T-API-001, T-API-010): the
 * operations with their {@code x-argos-*} extensions, and {@code $ref} resolution.
 */
public final class OpenApiSpec {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Set<String> METHODS =
      Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");
  private static final String COMPONENT_PREFIX = "#/components/schemas/";

  private final JsonNode document;
  private final List<Operation> operations;

  private OpenApiSpec(JsonNode document) {
    this.document = document;
    List<Operation> found = new ArrayList<>();
    JsonNode paths = document.path("paths");
    for (String path : paths.propertyNames()) {
      JsonNode item = paths.path(path);
      for (String method : item.propertyNames()) {
        if (METHODS.contains(method)) {
          found.add(
              new Operation(
                  method.toUpperCase(Locale.ROOT),
                  path,
                  item.path(method),
                  item.path("parameters")));
        }
      }
    }
    found.sort(Comparator.comparing(Operation::path).thenComparing(Operation::method));
    this.operations = List.copyOf(found);
  }

  /** Parses a JSON document. */
  public static OpenApiSpec parse(String json) {
    return new OpenApiSpec(JSON.readTree(json));
  }

  /** Every operation, sorted by path and method. */
  public List<Operation> operations() {
    return operations;
  }

  /** {@code schema}, with a {@code $ref} to a component schema followed. */
  public JsonNode resolve(JsonNode schema) {
    JsonNode current = schema;
    for (int depth = 0; depth < 16 && current.path("$ref").isString(); depth++) {
      String reference = current.path("$ref").asString();
      if (!reference.startsWith(COMPONENT_PREFIX)) {
        return current;
      }
      current =
          document
              .path("components")
              .path("schemas")
              .path(reference.substring(COMPONENT_PREFIX.length()));
    }
    return current;
  }

  /**
   * One operation.
   *
   * @param method upper-case HTTP method
   * @param path path template
   * @param node the operation object
   * @param pathParameters the {@code parameters} of the path item
   */
  public record Operation(String method, String path, JsonNode node, JsonNode pathParameters) {

    /** {@code METHOD /path}. */
    public String key() {
      return method + " " + path;
    }

    /** {@code x-argos-role}. */
    public Optional<String> role() {
      return text("x-argos-role");
    }

    /** {@code x-argos-audit}. */
    public Optional<String> audit() {
      return text("x-argos-audit");
    }

    /** {@code x-argos-pii}. */
    public Optional<String> pii() {
      return text("x-argos-pii");
    }

    /** Whether the method changes state (14 §2.2, §2.8). */
    public boolean unsafe() {
      return Set.of("POST", "PUT", "PATCH", "DELETE").contains(method);
    }

    private Optional<String> text(String extension) {
      JsonNode value = node.path(extension);
      return value.isString() ? Optional.of(value.asString()) : Optional.empty();
    }

    @Override
    public String toString() {
      return key();
    }
  }
}
