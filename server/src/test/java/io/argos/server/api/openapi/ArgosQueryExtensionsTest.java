/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.argos.server.auth.HandlerRoles;
import io.argos.server.auth.RequiresRole;
import io.argos.server.auth.Role;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.PathParameter;
import io.swagger.v3.oas.models.parameters.QueryParameter;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

/** The query extensions of 14 §2.8 that WO-23 adds: csv, limits, source, untrusted. */
@Tag("req:DSN-14-10")
class ArgosQueryExtensionsTest {

  /** Annotated handlers. */
  static class Handlers {
    @RequiresRole(Role.VIEWER)
    @ArgosCsv
    @ArgosLimits(rangeMax = "24h", rowsMax = 1000, timeoutS = 10)
    @ArgosSource({"xlog"})
    public void search() {}

    @RequiresRole(Role.VIEWER)
    @ArgosLimits(rangeMax = "1y")
    public void rangeOnly() {}

    @RequiresRole(Role.VIEWER)
    @ArgosLimits
    public void emptyLimits() {}
  }

  /** A response type with one collected string. */
  record Dto(@ArgosUntrusted String service, String id) {}

  private final ArgosOperationCustomizer customizer =
      new ArgosOperationCustomizer(new HandlerRoles(List.of()));

  private Map<String, Object> extensions(String method) throws Exception {
    Handlers bean = new Handlers();
    Operation operation =
        customizer.customize(
            new Operation(), new HandlerMethod(bean, Handlers.class.getMethod(method)));
    return operation.getExtensions() == null ? Map.of() : operation.getExtensions();
  }

  @Test
  @DisplayName(
      "T-API-001a x-argos-csv, x-argos-limits {range_max, rows_max, timeout_s}, x-argos-source")
  void operationExtensions() throws Exception {
    Map<String, Object> search = extensions("search");
    assertThat(search)
        .containsEntry("x-argos-role", "viewer")
        .containsEntry("x-argos-csv", true)
        .containsEntry("x-argos-source", List.of("xlog"));
    assertThat(search.get("x-argos-limits"))
        .isEqualTo(Map.of("range_max", "24h", "rows_max", 1000, "timeout_s", 10));
    assertThat(extensions("rangeOnly").get("x-argos-limits")).isEqualTo(Map.of("range_max", "1y"));
    assertThat(extensions("rangeOnly")).doesNotContainKey("x-argos-csv");
    assertThat(extensions("emptyLimits")).doesNotContainKey("x-argos-limits");
  }

  /** The enum of a parameter's schema (swagger's raw Schema type). */
  private static List<Object> enumOf(Parameter parameter) {
    List<?> values = parameter.getSchema().getEnum();
    return values == null ? null : List.copyOf(values);
  }

  @Test
  @DisplayName(
      "T-API-001a the format query parameter of a CSV operation is the enum json, csv; other"
          + " parameters and operations keep their schema (C-191 (1))")
  void formatEnum() throws Exception {
    Handlers bean = new Handlers();
    for (String method : List.of("search", "rangeOnly")) {
      Operation operation =
          new Operation()
              .addParametersItem(new QueryParameter().name("format").schema(new StringSchema()))
              .addParametersItem(new QueryParameter().name("tz").schema(new StringSchema()))
              .addParametersItem(new PathParameter().name("format").schema(new StringSchema()));
      customizer.customize(operation, new HandlerMethod(bean, Handlers.class.getMethod(method)));
      List<Parameter> parameters = operation.getParameters();
      if (method.equals("search")) {
        assertThat(enumOf(parameters.get(0))).containsExactly("json", "csv");
      } else {
        assertThat(enumOf(parameters.get(0))).as("not a CSV operation").isNull();
      }
      assertThat(enumOf(parameters.get(1))).as(method + " tz").isNull();
      assertThat(enumOf(parameters.get(2))).as(method + " path").isNull();
    }
  }

  @Test
  @DisplayName("T-API-001a x-argos-untrusted on a schema property annotated @ArgosUntrusted")
  void untrustedProperty() throws Exception {
    Annotation untrusted =
        Dto.class.getDeclaredField("service").getAnnotation(ArgosUntrusted.class);
    assertThat(untrusted).isNotNull();
    ArgosUntrustedPropertyCustomizer property = new ArgosUntrustedPropertyCustomizer();
    StringSchema marked = new StringSchema();
    property.customize(
        marked, new AnnotatedType(String.class).ctxAnnotations(new Annotation[] {untrusted}));
    assertThat(marked.getExtensions()).containsEntry("x-argos-untrusted", true);
    StringSchema plain = new StringSchema();
    property.customize(plain, new AnnotatedType(String.class));
    assertThat(plain.getExtensions()).isNull();
  }
}
