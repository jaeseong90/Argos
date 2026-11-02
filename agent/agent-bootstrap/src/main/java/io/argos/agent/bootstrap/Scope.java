/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

/**
 * Result of {@link Tracer#activate(TraceContext)}: {@link #close()} restores the context that was
 * current before. Not {@code AutoCloseable} on purpose: advice may not use try-with-resources (10
 * §5.3 A-3).
 */
public interface Scope {
  /** Does nothing; returned when there is nothing to restore. */
  Scope NOOP =
      new Scope() {
        @Override
        public void close() {}
      };

  void close();
}
