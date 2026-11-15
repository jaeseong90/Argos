/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servletasync;

import io.argos.agent.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into the exit of Tomcat 10.1/11's {@code
 * Request.startAsync(jakarta.servlet.ServletRequest, jakarta.servlet.ServletResponse)} (10 §6.7):
 * {@link AsyncHelperJakarta#started} puts the anchored transaction into async mode. Nothing happens
 * when {@code startAsync} threw. Mirror of {@link JavaxStartAsyncAdvice}.
 */
public final class JakartaStartAsyncAdvice {
  private JakartaStartAsyncAdvice() {}

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(
      @Advice.This Object request,
      @Advice.Argument(1) Object response,
      @Advice.Thrown Throwable error) {
    if (Argos.skip(ServletAsyncModule.MODULE_ID)) {
      return;
    }
    if (error == null) {
      AsyncHelperJakarta.started(request, response);
    }
  }
}
