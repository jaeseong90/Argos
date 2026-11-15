/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servletasync;

import io.argos.agent.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into the exit of Tomcat 9's {@code Request.startAsync(javax.servlet.ServletRequest,
 * javax.servlet.ServletResponse)} (10 §6.7): {@link AsyncHelperJavax#started} puts the anchored
 * transaction into async mode. Nothing happens when {@code startAsync} threw (not async capable,
 * already started). Mirror of {@link JakartaStartAsyncAdvice}.
 */
public final class JavaxStartAsyncAdvice {
  private JavaxStartAsyncAdvice() {}

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(
      @Advice.This Object request,
      @Advice.Argument(1) Object response,
      @Advice.Thrown Throwable error) {
    if (Argos.skip(ServletAsyncModule.MODULE_ID)) {
      return;
    }
    if (error == null) {
      AsyncHelperJavax.started(request, response);
    }
  }
}
