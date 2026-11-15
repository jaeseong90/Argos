/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.openapi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The bounds of a REST handler, the OpenAPI extension {@code x-argos-limits: {range_max, rows_max,
 * timeout_s}} from which the UI writes its hints (14 §2.8). The annotation is created by WO-24;
 * WO-23 emits the extension and puts it on the query handlers.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ArgosLimits {

  /** The longest time range, e.g. {@code 24h}; empty when the handler takes no range. */
  String rangeMax() default "";

  /** The most rows of one response; 0 when not bounded by rows. */
  int rowsMax() default 0;

  /** The query timeout in seconds; 0 when the handler runs no query. */
  int timeoutS() default 0;
}
