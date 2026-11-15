/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.openapi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a property or a request parameter with a generic name (such as {@code id} or {@code
 * service}) that holds unsigned 64-bit keys as decimal strings (ADR-0010 C-175). Properties and
 * parameters whose name always means such a key ({@code tx_id}, {@code service_id}, ...) need no
 * annotation: {@link ArgosUnsignedIdCustomizer} recognizes them by name. Added by WO-23.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({
  ElementType.FIELD,
  ElementType.RECORD_COMPONENT,
  ElementType.METHOD,
  ElementType.PARAMETER
})
public @interface ArgosUnsignedId {}
