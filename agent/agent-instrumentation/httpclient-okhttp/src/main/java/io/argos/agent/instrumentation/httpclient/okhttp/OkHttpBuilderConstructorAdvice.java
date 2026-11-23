/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.okhttp;

import io.argos.agent.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/**
 * Inlined at the exit of every {@code OkHttpClient.Builder} constructor (10 §6.5; exit advice only,
 * A-7, and without {@code onThrowable}: ByteBuddy cannot catch inside a constructor): the agent's
 * interceptor goes to the front of the builder's modifiable interceptor list unless it is there
 * already (the copy constructor of {@code client.newBuilder()} copies it). This also covers {@code
 * new OkHttpClient()}, which never calls {@code build()}.
 */
public final class OkHttpBuilderConstructorAdvice {
  private OkHttpBuilderConstructorAdvice() {}

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void exit(@Advice.This Object builder) {
    if (Argos.skip(HttpClientOkHttpModule.MODULE_ID)) {
      return;
    }
    OkHttpInterceptorHelper.install(builder);
  }
}
