/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The response schema rules of {@link ArgosRecordSchemaConverter} (14 §2.9, O-R03) on swagger's own
 * model resolution in OpenAPI 3.1 mode, and the CSV content of {@link ArgosOperationCustomizer} (14
 * §4.17): what the JSON writer always writes is required, what it may write as null admits null.
 */
@Tag("req:O-R03")
@Tag("req:DSN-14-10")
class ArgosRecordSchemaConverterTest {

  /** A nested object. */
  record Child(@JsonProperty("child_id") int childId) {}

  /** Every rule in one record (the writer's default inclusion: ALWAYS). */
  record Sample(
      @JsonProperty("id") String id,
      @JsonProperty("count") long count,
      @JsonProperty("p95_ms") @Nullable Double p95Ms,
      @JsonProperty("caller") @Nullable Child caller,
      @JsonProperty("child") Child child,
      @JsonProperty("values") List<@Nullable Double> values,
      @JsonProperty("grid") Map<String, List<@Nullable Long>> grid,
      @JsonProperty("names") List<String> names,
      @JsonProperty("cursor") Optional<String> cursor,
      @JsonProperty("delta") @JsonInclude(JsonInclude.Include.NON_NULL) Double delta,
      @JsonProperty("hidden_doc")
          @io.swagger.v3.oas.annotations.media.Schema(
              requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED)
          String hiddenDoc) {}

  /** A record that leaves nulls out, like the envelope meta. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Meta(
      @JsonProperty("request_id")
          @io.swagger.v3.oas.annotations.media.Schema(
              requiredMode = io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED)
          String requestId,
      @JsonProperty("from") Instant from,
      @JsonProperty("truncated") boolean truncated,
      @JsonProperty("next_cursor") Optional<String> nextCursor) {}

  @SuppressWarnings("rawtypes")
  private static Map<String, Schema> resolve(Class<?> type) {
    ModelConverters converters = new ModelConverters(true);
    converters.addConverter(new ArgosRecordSchemaConverter());
    return converters.readAll(type);
  }

  private static Schema<?> property(Schema<?> model, String name) {
    return model.getProperties().get(name);
  }

  @Test
  @DisplayName(
      "T-API-002a always-written components are required; @Nullable, Optional and nullable"
          + " elements admit null; NON_NULL and NOT_REQUIRED components are optional")
  void requiredAndNullable() {
    @SuppressWarnings("rawtypes")
    Map<String, Schema> models = resolve(Sample.class);
    Schema<?> sample = models.get("Sample");
    assertThat(sample.getRequired())
        .containsExactly(
            "caller", "child", "count", "cursor", "grid", "id", "names", "p95_ms", "values");
    assertThat(property(sample, "id").getTypes()).containsExactly("string");
    assertThat(property(sample, "count").getTypes()).containsExactly("integer");
    assertThat(property(sample, "p95_ms").getTypes()).containsExactly("number", "null");
    assertThat(property(sample, "delta").getTypes()).containsExactly("number");
    assertThat(property(sample, "cursor").getTypes()).containsExactly("string", "null");
    Schema<?> caller = property(sample, "caller");
    assertThat(caller.get$ref()).isNull();
    assertThat(caller.getAnyOf()).hasSize(2);
    Schema<?> target = caller.getAnyOf().get(0);
    Schema<?> none = caller.getAnyOf().get(1);
    assertThat(target.get$ref()).isEqualTo("#/components/schemas/Child");
    assertThat(none.getTypes()).containsExactly("null");
    assertThat(property(sample, "child").get$ref()).isEqualTo("#/components/schemas/Child");
    assertThat(property(sample, "values").getItems().getTypes()).containsExactly("number", "null");
    assertThat(property(sample, "names").getItems().getTypes()).containsExactly("string");
    Schema<?> grid = (Schema<?>) property(sample, "grid").getAdditionalProperties();
    assertThat(grid.getItems().getTypes()).containsExactly("integer", "null");
    Schema<?> child = models.get("Child");
    assertThat(child.getRequired()).containsExactly("child_id");
  }

  @Test
  @DisplayName(
      "T-API-002a with NON_NULL: explicit REQUIRED and primitives are required, an Optional is"
          + " optional and nullable (next_cursor), other references are optional")
  void nonNullRecord() {
    Schema<?> meta = resolve(Meta.class).get("Meta");
    assertThat(meta.getRequired()).containsExactly("request_id", "truncated");
    assertThat(property(meta, "from").getTypes()).containsExactly("string");
    assertThat(property(meta, "next_cursor").getTypes()).containsExactly("string", "null");
  }

  @Test
  @DisplayName(
      "T-API-001a an x-argos-csv operation gets a text/csv content next to the JSON one of its"
          + " 200 response, unless it declares one")
  @SuppressWarnings("rawtypes") // swagger's builders return the raw Schema type
  void csvContent() {
    Operation operation =
        new Operation()
            .responses(
                new ApiResponses()
                    .addApiResponse(
                        "200",
                        new ApiResponse()
                            .content(
                                new Content()
                                    .addMediaType(
                                        "application/json",
                                        new MediaType().schema(new Schema().$ref("X"))))));
    ArgosOperationCustomizer.addCsvContent(operation);
    Content content = operation.getResponses().get("200").getContent();
    assertThat(content).containsKeys("application/json", "text/csv");
    Schema<?> csv = content.get("text/csv").getSchema();
    assertThat(csv.getTypes()).containsExactly("string");
    assertThat(csv.getFormat()).isEqualTo("binary");
    MediaType declared = new MediaType().schema(new Schema().format("declared"));
    content.put("text/csv", declared);
    ArgosOperationCustomizer.addCsvContent(operation);
    assertThat(content.get("text/csv")).isSameAs(declared);
  }
}
