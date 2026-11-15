/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servlet;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.Scope;
import io.argos.agent.bootstrap.TraceContext;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code javax.servlet.http.HttpServlet.service(ServletRequest, ServletResponse)} (10
 * §6.1): the servlet of a dispatch, which also counts FORWARD, INCLUDE, ERROR and ASYNC dispatches
 * ({@code dispatch_count}). Arguments are taken as {@code Object} (advice references bootstrap
 * types and own helpers only); {@link ServletHelperJavax} does the work. The enter value is the
 * transaction this call takes part in; the scope binds it to the thread of an ASYNC dispatch.
 * Mirror of {@link JakartaServletAdvice}; {@link JavaxFilterAdvice} is the filter variant.
 */
public final class JavaxServletAdvice {
  private JavaxServletAdvice() {}

  // The advice local is written here and read by exit(); Error Prone cannot see that link.
  @SuppressWarnings("UnusedVariable")
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static TraceContext enter(
      @Advice.Argument(0) Object request, @Advice.Local("argosScope") Scope scope) {
    if (Argos.skip(ServletModule.MODULE_ID)) {
      return null;
    }
    TraceContext context = ServletHelperJavax.enter(request, true);
    if (context != null) {
      scope = Argos.tracer().activate(context);
    }
    return context;
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(
      @Advice.Enter TraceContext context,
      @Advice.Argument(0) Object request,
      @Advice.Argument(1) Object response,
      @Advice.Thrown Throwable error,
      @Advice.Local("argosScope") Scope scope) {
    if (context == null) {
      return;
    }
    if (scope != null) {
      scope.close();
    }
    ServletHelperJavax.exit(context, request, response, error);
  }
}
