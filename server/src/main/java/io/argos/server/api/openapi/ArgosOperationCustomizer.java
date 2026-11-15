/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.openapi;

import io.argos.server.audit.AuditEvent;
import io.argos.server.auth.HandlerRoles;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;

/**
 * Adds the {@code x-argos-*} extensions of 14 §2.8 to every operation of the OpenAPI document, from
 * the annotations of its handler:
 *
 * <ul>
 *   <li>{@value #ROLE}: {@code public}, {@code viewer}, {@code operator} or {@code admin}, from
 *       {@code @RequiresRole} through {@link HandlerRoles}, the same answer the run-time check uses
 *       (DSN-12-11);
 *   <li>{@value #PII}: {@code view}, from {@link ArgosPii};
 *   <li>{@value #AUDIT}: the event names of {@link ArgosAudit} joined with commas, or {@value
 *       ArgosAudit#NONE}.
 * </ul>
 *
 * WO-23 adds:
 *
 * <ul>
 *   <li>{@value #CSV}: {@code true}, from {@link ArgosCsv} (14 §4.17), and a {@value #TEXT_CSV}
 *       content ({@code string}, {@code binary}) next to the JSON one of the 200 response, where
 *       the handler did not declare it: the CSV form is a hidden handler of the same path ({@link
 *       #addCsvContent}); its {@value #FORMAT} parameter is the enum {@code json}, {@code csv}
 *       ({@link #enumerateFormat}, ADR-0010 C-191 (1));
 *   <li>{@value #LIMITS}: {@code {range_max, rows_max, timeout_s}} from {@link ArgosLimits}, each
 *       key only when set (14 §2.8);
 *   <li>{@value #SOURCE}: the table list of {@link ArgosSource};
 *   <li>{@code x-argos-untrusted} on schema properties: {@link ArgosUntrustedPropertyCustomizer};
 *   <li>the {@code operationId} of a handler that names none: {@code <resource>_<verb>} (14 §2.9)
 *       from the controller and method names ({@link #derivedOperationId}).
 * </ul>
 *
 * An annotation on the method wins over the one on the class. A handler without a role has no
 * {@value #ROLE} here, but such a handler never gets this far: {@code RoleMatrix} fails the start.
 */
@Component
public class ArgosOperationCustomizer implements OperationCustomizer {

  /** The minimum role of the operation. */
  public static final String ROLE = "x-argos-role";

  /** The extra permission {@code pii.view} is needed. */
  public static final String PII = "x-argos-pii";

  /** The audit events of the operation. */
  public static final String AUDIT = "x-argos-audit";

  /** The operation supports {@code format=csv}. */
  public static final String CSV = "x-argos-csv";

  /** The bounds of the operation: {@code range_max}, {@code rows_max}, {@code timeout_s}. */
  public static final String LIMITS = "x-argos-limits";

  /** The tables the operation reads. */
  public static final String SOURCE = "x-argos-source";

  /** The media type of a CSV export (14 §4.17). */
  public static final String TEXT_CSV = "text/csv";

  /** The query parameter that selects the CSV form of a CSV operation. */
  static final String FORMAT = "format";

  /** The values of {@value #FORMAT} (14 §4.17): JSON by default, or CSV. */
  static final List<String> FORMATS = List.of("json", "csv");

  private final HandlerRoles roles;

  public ArgosOperationCustomizer(HandlerRoles roles) {
    this.roles = roles;
  }

  @Override
  public Operation customize(Operation operation, HandlerMethod handler) {
    if (!hasExplicitOperationId(handler)) {
      operation.setOperationId(derivedOperationId(handler));
    }
    roles.required(handler).ifPresent(role -> operation.addExtension(ROLE, role.extensionValue()));
    if (find(handler, ArgosPii.class).isPresent()) {
      operation.addExtension(PII, ArgosPii.VIEW);
    }
    find(handler, ArgosAudit.class)
        .flatMap(ArgosOperationCustomizer::auditValue)
        .ifPresent(value -> operation.addExtension(AUDIT, value));
    if (find(handler, ArgosCsv.class).isPresent()) {
      operation.addExtension(CSV, true);
      addCsvContent(operation);
      enumerateFormat(operation);
    }
    find(handler, ArgosLimits.class)
        .map(ArgosOperationCustomizer::limitsValue)
        .filter(value -> !value.isEmpty())
        .ifPresent(value -> operation.addExtension(LIMITS, value));
    find(handler, ArgosSource.class)
        .filter(source -> source.value().length > 0)
        .ifPresent(source -> operation.addExtension(SOURCE, List.of(source.value())));
    return operation;
  }

