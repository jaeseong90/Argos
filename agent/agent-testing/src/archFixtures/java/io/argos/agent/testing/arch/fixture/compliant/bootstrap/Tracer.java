/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant.bootstrap;

/** The API that advice and helper code calls (10 §3). */
public interface Tracer {
  Tracer NOOP =
      new Tracer() {
        @Override
        public TraceContext begin(String entryName, long startNanos) {
          return null;
        }

        @Override
        public void end(TraceContext context, Throwable error, long endNanos) {}
      };

  TraceContext begin(String entryName, long startNanos);

  void end(TraceContext context, Throwable error, long endNanos);
}
