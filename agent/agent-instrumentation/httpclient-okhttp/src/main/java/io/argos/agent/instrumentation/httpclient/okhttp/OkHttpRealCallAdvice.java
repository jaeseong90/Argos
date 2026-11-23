/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.okhttp;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.TraceContext;
import net.bytebuddy.asm.Advice;

/**
 * Inlined at the exit of the {@code RealCall} constructor (OkHttp 3 {@code okhttp3.RealCall},
 * OkHttp 4 {@code okhttp3.internal.connection.RealCall}; exit advice only, A-7, without {@code
 * onThrowable}): {@code OkHttpClient.newCall(..)} runs on the application's thread, so the
 * transaction bound to it is attached to the new call. The interceptor reads it back from {@code
 * Interceptor.Chain.call()}, also on the dispatcher thread of an {@code enqueue}d call (10 §18
 * U-11). Outside a transaction nothing is stored. No OkHttp type appears here (the agent describes
 * advice from its own jar, where OkHttp does not exist); the helper casts.
 */
public final class OkHttpRealCallAdvice {
  private OkHttpRealCallAdvice() {}

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void exit(@Advice.This Object call) {
    if (Argos.skip(HttpClientOkHttpModule.MODULE_ID)) {
      return;
    }
    TraceContext context = Argos.tracer().current();
    if (context != null) {
      OkHttpInterceptorHelper.attach(call, context);
    }
  }
}
