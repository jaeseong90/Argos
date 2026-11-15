/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.springwebmvc;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.TraceContext;
import io.argos.agent.bootstrap.Tracer;

/**
 * Namespace-independent part of the Spring MVC naming (10 §6.2, F-A02, F-A29, F-A36), used by
 * {@link SpringWebMvcHelperJavax} and {@link SpringWebMvcHelperJakarta}. Injected into the
 * application class loader: bootstrap API only, no state.
 *
 * <ul>
 *   <li>A best matching pattern fixes {@code [<context path> ]<METHOD> <pattern>}: the actual
 *       request method, also for a mapping without a method restriction.
 *   <li>No pattern but a {@code HandlerMethod}: {@code [<context path> ]<Controller>#<method>}, not
 *       fixed; the exit of the same {@code handle} call reads the pattern again ({@link #retry})
 *       and a pattern found then fixes the name. Otherwise the tracer keeps the handler name at the
 *       end (its token is not a path, so the F-A29 fallback leaves it).
 *   <li>Neither: nothing; the servlet module's mapping pattern or the F-A29 path fallback names the
 *       transaction at the end.
 * </ul>
 */
public final class SpringWebMvcHelperCommon {
  /** {@code HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE} (Framework 5.3-7.x). */
  public static final String BEST_MATCHING_PATTERN =
      "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern";

  /** {@code org.springframework.web.method.HandlerMethod} (spring-web, Framework 5.3-7.x). */
  public static final String HANDLER_METHOD = "org.springframework.web.method.HandlerMethod";

  /** Superclasses walked at most to find {@link #HANDLER_METHOD} (bounded loop). */
  static final int MAX_HIERARCHY_DEPTH = 8;

  private SpringWebMvcHelperCommon() {}

  /**
   * The transaction on this thread whose name is not fixed yet, or null (no transaction, or a
   * module already fixed the name: the first fixed name wins, so a FORWARD keeps it).
   */
  public static TraceContext unnamedContext() {
    TraceContext context = Argos.tracer().current();
    return context == null || context.serviceFixed ? null : context;
  }

  /**
   * Enter of {@code HandlerAdapter.handle} on the REQUEST dispatch (C-48): names {@code context}
   * from the pattern (fixed) or, without one, from a {@code HandlerMethod} {@code handler} (not
   * fixed). Returns {@code context} when the exit should retry with the pattern (handler-method
   * name set), else null.
   */
  public static TraceContext nameService(
      TraceContext context, String contextPath, String method, Object pattern, Object handler) {
    Tracer tracer = Argos.tracer();
    String name =
        pattern instanceof String ? serviceName(contextPath, method, (String) pattern) : null;
    if (name != null) {
      tracer.setServiceName(context, name, true);
      return null;
    }
    String handlerName = handlerMethodName(contextPath, handler);
    if (handlerName == null) {
      return null;
    }
    tracer.setServiceName(context, handlerName, false);
    return context;
  }

  /**
   * Exit of the same {@code handle} call after a handler-method name: a pattern that is on the
   * request now fixes the name (10 §6.2 "종료 시 재시도").
   */
  public static void retry(
      TraceContext context, String contextPath, String method, Object pattern) {
    if (context.serviceFixed || context.isEnded() || !(pattern instanceof String)) {
      return;
    }
    String name = serviceName(contextPath, method, (String) pattern);
    if (name != null) {
      Argos.tracer().setServiceName(context, name, true);
    }
  }

  /** {@code [<context path> ]<METHOD> <pattern>} (F-A36 prefix), or null without pattern. */
  public static String serviceName(String contextPath, String method, String pattern) {
    if (pattern == null || pattern.isEmpty() || method == null || method.isEmpty()) {
      return null;
    }
    return prefixed(contextPath, method + " " + pattern);
  }

  /**
   * {@code [<context path> ]<Controller>#<method>} of a Spring {@code HandlerMethod} (matched by
   * class name, Spring is not a compile dependency), or null for any other handler. The text comes
   * from {@code HandlerMethod.toString()}, which Spring builds once as {@code <bean
   * type>#<method>(<parameter types>)}: code identifiers, never request data.
   */
  public static String handlerMethodName(String contextPath, Object handler) {
    if (handler == null || !isHandlerMethod(handler.getClass())) {
      return null;
    }
    String description = handler.toString();
    if (description == null) {
      return null;
    }
    int paren = description.indexOf('(');
    String head = paren < 0 ? description : description.substring(0, paren);
    int hash = head.lastIndexOf('#');
    if (hash <= 0 || hash == head.length() - 1) {
      return null;
    }
    String type = head.substring(0, hash);
    String simple = type.substring(type.lastIndexOf('.') + 1);
    if (simple.isEmpty()) {
      return null;
    }
    return prefixed(contextPath, simple + "#" + head.substring(hash + 1));
  }

  /** True when {@code type} is {@link #HANDLER_METHOD} or extends it. */
  static boolean isHandlerMethod(Class<?> type) {
    Class<?> current = type;
    for (int depth = 0; current != null && depth < MAX_HIERARCHY_DEPTH; depth++) {
      if (HANDLER_METHOD.equals(current.getName())) {
        return true;
      }
      current = current.getSuperclass();
    }
    return false;
  }

  private static String prefixed(String contextPath, String name) {
    return contextPath == null || contextPath.isEmpty() ? name : contextPath + " " + name;
  }
}
