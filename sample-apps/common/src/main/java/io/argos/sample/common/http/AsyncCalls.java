/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.common.http;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The app-side wait of the asynchronous client modes ({@code mode=async|future|subscribe}): the
 * request thread of the sample app waits for the client's result so that the endpoint can answer
 * with it. The wait belongs to the app, not to the agent.
 */
public final class AsyncCalls {

  private AsyncCalls() {}

  /**
   * Waits at most {@code waitMs} for {@code future} and returns its value. The failure of the call
   * is rethrown as it is (the cause of the {@link ExecutionException}), so that {@link
   * OutboundOutcome#classify} sees the client's own exception; a wait that runs out cancels the
   * future and throws {@link TimeoutException}.
   */
  public static <T> T await(Future<T> future, long waitMs) throws Exception {
    try {
      return future.get(waitMs, TimeUnit.MILLISECONDS);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception) {
        throw (Exception) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw e;
    } catch (TimeoutException e) {
      future.cancel(true);
      throw e;
    }
  }
}
