/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.reactornetty;

import io.argos.agent.bootstrap.Argos;
import net.bytebuddy.asm.Advice;

/**
 * Inlined at the entry of {@code HttpClientConnect$HttpObserver.onStateChange(Connection, State)}.
 * {@code RESPONSE_RECEIVED} is signalled once, for the final response (a followed redirect is an
 * error of the connection {@code Mono} instead), and the method then completes the {@code Mono}
 * with the response, which can resume {@code block()} at once; so the step is closed at the entry
 * ({@link ReactorNettyHelper#responseReceived}), before the transaction can end.
 */
public final class ReactorNettyResponseAdvice {
  private ReactorNettyResponseAdvice() {}

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void enter(
      @Advice.Argument(0) Object connection,
      @Advice.Argument(1) Object state,
      @Advice.FieldValue("handler") Object handler) {
    if (Argos.skip(HttpClientReactorNettyModule.MODULE_ID)) {
      return;
    }
    ReactorNettyHelper.responseReceived(handler, connection, state);
  }
}
