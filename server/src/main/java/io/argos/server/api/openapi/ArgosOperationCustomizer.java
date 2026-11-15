/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.openapi;

import io.argos.server.audit.AuditEvent;
import io.argos.server.auth.HandlerRoles;
import io.swagger.v3.oas.models.Operation;
import java.lang.annotation.Annotation;
import java.util.Arrays;
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
 * An annotation on the method wins over the one on the class. A handler without a role has no
 * {@value #ROLE} here, but such a handler never gets this far: {@code RoleMatrix} fails the start.
 * WO-23 adds {@code x-argos-csv}, {@code x-argos-limits}, {@code x-argos-source} and {@code
 * x-argos-untrusted}.
 */
@Component
public class ArgosOperationCustomizer implements OperationCustomizer {

  /** The minimum role of the operation. */
  public static final String ROLE = "x-argos-role";

  /** The extra permission {@code pii.view} is needed. */
  public static final String PII = "x-argos-pii";

  /** The audit events of the operation. */
  public static final String AUDIT = "x-argos-audit";

  private final HandlerRoles roles;

  public ArgosOperationCustomizer(HandlerRoles roles) {
    this.roles = roles;
  }

  @Override
  public Operation customize(Operation operation, HandlerMethod handler) {
    roles.required(handler).ifPresent(role -> operation.addExtension(ROLE, role.extensionValue()));
    if (find(handler, ArgosPii.class).isPresent()) {
      operation.addExtension(PII, ArgosPii.VIEW);
    }
    find(handler, ArgosAudit.class)
        .flatMap(ArgosOperationCustomizer::auditValue)
        .ifPresent(value -> operation.addExtension(AUDIT, value));
    return operation;
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

  private static <A extends Annotation> Optional<A> find(HandlerMethod handler, Class<A> type) {
    A onMethod = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), type);
    if (onMethod != null) {
      return Optional.of(onMethod);
    }
    Class<?> beanType = ClassUtils.getUserClass(handler.getBeanType());
    return Optional.ofNullable(AnnotatedElementUtils.findMergedAnnotation(beanType, type));
  }
}
