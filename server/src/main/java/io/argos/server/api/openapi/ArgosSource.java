/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.openapi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The tables a REST handler reads, emitted as the OpenAPI extension {@code x-argos-source} (14
 * §2.8: "조회 소스(문서화 목적)"), e.g. {@code @ArgosSource({"xlog", "app_stat_1m", "app_stat_1h"})}.
 * Logical table names (13 §3, §4) or {@code postgres:<table>}, {@code memory:<what>} for other
 * sources. Documentation only; added by WO-23.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface ArgosSource {

  /** The tables, in the order they matter. */
  String[] value();
}
