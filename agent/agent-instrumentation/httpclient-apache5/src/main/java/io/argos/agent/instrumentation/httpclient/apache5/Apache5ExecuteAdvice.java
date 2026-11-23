/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.apache5;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.CallDepth;
import io.argos.agent.bootstrap.TraceContext;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code doExecute(HttpHost, ClassicHttpRequest, HttpContext)} of the classic
 * HttpClient 5 implementations (10 §6.5). Enter, for the outermost client call of the thread inside
 * a transaction: propagation headers on the request and the HTTP step ({@link
 * Apache5Helper#start}). Exit: status or exception, failure class ({@link Apache5Helper#end}). The
 * application's request object is only given headers through {@code setHeader}; its result and
 * exception are never touched.
 */
public final class Apache5ExecuteAdvice {
  private Apache5ExecuteAdvice() {}

  // The advice locals are written here and read by exit(); Error Prone cannot see that link.
  @SuppressWarnings("UnusedVariable")
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static Object enter(
      @Advice.Argument(0) Object target,
      @Advice.Argument(1) Object request,
      @Advice.Local("argosContext") TraceContext context,
      @Advice.Local("argosCall") long[] call) {
    if (Argos.skip(HttpClientApache5Module.MODULE_ID)) {
      return null;
    }
    if (CallDepth.enter(CallDepth.HTTP_CLIENT) == 1) {
      context = Argos.tracer().current();
      if (context != null) {
        call = Apache5Helper.start(context, target, request);
      }
    }
    return true;
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(
      @Advice.Enter Object entered,
      @Advice.Return Object response,
      @Advice.Thrown Throwable error,
      @Advice.Local("argosContext") TraceContext context,
      @Advice.Local("argosCall") long[] call) {
    if (entered == null) {
      return;
    }
    CallDepth.exit(CallDepth.HTTP_CLIENT);
    if (call != null) {
      Apache5Helper.end(context, call, response, error);
    }
  }
}
