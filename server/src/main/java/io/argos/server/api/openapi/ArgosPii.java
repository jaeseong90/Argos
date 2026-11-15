/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.openapi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a REST handler that shows personal data only with the extra permission {@code pii.view}
 * (bind parameters in {@code full} mode, environment dumps; 14 §2.8, §7.3, ADR-0010 C-106 (6)).
 * Emitted as the OpenAPI extension {@code x-argos-pii: view}. Documentation of the contract: the
 * handler or its service performs the check (403 {@code pii_view_required}, or the {@code
 * masked_by_role} downgrade of 14 §7.3).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface ArgosPii {

  /** The only value of the extension (14 §2.8). */
  String VIEW = "view";
}
