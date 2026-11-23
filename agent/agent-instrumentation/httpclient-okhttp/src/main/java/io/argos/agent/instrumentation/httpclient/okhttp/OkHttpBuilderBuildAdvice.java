/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.okhttp;

import io.argos.agent.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/**
 * Inlined at the entry of {@code OkHttpClient.Builder.build()} (10 §6.5): puts the agent's
 * interceptor back at the front of the builder's interceptor list when the application removed it
 * after the builder was created ({@code interceptors().clear()}); otherwise nothing changes.
 */
public final class OkHttpBuilderBuildAdvice {
  private OkHttpBuilderBuildAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(@Advice.This Object builder) {
    if (Argos.skip(HttpClientOkHttpModule.MODULE_ID)) {
      return;
    }
    OkHttpInterceptorHelper.install(builder);
  }
}
