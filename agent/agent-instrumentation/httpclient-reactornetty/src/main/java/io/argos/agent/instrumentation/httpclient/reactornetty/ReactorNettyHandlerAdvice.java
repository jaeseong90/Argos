/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.reactornetty;

import io.argos.agent.bootstrap.Argos;
import java.net.SocketAddress;
import net.bytebuddy.asm.Advice;

/**
 * Inlined at the exit of the {@code HttpClientConnect$HttpClientHandler} constructor (exit advice
 * only, A-7, without {@code onThrowable}). {@code MonoHttpConnect.subscribe} creates the handler on
 * the subscribing thread, so the transaction bound to that thread (the servlet request calling
 * {@code block()}, {@code toFuture()} or {@code subscribe()}) and the call's start time are
 * attached to the handler ({@link ReactorNettyHelper#attach}). The handler class is
 * package-private; it is typed as its public super class {@code SocketAddress}.
 */
public final class ReactorNettyHandlerAdvice {
  private ReactorNettyHandlerAdvice() {}

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void exit(@Advice.This SocketAddress handler) {
    if (Argos.skip(HttpClientReactorNettyModule.MODULE_ID)) {
      return;
    }
    ReactorNettyHelper.attach(handler);
  }
}
