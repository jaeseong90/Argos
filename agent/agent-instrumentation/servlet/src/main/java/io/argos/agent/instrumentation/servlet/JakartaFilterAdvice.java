/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servlet;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.Scope;
import io.argos.agent.bootstrap.TraceContext;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code jakarta.servlet.Filter.doFilter(ServletRequest, ServletResponse,
 * FilterChain)} implementations (10 §6.1): a filter call joins or begins the transaction like a
 * servlet call but never counts a dispatch. Mirror of {@link JavaxFilterAdvice}; see {@link
 * JakartaServletAdvice}.
 */
public final class JakartaFilterAdvice {
  private JakartaFilterAdvice() {}

  // The advice local is written here and read by exit(); Error Prone cannot see that link.
  @SuppressWarnings("UnusedVariable")
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static TraceContext enter(
      @Advice.Argument(0) Object request, @Advice.Local("argosScope") Scope scope) {
    if (Argos.skip(ServletModule.MODULE_ID)) {
      return null;
    }
    TraceContext context = ServletHelperJakarta.enter(request, false);
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
    ServletHelperJakarta.exit(context, request, response, error);
  }
}
