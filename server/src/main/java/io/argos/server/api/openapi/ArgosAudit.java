/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.openapi;

import io.argos.server.audit.AuditEvent;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The audit events of a REST handler, emitted as the OpenAPI extension {@code x-argos-audit} (14
 * §2.8; event names per 16 §11.1, ADR-0010 C-106 (7)). Required on every POST, PUT, PATCH and
 * DELETE handler and on sensitive reads; the contract test T-API-001 fails without it.
 *
 * <pre>{@code
 * @PostMapping("/api/v1/auth/login")
 * @RequiresRole(Role.PUBLIC)
 * @ArgosAudit({AuditEvent.LOGIN_SUCCESS, AuditEvent.LOGIN_FAILED})
 * public LoginResponse login(...) { ... }
 *
 * @PostMapping("/api/v1/csp-report")
 * @RequiresRole(Role.PUBLIC)
 * @ArgosAudit(none = "Reports are only counted and logged, never stored (16 §9.9)")
 * public void report(...) { ... }
 * }</pre>
 *
 * The annotation documents the contract only: the handler or its service records the events through
 * {@code AuditService}. A POST that changes no state and writes no event says so with {@link
 * #none()}, which is emitted as {@value #NONE}, so the decision is explicit and reviewable.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface ArgosAudit {

  /** The {@code x-argos-audit} value of an operation that writes no audit event. */
  String NONE = "none";

  /**
   * The events the operation can record, the main one first. In the extension they are joined with
   * commas: {@code LOGIN_SUCCESS,LOGIN_FAILED}. Empty only together with {@link #none()}.
   */
  AuditEvent[] value() default {};

  /** Why this operation writes no audit event; non-blank only when {@link #value()} is empty. */
  String none() default "";
}