  /**
   * Adds a {@value #TEXT_CSV} content (a {@code string} of format {@code binary}) to the 200
   * response of a CSV operation that does not declare one (14 §4.17): its {@code format=csv} form
   * is a separate, hidden handler of the same path, so springdoc sees only the JSON form.
   */
  @SuppressWarnings("rawtypes") // swagger's raw Schema type
  static void addCsvContent(Operation operation) {
    ApiResponses responses = operation.getResponses();
    ApiResponse ok = responses == null ? null : responses.get("200");
    if (ok == null) {
      return;
    }
    Content content = ok.getContent();
    if (content == null) {
      content = new Content();
      ok.setContent(content);
    }
    if (!content.containsKey(TEXT_CSV)) {
      Schema text = new Schema();
      text.setType("string");
      text.addType("string");
      text.setFormat("binary");
      content.addMediaType(TEXT_CSV, new MediaType().schema(text));
    }
  }

  /**
   * Writes the values of the {@value #FORMAT} query parameter of a CSV operation as an enum, {@code
   * json} and {@code csv} (14 §4.17, ADR-0010 C-191 (1)). Documentation only: the handlers still
   * route {@code csv} to the hidden CSV form and accept {@code json} in any case or no value.
   */
  @SuppressWarnings({"rawtypes", "unchecked"}) // swagger's raw Schema type
  static void enumerateFormat(Operation operation) {
    if (operation.getParameters() == null) {
      return;
    }
    for (Parameter parameter : operation.getParameters()) {
      Schema schema = parameter.getSchema();
      if ("query".equals(parameter.getIn())
          && FORMAT.equals(parameter.getName())
          && schema != null) {
        schema.setEnum(new ArrayList<>(FORMATS));
      }
    }
  }

  /** The {@value #LIMITS} object: the keys of the set members only, in the order of 14 §2.8. */
  static Map<String, Object> limitsValue(ArgosLimits limits) {
    Map<String, Object> value = new LinkedHashMap<>();
    if (!limits.rangeMax().isBlank()) {
      value.put("range_max", limits.rangeMax());
    }
    if (limits.rowsMax() > 0) {
      value.put("rows_max", limits.rowsMax());
    }
    if (limits.timeoutS() > 0) {
      value.put("timeout_s", limits.timeoutS());
    }
    return value;
  }

  /**
   * The {@value #AUDIT} value of an annotation: the event names, or {@value ArgosAudit#NONE} with a
   * reason. Empty for an annotation that names neither or both, so that the contract test T-API-001
   * reports the operation.
   */
  static Optional<String> auditValue(ArgosAudit audit) {
    boolean hasEvents = audit.value().length > 0;
    boolean hasReason = !audit.none().isBlank();
    if (hasEvents == hasReason) {
      return Optional.empty();
    }
    return hasReason
        ? Optional.of(ArgosAudit.NONE)
        : Optional.of(
            Arrays.stream(audit.value()).map(AuditEvent::name).collect(Collectors.joining(",")));
  }

  /** Whether the handler names its operation ({@code @Operation(operationId = ...)}). */
  static boolean hasExplicitOperationId(HandlerMethod handler) {
    io.swagger.v3.oas.annotations.Operation op =
        AnnotatedElementUtils.findMergedAnnotation(
            handler.getMethod(), io.swagger.v3.oas.annotations.Operation.class);
    return op != null && !op.operationId().isBlank();
  }

  /**
   * The {@code <resource>_<verb>} operation id of 14 §2.9 for a handler that names none: the
   * controller's simple name without {@code Controller} and the method name, both in snake case,
   * e.g. {@code AdminUsersController.create} → {@code admin_users_create}, {@code
   * AuthController.login} → {@code auth_login}.
   */
  static String derivedOperationId(HandlerMethod handler) {
    String type = ClassUtils.getUserClass(handler.getBeanType()).getSimpleName();
    if (type.endsWith("Controller") && type.length() > "Controller".length()) {
      type = type.substring(0, type.length() - "Controller".length());
    }
    String resource = snake(type);
    String verb = snake(handler.getMethod().getName());
    return resource.equals(verb) || resource.endsWith("_" + verb)
        ? resource
        : resource + "_" + verb;
  }

  /** {@code CamelCase} to {@code snake_case}. */
  static String snake(String name) {
    StringBuilder out = new StringBuilder(name.length() + 8);
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (Character.isUpperCase(c)) {
        if (i > 0) {
          out.append('_');
        }
        out.append(Character.toLowerCase(c));
      } else if (Character.isLetterOrDigit(c)) {
        out.append(c);
      } else {
        out.append('_');
      }
    }
    return out.toString();
  }

  private static <A extends Annotation> Optional<A> find(HandlerMethod handler, Class<A> type) {
    A onMethod = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), type);
    if (onMethod != null) {
      return Optional.of(onMethod);
    }
    Class<?> beanType = ClassUtils.getUserClass(handler.getBeanType());
    return Optional.ofNullable(AnnotatedElementUtils.findMergedAnnotation(beanType, type));
  }
}
