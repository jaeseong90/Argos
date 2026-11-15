/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.lang.annotation.Annotation;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;

/**
 * The pattern of the unsigned 64-bit keys (ADR-0010 C-175) that {@link ArgosUnsignedIdCustomizer}
 * puts on schema properties and parameters; lint rule 10 checks the generated document.
 */
@Tag("req:DSN-14-10")
class ArgosUnsignedIdCustomizerTest {

  private static final String PATTERN = "^[0-9]{1,20}$";

  /** A DTO and a handler with generic names. */
  static class Fixture {
    @ArgosUnsignedId String id;

    public void handler(@ArgosUnsignedId List<String> service, String other) {}
  }

  private final ArgosUnsignedIdCustomizer customizer = new ArgosUnsignedIdCustomizer();

  @Test
  @DisplayName(
      "T-API-001a a listed property name gets the pattern on its string, or on the string items"
          + " of its array; other names and types are left alone")
  void byName() {
    StringSchema tx = new StringSchema();
    customizer.customize(tx, new AnnotatedType(String.class).propertyName("tx_id"));
    assertThat(tx.getPattern()).isEqualTo(PATTERN);

    ArraySchema services = new ArraySchema().items(new StringSchema());
    customizer.customize(services, new AnnotatedType(List.class).propertyName("service_id"));
    assertThat(services.getItems().getPattern()).isEqualTo(PATTERN);
    assertThat(services.getPattern()).isNull();

    StringSchema trace = new StringSchema();
    customizer.customize(trace, new AnnotatedType(String.class).propertyName("trace_id"));
    assertThat(trace.getPattern()).as("trace_id is 32 hex digits, not a 64-bit key").isNull();

    // A 64-bit key typed as a number is a DTO defect: left as it is, lint rule 10 reports it.
    IntegerSchema number = new IntegerSchema();
    customizer.customize(number, new AnnotatedType(Long.class).propertyName("sql_id"));
    assertThat(number.getPattern()).isNull();
  }

  @Test
  @DisplayName(
      "T-API-001a a generic name gets the pattern only with @ArgosUnsignedId; listed parameter"
          + " names and annotated parameters get it too")
  void byAnnotationAndParameters() throws Exception {
    Annotation marked = Fixture.class.getDeclaredField("id").getAnnotation(ArgosUnsignedId.class);
    StringSchema id = new StringSchema();
    customizer.customize(
        id,
        new AnnotatedType(String.class)
            .propertyName("id")
            .ctxAnnotations(new Annotation[] {marked}));
    assertThat(id.getPattern()).isEqualTo(PATTERN);
    StringSchema plain = new StringSchema();
    customizer.customize(plain, new AnnotatedType(String.class).propertyName("id"));
    assertThat(plain.getPattern()).isNull();

    Parameter txId = new Parameter().name("txId").schema(new StringSchema());
    customizer.customize(txId, null);
    assertThat(txId.getSchema().getPattern()).isEqualTo(PATTERN);

    var handler = Fixture.class.getMethod("handler", List.class, String.class);
    Parameter service =
        new Parameter().name("service").schema(new ArraySchema().items(new StringSchema()));
    customizer.customize(service, new MethodParameter(handler, 0));
    assertThat(service.getSchema().getItems().getPattern()).isEqualTo(PATTERN);
    Parameter other = new Parameter().name("other").schema(new StringSchema());
    customizer.customize(other, new MethodParameter(handler, 1));
    assertThat(other.getSchema().getPattern()).isNull();
  }
}
