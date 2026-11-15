/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.openapi;

import io.argos.server.api.openapi.ArgosRecordSchemaConverter.RequestForm;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

/**
 * Keeps the response rules of {@link ArgosRecordSchemaConverter} out of request bodies (review
 * finding r1-new-1): a request body field that the JSON writer would always write is still optional
 * for the client, and a request field may be absent without being {@code null}.
 *
 * <ol>
 *   <li>Every component schema reachable from a request body ({@code paths → operations →
 *       requestBody → content → schema}, through {@code $ref}, properties, items, map values and
 *       compositions, e.g. {@code TransactionSearchRequest → TransactionSearchFilters}) gets back
 *       springdoc's own {@code required} list (explicit {@code @Schema(requiredMode = REQUIRED)}
 *       only) and its original property schemas (no added {@code null}), from {@link
 *       ArgosRecordSchemaConverter#REQUEST_FORM}.
 *   <li>A component reachable from both a request body and a 2xx response fails the document with
 *       an {@link IllegalStateException}: one schema cannot say both forms, so the request and the
 *       response need records of their own.
 *   <li>{@link ArgosRecordSchemaConverter#REQUEST_FORM} is removed from every schema.
 * </ol>
 *
 * The contract test T-API-001 checks rule 2 on the committed document too ({@code OpenApiLint}).
 */
@Component
public class ArgosRequestSchemaCustomizer implements OpenApiCustomizer {

  private static final String SCHEMAS = "#/components/schemas/";
  private static final String REQUEST_BODIES = "#/components/requestBodies/";
  private static final String RESPONSES = "#/components/responses/";

  @Override
  public void customise(OpenAPI openApi) {
    apply(openApi);
  }

  /** Rules 1 to 3 on {@code openApi}. */
  @SuppressWarnings({"rawtypes", "unchecked"}) // swagger's raw Schema type
  static void apply(OpenAPI openApi) {
    Components components = openApi.getComponents();
    Map<String, Schema> schemas =
        components == null || components.getSchemas() == null ? Map.of() : components.getSchemas();
    Set<String> requests = new TreeSet<>();
    Set<String> responses = new TreeSet<>();
    if (openApi.getPaths() != null) {
      for (PathItem item : openApi.getPaths().values()) {
        for (Operation operation : item.readOperations()) {
          RequestBody body = requestBody(operation.getRequestBody(), components);
          if (body != null) {
            walk(body.getContent(), schemas, requests);
          }
          if (operation.getResponses() == null) {
            continue;
          }
          for (Map.Entry<String, ApiResponse> response : operation.getResponses().entrySet()) {
            if (response.getKey().startsWith("2")) {
              ApiResponse resolved = response(response.getValue(), components);
              if (resolved != null) {
                walk(resolved.getContent(), schemas, responses);
              }
            }
          }
        }
      }
    }
    Set<String> both = new TreeSet<>(requests);
    both.retainAll(responses);
    if (!both.isEmpty()) {
      throw new IllegalStateException(
          "OpenAPI component schema(s) "
              + both
              + " are reachable from both a request body and a 2xx response: split the request and"
              + " response records (response schemas mark always-written properties required and"
              + " nullable ones null, which a request body must not say)");
    }
    for (String name : requests) {
      Schema model = schemas.get(name);
      if (model != null) {
        restore(name, model);
      }
    }
    for (Schema model : schemas.values()) {
      if (model != null && model.getExtensions() != null) {
        model.getExtensions().remove(ArgosRecordSchemaConverter.REQUEST_FORM);
        if (model.getExtensions().isEmpty()) {
          model.setExtensions(null);
        }
      }
    }
  }

  /** Puts the {@link RequestForm} of a request component back. */
  @SuppressWarnings({"rawtypes", "unchecked"}) // swagger's raw Schema maps
  private static void restore(String name, Schema model) {
    Object form =
        model.getExtensions() == null
            ? null
            : model.getExtensions().get(ArgosRecordSchemaConverter.REQUEST_FORM);
    if (form == null) {
      return; // not a record schema that the converter changed
    }
    if (!(form instanceof RequestForm request)) {
      throw new IllegalStateException(
          "Schema " + name + " carries an unexpected " + ArgosRecordSchemaConverter.REQUEST_FORM);
    }
    if (model.getProperties() != null && !request.properties().isEmpty()) {
      Map<String, Schema> properties = new LinkedHashMap<>(model.getProperties());
      request.properties().forEach(properties::replace);
      model.setProperties(properties);
    }
    model.setRequired(request.required() == null ? null : new ArrayList<>(request.required()));
  }

  private static RequestBody requestBody(RequestBody body, Components components) {
    if (body != null
        && body.get$ref() != null
        && body.get$ref().startsWith(REQUEST_BODIES)
        && components != null
        && components.getRequestBodies() != null) {
      return components.getRequestBodies().get(body.get$ref().substring(REQUEST_BODIES.length()));
    }
    return body;
  }

  private static ApiResponse response(ApiResponse response, Components components) {
    if (response != null
        && response.get$ref() != null
        && response.get$ref().startsWith(RESPONSES)
        && components != null
        && components.getResponses() != null) {
      return components.getResponses().get(response.get$ref().substring(RESPONSES.length()));
    }
    return response;
  }

  @SuppressWarnings("rawtypes")
  private static void walk(Content content, Map<String, Schema> schemas, Set<String> into) {
    if (content == null) {
      return;
    }
    for (MediaType media : content.values()) {
      if (media != null) {
        walk(media.getSchema(), schemas, into);
      }
    }
  }

  /** Adds every component schema that {@code schema} refers to, transitively. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  static void walk(Schema schema, Map<String, Schema> schemas, Set<String> into) {
    if (schema == null) {
      return;
    }
    String ref = schema.get$ref();
    if (ref != null && ref.startsWith(SCHEMAS)) {
      String name = ref.substring(SCHEMAS.length());
      if (into.add(name)) {
        walk(schemas.get(name), schemas, into);
      }
    }
    if (schema.getProperties() != null) {
      walkAll(schema.getProperties().values(), schemas, into);
    }
    if (schema.getPatternProperties() != null) {
      walkAll(schema.getPatternProperties().values(), schemas, into);
    }
    walk(schema.getItems(), schemas, into);
    if (schema.getAdditionalProperties() instanceof Schema values) {
      walk(values, schemas, into);
    }
    walkAll(schema.getAllOf(), schemas, into);
    walkAll(schema.getAnyOf(), schemas, into);
    walkAll(schema.getOneOf(), schemas, into);
    walkAll(schema.getPrefixItems(), schemas, into);
    walk(schema.getNot(), schemas, into);
  }

  @SuppressWarnings("rawtypes")
  private static void walkAll(
      Collection<? extends Schema> list, Map<String, Schema> schemas, Set<String> into) {
    if (list != null) {
      for (Schema s : list) {
        walk(s, schemas, into);
      }
    }
  }
}
