/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servlet;

import io.argos.agent.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into the exit of the container response facade's {@code getOutputStream()} and {@code
 * getWriter()} (ADR-0010 C-127; Tomcat {@code ResponseFacade}, Jetty and Undertow responses of
 * {@link ContainerResponseInstrumentation}): the returned stream is linked to the facade, so that
 * its {@code flush()} ends a streaming response (10 §6.7) also when the stream was opened before
 * {@code startAsync} (Spring's {@code StreamingResponseBody}). The returned stream is only read,
 * never replaced.
 */
public final class ResponseStreamAdvice {
  private ResponseStreamAdvice() {}

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(@Advice.This Object response, @Advice.Return Object stream) {
    if (Argos.skip(ServletModule.MODULE_ID)) {
      return;
    }
    ServletHelperCommon.streamOpened(response, stream);
  }
}
