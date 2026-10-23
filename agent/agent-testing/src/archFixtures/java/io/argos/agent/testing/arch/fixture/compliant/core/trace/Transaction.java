/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant.core.trace;

import io.argos.agent.testing.arch.fixture.compliant.bootstrap.TraceContext;

/** Core side of a transaction (TraceContext.coreRef, 10 §9.1). */
public final class Transaction {
  final TraceContext context;
  final StepBuffer steps = new StepBuffer();
  String path;
  String[] headers;
  long cpuNanos = -1;

  public Transaction(TraceContext context, String path) {
    this.context = context;
    this.path = path;
  }

  public TraceContext context() {
    return context;
  }
}
