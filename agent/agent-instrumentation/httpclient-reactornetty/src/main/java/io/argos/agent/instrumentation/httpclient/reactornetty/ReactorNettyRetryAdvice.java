/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.reactornetty;

import io.argos.agent.bootstrap.Argos;
import java.net.SocketAddress;
import net.bytebuddy.asm.Advice;

/**
 * Inlined at the exit of {@code HttpClientHandler.test(Throwable)}, the retry filter ({@code
 * Retry.indefinitely().filter(handler)}) that sees every error of the connection {@code Mono}
 * before it moves on: {@code true} means the request is sent again (a followed redirect, one retry
 * of a reset pooled connection) and the step stays open; {@code false} means the error reaches the
 * application right after this method returns, so the step closes with it ({@link
 * ReactorNettyHelper#failed}). Connection failures (refused, connect timeout, unknown host) never
 * reach {@code requestWithBody} and are seen only here.
 */
public final class ReactorNettyRetryAdvice {
  private ReactorNettyRetryAdvice() {}

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(
      @Advice.This SocketAddress handler,
      @Advice.Argument(0) Throwable failure,
      @Advice.Return boolean retry,
      @Advice.FieldValue("toURI") Object uri,
      @Advice.FieldValue("method") Object method,
      @Advice.Thrown Throwable thrown) {
    if (Argos.skip(HttpClientReactorNettyModule.MODULE_ID)) {
      return;
    }
    if (thrown == null && !retry) {
      ReactorNettyHelper.failed(handler, failure, uri, method);
    }
  }
}
