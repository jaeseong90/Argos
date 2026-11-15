/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.auth;

import io.argos.server.api.error.ApiException;
import io.argos.server.api.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

/**
 * Enforces {@link RequiresRole} before every handler of the HTTP port (DSN-12-11, 12 §7.3, SEC-49).
 * Authorisation is decided here, per handler, from the annotation; there is no URL pattern matrix
 * in the security filter chain (it permits every request).
 *
 * <p>A handler method (a {@code @RequestMapping} method):
 *
 * <ol>
 *   <li>{@code PUBLIC}: allowed, with or without a session;
 *   <li>no authenticated {@link ArgosPrincipal}: 401 with the code recorded by the access-token
 *       filter ({@link AuthenticationFailure}: {@code session_expired}, {@code session_revoked}) or
 *       {@code unauthenticated}, whatever role the endpoint needs. This comes before the role
 *       check: a session revoked by a role change or a deactivation has no principal, so its next
 *       request is 401 everywhere, never 403;
 *   <li>a role below the required one: 403 {@code forbidden};
 *   <li>a handler without any role (only possible for a framework handler that no {@link
 *       HandlerRoleSource} knows, since {@code RoleMatrix} refuses unannotated Argos handlers at
 *       start): 403 {@code forbidden}, logged once per handler (fail closed).
 * </ol>
 *
 * Any other handler (a bean-name URL handler, an {@code HttpRequestHandler}, an MVC {@code
 * Controller}, a router function, a view controller) cannot carry the annotation and is refused
 * with 403 {@code forbidden} (fail closed, logged once per handler type), except:
 *
 * <ul>
 *   <li>Spring's static resource handler ({@code ResourceHttpRequestHandler}): the UI's files, and
 *       the 404 of a path that nothing else maps;
 *   <li>a {@link SelfAuthenticatingHandler}, which authenticates every request itself (the hook of
 *       WO-22's {@code /ws} handshake).
 * </ul>
 *
 * {@link RoleMatrix} refuses such handlers already at start, so at run time this is the second
 * line. Errors are thrown as {@link ApiException} and answered by the global exception handler.
 *
 * <p>Scope: the handlers of the HTTP port's application context. Spring MVC also hands the
 * interceptor to the child context of the management port (8081, {@code @EnableWebMvc} collects the
 * parent's configurers); requests dispatched there (Actuator, its error page, of whatever handler
 * type) are left to the management port's own access filter (SEC-55, WO-20). That decision comes
 * first, before the handler type.
 */
public class RoleAuthorizationInterceptor implements HandlerInterceptor {

  private static final Logger LOG = LoggerFactory.getLogger(RoleAuthorizationInterceptor.class);

  private final HandlerRoles roles;
  private final Object owner;
  private final Set<String> reportedUnknown = ConcurrentHashMap.newKeySet();

  /**
   * An interceptor for the handlers of {@code owner}.
   *
   * @param roles role resolver
   * @param owner the application context of the HTTP port; requests dispatched by another context
   *     (the management child) are not checked. Null checks every request.
   */
  public RoleAuthorizationInterceptor(HandlerRoles roles, Object owner) {
    this.roles = roles;
    this.owner = owner;
  }

  @Override
  public boolean preHandle(
      HttpServletRequest request, HttpServletResponse response, Object handler) {
    Object dispatcherContext =
        request.getAttribute(DispatcherServlet.WEB_APPLICATION_CONTEXT_ATTRIBUTE);
    if (owner != null && dispatcherContext != null && !owner.equals(dispatcherContext)) {
      return true;
    }
    if (!(handler instanceof HandlerMethod method)) {
      if (handler instanceof ResourceHttpRequestHandler
          || handler instanceof SelfAuthenticatingHandler) {
        return true;
      }
      String type = handler == null ? "null" : handler.getClass().getName();
      if (reportedUnknown.size() < 1000 && reportedUnknown.add(type)) {
        LOG.warn(
            "Handler {} is not a @RequestMapping method and cannot carry @RequiresRole: every"
                + " request is refused",
            type);
      }
      throw new ApiException(ErrorCode.FORBIDDEN);
    }
    Optional<Role> required = roles.required(method);
    if (required.isEmpty()) {
      String name = method.getBeanType().getName() + "#" + method.getMethod().getName();
      if (reportedUnknown.size() < 1000 && reportedUnknown.add(name)) {
        LOG.warn(
            "Handler {} has no @RequiresRole and no role source: every request is refused", name);
      }
      throw new ApiException(ErrorCode.FORBIDDEN);
    }
    authorize(required.get(), request);
    return true;
  }

  /**
   * Checks the current principal against {@code required}.
   *
   * @throws ApiException 401 without a principal, 403 {@code forbidden} for a lower role
   */
  public static void authorize(Role required, HttpServletRequest request) {
    if (required == Role.PUBLIC) {
      return;
    }
    Optional<ArgosPrincipal> principal = ArgosPrincipal.current();
    if (principal.isEmpty()) {
      throw new ApiException(AuthenticationFailure.of(request));
    }
    if (!principal.get().hasRole(required)) {
      throw new ApiException(ErrorCode.FORBIDDEN);
    }
  }
}
