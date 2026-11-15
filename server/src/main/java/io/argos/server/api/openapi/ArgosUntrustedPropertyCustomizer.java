/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.api.openapi;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.annotation.Annotation;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springframework.stereotype.Component;

/**
 * Emits {@value #UNTRUSTED}{@code : true} on every schema property whose field, record component or
 * accessor carries {@link ArgosUntrusted} (14 §2.8, §2.10, SEC-60): the generated client and the
 * lint of T-API-001 see which strings come from collected data. Added by WO-23.
 */
@Component
public class ArgosUntrustedPropertyCustomizer implements PropertyCustomizer {

  /** The schema property extension. */
  public static final String UNTRUSTED = "x-argos-untrusted";

  @Override
  @SuppressWarnings("rawtypes") // springdoc's interface uses the raw swagger Schema type
  public Schema customize(Schema property, AnnotatedType type) {
    if (property != null && isUntrusted(type)) {
      property.addExtension(UNTRUSTED, true);
    }
    return property;
  }

  static boolean isUntrusted(AnnotatedType type) {
    if (type == null || type.getCtxAnnotations() == null) {
      return false;
    }
    for (Annotation a : type.getCtxAnnotations()) {
      if (a instanceof ArgosUntrusted) {
        return true;
      }
    }
    return false;
  }
}
