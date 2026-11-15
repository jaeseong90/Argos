/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import java.util.Optional;
import org.springframework.web.method.HandlerMethod;

/**
 * The role of a handler that cannot carry {@link RequiresRole} because its class is not Argos code
 * (e.g. the springdoc OpenAPI endpoint, served to viewers at {@code /api/v1/openapi.yaml}, 14 §8).
 * A bean of this type is consulted by {@link HandlerRoles} only when the handler method and class
 * have no annotation. Argos controllers never use it: they are annotated.
 */
@FunctionalInterface
public interface HandlerRoleSource {

  /** The minimum role of {@code handler}, or empty when this source does not know it. */
  Optional<Role> roleOf(HandlerMethod handler);
}
