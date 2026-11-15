/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.function.support.RouterFunctionMapping;
import org.springframework.web.servlet.handler.AbstractHandlerMethodMapping;
import org.springframework.web.servlet.handler.AbstractUrlHandlerMapping;
import org.springframework.web.servlet.mvc.ParameterizableViewController;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

/**
 * The part of {@link RoleMatrix} that looks beyond {@code @RequestMapping} methods (DSN-12-11): a
 * handler that is not a handler method cannot carry {@link RequiresRole}, so the HTTP port may map
 * only the few such handlers that need no role. Every {@link HandlerMapping} bean that the
 * dispatcher of the HTTP port uses is inspected:
 *
 * <ul>
 *   <li>handler-method mappings ({@code RequestMappingHandlerMapping}, Actuator's additional health
 *       paths): their methods are {@link RoleMatrix#missing()}'s and the interceptor's;
 *   <li>URL mappings ({@code BeanNameUrlHandlerMapping}, {@code SimpleUrlHandlerMapping}, Spring
 *       Boot's welcome page mappings): every pattern, the root and the default handler must be one
 *       of {@link #allowed};
 *   <li>the router function mapping: refused as soon as it has a router function;
 *   <li>a mapping of any other type: refused (its handlers cannot be listed).
 * </ul>
 *
 * Allowed outside {@code /api} only: Spring's static resource handler, a {@link
 * SelfAuthenticatingHandler}, and Spring Boot's two welcome page handlers of {@code /} (a forward
 * to {@code index.html} and a 406 for clients that do not accept HTML; {@code IndexHtmlController}
 * maps {@code /} itself and is asked first, so neither ever runs, and the interceptor would refuse
 * both). Under {@code /api} no such handler is allowed. These are the handler types of the server's
 * context as enumerated on Spring Boot 4.1 (WO-24 r1), with and without a {@code
 * static/index.html}.
 */
final class NonMethodHandlers {

  private static final String API_PREFIX = "/api";
  private static final String BOOT_WELCOME_PAGE =
      "org.springframework.boot.webmvc.autoconfigure.WelcomePageHandlerMapping";
  private static final String BOOT_WELCOME_NOT_ACCEPTABLE =
      "org.springframework.boot.webmvc.autoconfigure.WelcomePageNotAcceptableHandlerMapping";

  private NonMethodHandlers() {}

  /**
   * The refused handlers of the context's dispatcher, as {@code pattern -> handler type (mapping
   * bean)}, sorted.
   */
  static List<String> refused(ApplicationContext context) {
    return refused(
        BeanFactoryUtils.beansOfTypeIncludingAncestors(context, HandlerMapping.class, true, false));
  }

  /** The refused handlers of {@code mappings} (bean name to mapping), sorted. */
  static List<String> refused(Map<String, HandlerMapping> mappings) {
    List<String> refused = new ArrayList<>();
    for (Map.Entry<String, HandlerMapping> entry : new TreeMap<>(mappings).entrySet()) {
      String bean = entry.getKey();
      HandlerMapping mapping = entry.getValue();
      if (mapping instanceof AbstractHandlerMethodMapping<?>) {
        continue;
      }
      if (mapping instanceof RouterFunctionMapping router) {
        if (router.getRouterFunction() != null) {
          refused.add("router function " + router.getRouterFunction() + " (" + bean + ")");
        }
        continue;
      }
      if (mapping instanceof AbstractUrlHandlerMapping url) {
        new TreeMap<>(url.getHandlerMap())
            .forEach((pattern, handler) -> check(pattern, handler, url, bean, refused));
        if (url.getRootHandler() != null) {
          check("/", url.getRootHandler(), url, bean, refused);
        }
        if (url.getDefaultHandler() != null) {
          check("(default)", url.getDefaultHandler(), url, bean, refused);
        }
        continue;
      }
      refused.add(
          "handler mapping of unsupported type "
              + mapping.getClass().getName()
              + " ("
              + bean
              + ")");
    }
    return List.copyOf(refused);
  }

  private static void check(
      String pattern,
      Object handler,
      AbstractUrlHandlerMapping mapping,
      String bean,
      List<String> refused) {
    if (!allowed(pattern, handler, mapping)) {
      refused.add(pattern + " -> " + handler.getClass().getName() + " (" + bean + ")");
    }
  }

  /** Whether {@code handler}, mapped to {@code pattern} by {@code mapping}, needs no role. */
  static boolean allowed(String pattern, Object handler, AbstractUrlHandlerMapping mapping) {
    if (pattern.startsWith(API_PREFIX)) {
      return false;
    }
    if (handler instanceof ResourceHttpRequestHandler
        || handler instanceof SelfAuthenticatingHandler) {
      return true;
    }
    if (!"/".equals(pattern)) {
      return false;
    }
    String mappingType = mapping.getClass().getName();
    if (BOOT_WELCOME_PAGE.equals(mappingType)) {
      return handler instanceof ParameterizableViewController;
    }
    // The 406 handler is a lambda of the mapping class itself.
    return BOOT_WELCOME_NOT_ACCEPTABLE.equals(mappingType)
        && handler.getClass().getName().startsWith(BOOT_WELCOME_NOT_ACCEPTABLE + "$$Lambda");
  }
}
