/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.sample.load;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Sends one request of the mix; tests replace it with an in-memory fake. */
interface Transport extends AutoCloseable {

  /**
   * Sends {@code GET <target><pathAndQuery>} without waiting. The future completes with the HTTP
   * status, or exceptionally with the transport failure (see {@link Outcome#ofFailure}).
   */
  CompletableFuture<Integer> get(String pathAndQuery, Duration timeout);

  @Override
  void close();
}
