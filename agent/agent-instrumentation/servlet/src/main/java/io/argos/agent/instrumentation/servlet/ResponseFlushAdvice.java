/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.servlet;

import io.argos.agent.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into the exit of {@code flushBuffer()} / {@code flush()} of the container's response
 * classes, matched by name (ADR-0010 C-127; Tomcat: {@code ResponseFacade}, {@code
 * CoyoteOutputStream}, {@code CoyoteWriter}): the first flush of an async response ends a streaming
 * transaction (10 §6.7, F-A34). Namespace-independent; {@link ServletHelperCommon#flushed} does the
 * work and returns at once while no async response is mapped.
 */
public final class ResponseFlushAdvice {
  private ResponseFlushAdvice() {}

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(@Advice.This Object target) {
    if (Argos.skip(ServletModule.MODULE_ID)) {
      return;
    }
    ServletHelperCommon.flushed(target);
  }
}
