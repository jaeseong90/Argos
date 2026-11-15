/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;

/**
 * The single place that answers "which role does this handler require" (DSN-12-11): {@link
 * RequiresRole} on the method, else on the controller class (proxies unwrapped), else the first
 * {@link HandlerRoleSource} bean that knows the handler. Used by {@link
 * RoleAuthorizationInterceptor} at run time and by {@code RoleMatrix} at start, so both always
 * agree.
 */
@Component
public class HandlerRoles {

  private final List<HandlerRoleSource> sources;

  @Autowired
  public HandlerRoles(ObjectProvider<HandlerRoleSource> sources) {
    this.sources = sources.orderedStream().toList();
  }

  /** A resolver with explicit sources (tests, start checks outside a context). */
  public HandlerRoles(List<HandlerRoleSource> sources) {
    this.sources = List.copyOf(sources);
  }

  /** The required role, or empty when the handler has none (which fails closed at run time). */
  public Optional<Role> required(HandlerMethod handler) {
    Optional<Role> annotated = annotated(handler);
    if (annotated.isPresent()) {
      return annotated;
    }
    for (HandlerRoleSource source : sources) {
      Optional<Role> role = source.roleOf(handler);
      if (role.isPresent()) {
        return role;
      }
    }
    return Optional.empty();
  }

  /** The role of the annotation on the method or its class, ignoring the sources. */
  public static Optional<Role> annotated(HandlerMethod handler) {
    RequiresRole onMethod =
        AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), RequiresRole.class);
    if (onMethod != null) {
      return Optional.of(onMethod.value());
    }
    Class<?> type = ClassUtils.getUserClass(handler.getBeanType());
    RequiresRole onClass = AnnotatedElementUtils.findMergedAnnotation(type, RequiresRole.class);
    return onClass == null ? Optional.empty() : Optional.of(onClass.value());
  }
}
