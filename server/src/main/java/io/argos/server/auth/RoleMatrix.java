/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import io.argos.server.bootstrap.PhaseOrder;
import io.argos.server.bootstrap.StartupPhase;
import io.argos.server.bootstrap.StartupPhaseException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The start check of DSN-12-11 (12 §7.3, SEC-49): every handler of every {@link
 * RequestMappingHandlerMapping} of the HTTP port has a role, through {@link RequiresRole} on the
 * method or class or a {@link HandlerRoleSource} ({@link HandlerRoles}, the same answer the
 * run-time check gives). One handler without a role fails the start, before any port opens, naming
 * the offending methods. This holds for the handlers that later work orders add, without any change
 * here.
 *
 * <p>Handlers are named by signature, {@code class#method(parameter types)}: every overload is
 * checked and listed on its own, so a role on one overload never hides another without one.
 *
 * <p>Handlers that are not {@code @RequestMapping} methods cannot carry the annotation, so they
 * fail the start too ({@link #refusedNonMethodHandlers()}): a bean whose name is a URL ({@code
 * BeanNameUrlHandlerMapping}), an {@code HttpRequestHandler} or MVC {@code Controller} in a URL
 * mapping, any router function, a handler mapping of an unknown type. Allowed are only static
 * resources, a {@link SelfAuthenticatingHandler} and Spring Boot's welcome page handlers of {@code
 * /}, and none of them under {@code /api}.
 *
 * <p>Not inspected: Actuator's own handlers (they live in the management port's child context and
 * are governed by SEC-55). springdoc's handlers get {@code VIEWER} from {@code
 * OpenApiConfiguration}'s role source.
 */
@Component
public class RoleMatrix implements StartupPhase {

  /** Longest list of offending methods in the single ERROR line of the failed start. */
  private static final int MAX_LISTED = 8;

  private static final Logger LOG = LoggerFactory.getLogger(RoleMatrix.class);
  private static final List<String> EXEMPT_PACKAGES =
      List.of("org.springframework.boot.actuate.", "org.springframework.boot.webmvc.actuate.");

  private final ApplicationContext context;
  private final HandlerRoles roles;

  public RoleMatrix(ApplicationContext context, HandlerRoles roles) {
    this.context = context;
    this.roles = roles;
  }

  @Override
  public PhaseOrder order() {
    return PhaseOrder.CONFIG;
  }

  @Override
  public String name() {
    return "role-matrix";
  }

  /**
   * Fails the start when a handler has no role.
   *
   * @throws StartupPhaseException naming the offending methods; every one of them is also logged
   *     separately, because the ERROR line of the start sequence has a bounded length
   */
  @Override
  public void run() throws StartupPhaseException {
    List<String> missing = missing();
    List<String> refused = refusedNonMethodHandlers();
    if (missing.isEmpty() && refused.isEmpty()) {
      return;
    }
    missing.forEach(method -> LOG.warn("Handler without @RequiresRole: {}", method));
    refused.forEach(handler -> LOG.warn("Handler that cannot carry @RequiresRole: {}", handler));
    List<String> parts = new ArrayList<>();
    if (!missing.isEmpty()) {
      parts.add(
          listed(missing.size() + " REST handler(s) have no @RequiresRole (DSN-12-11): ", missing));
    }
    if (!refused.isEmpty()) {
      parts.add(
          listed(
              refused.size()
                  + " handler(s) are not @RequestMapping methods and cannot carry @RequiresRole"
                  + " (DSN-12-11): ",
              refused));
    }
    throw new StartupPhaseException(String.join("; ", parts));
  }

  private static String listed(String prefix, List<String> items) {
    List<String> listed = items.subList(0, Math.min(items.size(), MAX_LISTED));
    return prefix + String.join(", ", listed) + (listed.size() < items.size() ? ", ..." : "");
  }

  /**
   * The handlers of the HTTP port that are not handler methods and need a role they cannot carry:
   * URL-mapped handlers other than static resources, a {@link SelfAuthenticatingHandler} and Spring
   * Boot's welcome page (and none of those under {@code /api}), router functions, and mappings of
   * unknown types ({@link NonMethodHandlers}), as {@code pattern -> handler type (mapping bean)}.
   */
  public List<String> refusedNonMethodHandlers() {
    return NonMethodHandlers.refused(context);
  }

  /** The handlers without a role, as {@code class#method(parameter types)}, sorted. */
  public List<String> missing() {
    return missing(context.getBeansOfType(RequestMappingHandlerMapping.class).values());
  }

  /** The handlers of {@code mappings} without a role, as {@code class#method(parameter types)}. */
  List<String> missing(Collection<RequestMappingHandlerMapping> mappings) {
    List<String> missing = new ArrayList<>();
    for (Map.Entry<String, HandlerMethod> handler : handlers(mappings).entrySet()) {
      if (!exempt(handler.getValue()) && roles.required(handler.getValue()).isEmpty()) {
        missing.add(handler.getKey());
      }
    }
    return List.copyOf(missing);
  }

  /**
   * Every handler method by its {@link #signature}, sorted. A method mapped to several paths is one
   * entry; overloads are separate entries, each checked on its own.
   */
  private static Map<String, HandlerMethod> handlers(
      Collection<RequestMappingHandlerMapping> mappings) {
    Map<String, HandlerMethod> handlers = new TreeMap<>();
    for (RequestMappingHandlerMapping mapping : mappings) {
      for (HandlerMethod handler : mapping.getHandlerMethods().values()) {
        handlers.putIfAbsent(signature(handler), handler);
      }
    }
    return handlers;
  }

  private static boolean exempt(HandlerMethod handler) {
    String type = handler.getBeanType().getName();
    return EXEMPT_PACKAGES.stream().anyMatch(type::startsWith);
  }

  /** {@code class#method(parameter types)}, the parameter types fully qualified. */
  private static String signature(HandlerMethod handler) {
    Method method = handler.getMethod();
    return handler.getBeanType().getName()
        + "#"
        + method.getName()
        + Arrays.stream(method.getParameterTypes())
            .map(Class::getTypeName)
            .collect(Collectors.joining(",", "(", ")"));
  }

  /**
   * Every handler with its role, keyed by {@code class#method(parameter types)}, for the report of
   * a test or an operator tool.
   */
  public Map<String, String> table() {
    Map<String, String> table = new TreeMap<>();
    handlers(context.getBeansOfType(RequestMappingHandlerMapping.class).values())
        .forEach(
            (name, handler) ->
                table.put(
                    name, roles.required(handler).map(Role::extensionValue).orElse("(none)")));
    return table;
  }
}
