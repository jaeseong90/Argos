/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * One {@code meta} schema for every response envelope (14 §2.5, DSN-14-04): each {@code meta}
 * property of the committed OpenAPI document refers to {@value #META}, so that the WO-24 envelopes
 * ({@code Envelope*}: authentication, setup, users) and the query envelopes ({@code
 * ResponseEnvelope*}) cannot drift apart again. The committed document equals a fresh generation
 * ({@link OpenApiContractTest}), so checking it checks the controllers.
 */
@Tag("req:DSN-14-04")
class EnvelopeMetaContractTest {

  /** The one meta schema ({@code ResponseEnvelope.Meta}). */
  static final String META = "QueryMeta";

  private static final String REF = "#/components/schemas/" + META;

  private static JsonNode document;

  @BeforeAll
  static void read() {
    String committed = OpenApiDocument.readCommitted();
    assertThat(committed)
        .as(OpenApiDocument.COMMITTED + " is missing: run ./gradlew :server:generateOpenApi")
        .isNotNull();
    document = OpenApiDocument.readYaml(committed);
  }

  /** The {@code meta} properties anywhere in {@code node} that do not refer to {@value #META}. */
  static List<String> check(JsonNode node, String where, List<String> problems) {
    if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        check(node.get(i), where + "[" + i + "]", problems);
      }
    } else if (node.isObject()) {
      JsonNode meta = node.path("properties").path("meta");
      if (!meta.isMissingNode() && !REF.equals(meta.path("$ref").asString(""))) {
        problems.add(where + ".properties.meta: " + meta + " is not a $ref to " + META);
      }
      for (Map.Entry<String, JsonNode> e : node.properties()) {
        check(e.getValue(), where + "." + e.getKey(), problems);
      }
    }
    return problems;
  }

  @Test
  @DisplayName(
      "T-API-001 one meta schema: every envelope of the committed document (Envelope*,"
          + " ResponseEnvelope*) refers to QueryMeta, which has request_id and generated_at")
  void oneMetaSchema() {
    JsonNode schemas = document.path("components").path("schemas");
    assertThat(check(document, "$", new ArrayList<>())).isEmpty();
    assertThat(schemas.has("Meta")).as("the former WO-24 Meta schema is gone").isFalse();
    List<String> envelopes = new ArrayList<>();
    for (Map.Entry<String, JsonNode> schema : schemas.properties()) {
      if (schema.getValue().path("properties").has("meta")) {
        envelopes.add(schema.getKey());
      }
    }
    assertThat(envelopes)
        .as("both envelope families are checked")
        .anyMatch(name -> name.startsWith("Envelope"))
        .anyMatch(name -> name.startsWith("ResponseEnvelope"));
    JsonNode meta = schemas.path(META).path("properties");
    assertThat(meta.has("request_id")).isTrue();
    assertThat(meta.has("generated_at")).isTrue();
    assertThat(meta.has("truncated")).isTrue();
  }

  @Test
  @DisplayName("T-API-001 the one-meta check catches a second meta schema (not vacuous)")
  void checkIsNotVacuous() {
    ObjectNode copy = (ObjectNode) document.deepCopy();
    ObjectNode schemas = (ObjectNode) copy.path("components").path("schemas");
    String envelope = null;
    for (Map.Entry<String, JsonNode> schema : schemas.properties()) {
      if (schema.getKey().startsWith("Envelope")
          && schema.getValue().path("properties").has("meta")) {
        envelope = schema.getKey();
        break;
      }
    }
    assertThat(envelope).isNotNull();
    ((ObjectNode) schemas.path(envelope).path("properties"))
        .putObject("meta")
        .put("$ref", "#/components/schemas/Meta");
    assertThat(check(copy, "$", new ArrayList<>()))
        .singleElement()
        .asString()
        .contains("components.schemas." + envelope + ".properties.meta");
  }
}
