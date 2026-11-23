/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.reactornetty;

import io.argos.agent.bootstrap.Argos;
import java.net.SocketAddress;
import net.bytebuddy.asm.Advice;

/**
 * Inlined at the exit of {@code HttpClientHandler.requestWithBody(HttpClientOperations)} (event
 * loop). At this point the method has copied the client's default headers into the request (a copy
 * that replaces every header, so the propagation headers cannot be set at the entry) and the
 * request is not written yet (the returned publisher is subscribed afterwards). {@link
 * ReactorNettyHelper#request} sets the headers for the handler's current target ({@code toURI},
 * which a redirect changes) and opens the step at the first request.
 */
public final class ReactorNettyRequestAdvice {
  private ReactorNettyRequestAdvice() {}

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(
      @Advice.This SocketAddress handler,
      @Advice.Argument(0) Object operations,
      @Advice.FieldValue("toURI") Object uri,
      @Advice.FieldValue("method") Object method,
      @Advice.Thrown Throwable error) {
    if (Argos.skip(HttpClientReactorNettyModule.MODULE_ID)) {
      return;
    }
    if (error == null) {
      ReactorNettyHelper.request(handler, operations, uri, method);
    }
  }
}
