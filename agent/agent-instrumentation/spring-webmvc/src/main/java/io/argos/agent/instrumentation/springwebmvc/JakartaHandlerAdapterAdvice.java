/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.springwebmvc;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.TraceContext;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code HandlerAdapter.handle(jakarta HttpServletRequest, HttpServletResponse,
 * Object)} of Spring's adapters (Framework 6.x, 7.x, 10 §6.2). The enter names the service from the
 * handler pattern, or from a {@code HandlerMethod} without one; the exit decides by the enter value
 * (C-24): only after a handler-method name does it read the pattern again.
 */
public final class JakartaHandlerAdapterAdvice {
  private JakartaHandlerAdapterAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static TraceContext enter(
      @Advice.Argument(0) Object request, @Advice.Argument(2) Object handler) {
    if (Argos.skip(SpringWebMvcModule.MODULE_ID)) {
      return null;
    }
    return SpringWebMvcHelperJakarta.nameService(request, handler);
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(@Advice.Enter TraceContext context, @Advice.Argument(0) Object request) {
    if (context == null) {
      return;
    }
    SpringWebMvcHelperJakarta.retry(context, request);
  }
}
