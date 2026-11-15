/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The request side of the record schemas (review finding r1-new-1) on a mini OpenAPI model built
 * from swagger's own model resolution in OpenAPI 3.1 mode with {@link ArgosRecordSchemaConverter}:
 * {@link ArgosRequestSchemaCustomizer} gives a request body component back springdoc's description
 * (explicit {@code REQUIRED} only, no added {@code null}), keeps the response form of response
 * components, removes the internal extension and fails on a component that both reach.
 */
@Tag("req:O-R03")
@Tag("req:DSN-14-10")
class ArgosRequestSchemaCustomizerTest {

  /** A nested request record. */
  record ReqFilters(
      @JsonProperty("min_ms") Long minMs, @JsonProperty("names") List<@Nullable String> names) {}

  /** A request-only record with the writer's default inclusion (ALWAYS). */
  record ReqBody(
      @JsonProperty("from")
          @io.swagger.v3.oas.annotations.media.Schema(
              requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
          String from,
      @JsonProperty("to") String to,
      @JsonProperty("limit") Integer limit,
      @JsonProperty("note") @Nullable String note,
      @JsonProperty("filters") ReqFilters filters) {}

  /** A response-only record. */
  record RespBody(
      @JsonProperty("count") long count,
      @JsonProperty("label") @Nullable String label,
      @JsonProperty("values") List<@Nullable Double> values) {}

  /** A record that a request and a response share. */
  record SharedPart(@JsonProperty("id") String id) {}

  /** A request that holds the shared record. */
  record SharedReq(@JsonProperty("part") SharedPart part) {}

  /** A response that holds the shared record. */
  record SharedResp(@JsonProperty("part") SharedPart part) {}

  @SuppressWarnings({"rawtypes", "unchecked"}) // swagger's raw Schema type
  private static Map<String, Schema> resolve(Class<?>... types) {
    ModelConverters converters = new ModelConverters(true);
    converters.addConverter(new ArgosRecordSchemaConverter());
    Map<String, Schema> schemas = new LinkedHashMap<>();
    for (Class<?> type : types) {
      schemas.putAll(converters.readAll(type));
    }
    return schemas;
  }

  @SuppressWarnings({"rawtypes", "unchecked"}) // swagger's raw Schema type
  private static Content json(String component) {
    return new Content()
        .addMediaType(
            "application/json",
            new MediaType().schema(new Schema().$ref("#/components/schemas/" + component)));
  }

  /** {@code POST /x} with a request body and a 200 response of the two components. */
  @SuppressWarnings({"rawtypes", "unchecked"}) // swagger's raw Schema type
  private static OpenAPI document(Map<String, Schema> schemas, String request, String response) {
    Operation post =
        new Operation()
            .requestBody(new RequestBody().content(json(request)))
            .responses(
                new ApiResponses()
                    .addApiResponse("200", new ApiResponse().content(json(response))));
    return new OpenAPI()
        .components(new Components().schemas(schemas))
        .paths(new Paths().addPathItem("/x", new PathItem().post(post)));
  }

  @Test
  @DisplayName(
      "T-API-002a a request-only record (default ALWAYS inclusion) keeps only its explicit REQUIRED"
          + " properties and no added null, nested records too; the response record keeps the"
          + " response form; the internal extension is gone")
  @SuppressWarnings({"rawtypes", "unchecked"}) // swagger's raw Schema type
  void requestFormOfRequestBodies() {
    Map<String, Schema> schemas = resolve(ReqBody.class, RespBody.class);
    // The converter alone describes every record in its response form.
    assertThat(schemas.get("ReqBody").getRequired())
        .containsExactly("filters", "from", "limit", "note", "to");
    assertThat(schemas.get("ReqFilters").getRequired()).containsExactly("min_ms", "names");

    ArgosRequestSchemaCustomizer.apply(document(schemas, "ReqBody", "RespBody"));

    Schema<?> request = schemas.get("ReqBody");
    assertThat(request.getRequired()).containsExactly("from");
    assertThat(request.getProperties().get("note").getTypes()).containsExactly("string");
    assertThat(request.getProperties().get("filters").get$ref())
        .isEqualTo("#/components/schemas/ReqFilters");
    Schema<?> filters = schemas.get("ReqFilters");
    assertThat(filters.getRequired()).isNull();
    assertThat(filters.getProperties().get("names").getItems().getTypes())
        .containsExactly("string");
    Schema<?> response = schemas.get("RespBody");
    assertThat(response.getRequired()).containsExactly("count", "label", "values");
    assertThat(response.getProperties().get("label").getTypes()).containsExactly("string", "null");
    assertThat(response.getProperties().get("values").getItems().getTypes())
        .containsExactly("number", "null");
    for (Schema<?> schema : schemas.values()) {
      assertThat(schema.getExtensions() == null ? Map.of() : schema.getExtensions())
          .doesNotContainKey(ArgosRecordSchemaConverter.REQUEST_FORM);
    }
  }

  @Test
  @DisplayName(
      "T-API-002a a record reachable from both a request body and a 2xx response fails the"
          + " document generation, naming the schema")
  @SuppressWarnings({"rawtypes", "unchecked"}) // swagger's raw Schema type
  void sharedRecordFails() {
    Map<String, Schema> schemas = resolve(SharedReq.class, SharedResp.class);
    OpenAPI document = document(schemas, "SharedReq", "SharedResp");
    assertThatThrownBy(() -> ArgosRequestSchemaCustomizer.apply(document))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("[SharedPart]")
        .hasMessageContaining("split the request and response records");
  }

  @Test
  @DisplayName(
      "T-API-002a only 2xx responses count: a request component that a 422 response also reaches"
          + " keeps the request form; a request body that is also the 200 body fails")
  @SuppressWarnings({"rawtypes", "unchecked"}) // swagger's raw Schema type
  void onlySuccessResponsesCount() {
    Map<String, Schema> schemas = resolve(ReqBody.class, RespBody.class);
    OpenAPI document = document(schemas, "ReqBody", "RespBody");
    document
        .getPaths()
        .get("/x")
        .getPost()
        .getResponses()
        .addApiResponse("422", new ApiResponse().content(json("ReqFilters")));
    ArgosRequestSchemaCustomizer.apply(document);
    assertThat(schemas.get("ReqFilters").getRequired()).isNull();

    Map<String, Schema> again = resolve(ReqBody.class, RespBody.class);
    assertThatThrownBy(
            () -> ArgosRequestSchemaCustomizer.apply(document(again, "ReqBody", "ReqBody")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ReqBody")
        .hasMessageContaining("ReqFilters");
  }
}
