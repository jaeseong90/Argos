/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.jdk11;

import io.argos.agent.bootstrap.Argos;
import io.argos.agent.bootstrap.CallDepth;
import java.net.http.HttpRequest;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Inlined into the public {@code send} and {@code sendAsync} methods of {@code
 * jdk.internal.net.http.HttpClientImpl} (10 §6.5). For the outermost HTTP client call of the thread
 * ({@code CallDepth.HTTP_CLIENT}; {@code sendAsync(2)} calls {@code sendAsync(3)}) inside a
 * transaction, enter replaces the request argument with {@link Jdk11RequestHelper} (the
 * allow-listed {@code @Argument(0, readOnly = false)}, 10 §5.3 A-5: the same request plus the
 * propagation headers, nothing else changes) and opens the HTTP step; exit ends a {@code send} step
 * with the response or exception and hands a {@code sendAsync} step to the exchange's completion
 * (U-10). The returned future and every exception reach the application unchanged.
 */
public final class HttpClientSendAdvice {
  private HttpClientSendAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static Object enter(
      @Advice.Argument(value = 0, readOnly = false) HttpRequest request,
      @Advice.Origin("#m") String method,
      @Advice.Local("argosCall") Jdk11RequestHelper call) {
    if (Argos.skip(HttpClientJdk11Module.MODULE_ID)) {
      return null;
    }
    // Start before CallDepth.enter: if anything here throws, enter returns null and exit leaves
    // the depth alone.
    if (CallDepth.depth(CallDepth.HTTP_CLIENT) == 0) {
      call = Jdk11RequestHelper.start(request, "sendAsync".equals(method));
      if (call != null) {
        request = call;
      }
    }
    CallDepth.enter(CallDepth.HTTP_CLIENT);
    return true;
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(
      @Advice.Enter Object entered,
      @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object result,
      @Advice.Thrown Throwable error,
      @Advice.Local("argosCall") Jdk11RequestHelper call) {
    if (entered == null) {
      return;
    }
    CallDepth.exit(CallDepth.HTTP_CLIENT);
    if (call != null) {
      call.end(result, error);
    }
  }
}
