/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.springwebmvc;

import io.argos.agent.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into the enter of {@code HandlerAdapter.handle(javax HttpServletRequest, ...)} of
 * Spring's adapters (Framework 5.3, 10 §6.2): names the service from the handler pattern.
 */
public final class JavaxHandlerAdapterAdvice {
  private JavaxHandlerAdapterAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.Argument(0) Object request) {
    if (Argos.skip(SpringWebMvcModule.MODULE_ID)) {
      return;
    }
    SpringWebMvcHelperJavax.nameService(request);
  }
}
