/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.openapi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a REST handler that supports {@code format=csv} (14 §2.8, §4.17). Emitted as the OpenAPI
 * extension {@code x-argos-csv: true}. The annotation is created by WO-24; WO-23 emits the
 * extension and puts it on the export handlers.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ArgosCsv {}
