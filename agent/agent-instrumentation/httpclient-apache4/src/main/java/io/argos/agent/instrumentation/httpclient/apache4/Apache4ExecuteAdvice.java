/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.apache4;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.CallDepth;
import io.argos.agent.bootstrap.TraceContext;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code doExecute(HttpHost, HttpRequest, HttpContext)} of the HttpClient 4
 * implementations (10 §6.5). Enter, for the outermost client call of the thread inside a
 * transaction: propagation headers on the request and the HTTP step ({@link Apache4Helper#start}).
 * Exit: status or exception, failure class ({@link Apache4Helper#end}). The application's request
 * object is only given headers through {@code setHeader}; its result and exception are never
 * touched. Library types are {@code Object} here: the agent describes advice from its own jar,
 * where no HttpClient class exists (the helper, injected into the application's loader, casts).
 */
public final class Apache4ExecuteAdvice {
  private Apache4ExecuteAdvice() {}

  // The advice locals are written here and read by exit(); Error Prone cannot see that link.
  @SuppressWarnings("UnusedVariable")
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static Object enter(
      @Advice.Argument(0) Object target,
      @Advice.Argument(1) Object request,
      @Advice.Local("argosContext") TraceContext context,
      @Advice.Local("argosCall") long[] call) {
    if (Argos.skip(HttpClientApache4Module.MODULE_ID)) {
      return null;
    }
    if (CallDepth.enter(CallDepth.HTTP_CLIENT) == 1) {
      context = Argos.tracer().current();
      if (context != null) {
        call = Apache4Helper.start(context, target, request);
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
      Apache4Helper.end(context, call, response, error);
    }
  }
}
