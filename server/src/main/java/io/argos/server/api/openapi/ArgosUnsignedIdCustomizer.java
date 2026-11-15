/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.openapi;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.lang.annotation.Annotation;
import java.util.Set;
import org.springdoc.core.customizers.ParameterCustomizer;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;

/**
 * Describes the unsigned 64-bit keys of the API as what they are on the wire: decimal strings of 1
 * to 20 digits, {@code type: string} with {@code pattern: '^[0-9]{1,20}$'} (ADR-0010 C-175). JSON
 * numbers cannot carry 64 bits in a browser (2^53), so a generated client must keep these values as
 * opaque strings. The one place that decides which properties and parameters these are:
 *
 * <ul>
 *   <li>a schema property whose JSON name is in {@link #PROPERTIES}, in every schema (response and
 *       request, nullable variants and array items included: the pattern is set before {@link
 *       ArgosRecordSchemaConverter} copies a property for its nullable type);
 *   <li>a path or query parameter whose name is in {@link #PARAMETERS};
 *   <li>a property or parameter with a generic name that carries {@link ArgosUnsignedId}.
 * </ul>
 *
 * Contract lint rule 10 ({@code OpenApiLint}, T-API-001) checks the generated document against its
 * own list with the evidence of each key. Integer keys below 2^53 ({@code app_id}, {@code
 * instance_id}) stay JSON numbers. Added by WO-23.
 */
@Component
public class ArgosUnsignedIdCustomizer implements PropertyCustomizer, ParameterCustomizer {

  /** The pattern of an unsigned 64-bit decimal: 1 to 20 digits. */
  public static final String PATTERN = "^[0-9]{1,20}$";

  /**
   * JSON property names that always hold unsigned 64-bit keys: transaction ids ({@code tx_id} and
   * its references, {@code critical_path} as a list of them), the dictionary keys ({@code
   * service_id}, {@code version_id}, {@code error_class_id}, {@code sql_id}, {@code db_id}, {@code
   * host_id}, {@code name_id}) and the error group fingerprint ({@code error_group_id}, {@code
   * group_id}).
   */
  public static final Set<String> PROPERTIES =
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

  /** Path and query parameter names that always carry an unsigned 64-bit key. */
  public static final Set<String> PARAMETERS =
      Set.of("txId", "serviceId", "sqlId", "hostId", "groupId", "db_id");

  @Override
  @SuppressWarnings("rawtypes") // springdoc's interface uses the raw swagger Schema type
  public Schema customize(Schema property, AnnotatedType type) {
    if (property != null
        && type != null
        && (PROPERTIES.contains(type.getPropertyName()) || marked(type.getCtxAnnotations()))) {
      describe(property);
    }
    return property;
  }

  @Override
  public Parameter customize(Parameter parameter, MethodParameter method) {
    if (parameter != null
        && (PARAMETERS.contains(parameter.getName())
            || (method != null && method.hasParameterAnnotation(ArgosUnsignedId.class)))) {
      describe(parameter.getSchema());
    }
    return parameter;
  }

  /**
   * Sets the pattern on a string schema or on the string items of an array schema. Any other type
   * is left as it is: lint rule 10 reports it (a 64-bit key that is not a string is a defect of the
   * DTO, not of the document).
   */
  @SuppressWarnings("rawtypes")
  static void describe(Schema schema) {
    if (schema == null) {
      return;
    }
    if (is(schema, "array")) {
      Schema items = schema.getItems();
      if (items != null && is(items, "string")) {
        items.setPattern(PATTERN);
      }
    } else if (is(schema, "string")) {
      schema.setPattern(PATTERN);
    }
  }

  /** {@code type: t}, or {@code t} among the OpenAPI 3.1 types. */
  @SuppressWarnings("rawtypes")
  private static boolean is(Schema schema, String type) {
    return type.equals(schema.getType())
        || (schema.getTypes() != null && schema.getTypes().contains(type));
  }

  private static boolean marked(Annotation[] annotations) {
    if (annotations == null) {
      return false;
    }
    for (Annotation a : annotations) {
      if (a instanceof ArgosUnsignedId) {
        return true;
      }
    }
    return false;
  }
}
