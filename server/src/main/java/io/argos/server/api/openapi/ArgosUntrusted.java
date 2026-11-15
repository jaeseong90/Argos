/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.openapi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a response property as a string that comes from collected data (service names, URLs, SQL,
 * exceptions, ...): clients render it as text only (14 §2.8, §2.10, SEC-60). Emitted on the schema
 * property as the OpenAPI extension {@code x-argos-untrusted: true}. The annotation is created by
 * WO-24; WO-23 emits the extension and puts it on the response types.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.METHOD})
public @interface ArgosUntrusted {}
