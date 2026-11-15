/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.bootstrap;

import org.springframework.boot.SpringBootExceptionReporter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Keeps an aborted start to the single ERROR line that {@link StartupSequence} logged: for a {@link
 * StartupAbortedException} anywhere in the cause chain it reports the failure as handled, so Spring
 * Boot neither runs its failure analyzers nor logs "Application run failed" with the stack trace
 * (which could repeat exception messages of drivers). Registered in {@code
 * META-INF/spring.factories} ahead of Boot's own reporter. Every other failure is left to Spring
 * Boot.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class StartupFailureReporter implements SpringBootExceptionReporter {

  @Override
  public boolean reportException(Throwable failure) {
    return find(failure) != null;
  }

  /** The {@link StartupAbortedException} in the cause chain of {@code failure}, or null. */
  static StartupAbortedException find(Throwable failure) {
    Throwable current = failure;
    for (int depth = 0; current != null && depth < 32; depth++) {
      if (current instanceof StartupAbortedException aborted) {
        return aborted;
      }
      current = current.getCause();
    }
    return null;
  }
}
