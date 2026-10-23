/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant.core.trace;

/** Aggregated steps after the step limit (10 §9.1). */
public final class SummaryStep {
  public int type;
  public long nameId;
  public long count;
  public long sumNanos;
  public long maxNanos;
}
