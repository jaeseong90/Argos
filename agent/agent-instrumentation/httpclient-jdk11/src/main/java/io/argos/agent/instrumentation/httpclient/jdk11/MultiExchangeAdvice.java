/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.instrumentation.httpclient.jdk11;

import io.argos.agent.bootstrap.Argos;
import java.net.http.HttpRequest;
import java.util.concurrent.CompletableFuture;
import net.bytebuddy.asm.Advice;

/**
 * Inlined into {@code jdk.internal.net.http.MultiExchange.responseAsync(Executor)} (U-10): when the
 * exchange was created for a request that {@link HttpClientSendAdvice} wrapped (the {@code
 * userRequest} field of JDK 11 to 25), the exchange's internal future goes to the wrapper, which
 * registers itself on it as its first dependent ({@code sendAsync} only, see {@link
 * Jdk11RequestHelper}). Nothing is read from that future; the value returned to the JDK is
 * unchanged.
 */
public final class MultiExchangeAdvice {
  private MultiExchangeAdvice() {}

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void exit(
      @Advice.FieldValue("userRequest") HttpRequest request,
      @Advice.Return CompletableFuture<?> exchange) {
    if (Argos.skip(HttpClientJdk11Module.MODULE_ID)) {
      return;
    }
    if (request instanceof Jdk11RequestHelper) {
      ((Jdk11RequestHelper) request).exchangeStarted(exchange);
    }
  }
}
