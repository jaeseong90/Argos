/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.bootstrap;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

/**
 * Holder of the {@link TraceContext#markEnded()} and {@link TraceContext#addFlags(int)} updaters,
 * outside TraceContext so that its fields keep the T-AG-004 types (primitives, strings, bootstrap
 * types).
 */
final class EndState {
  static final AtomicIntegerFieldUpdater<TraceContext> UPDATER =
      AtomicIntegerFieldUpdater.newUpdater(TraceContext.class, "endState");

  static final AtomicIntegerFieldUpdater<TraceContext> FLAGS =
      AtomicIntegerFieldUpdater.newUpdater(TraceContext.class, "flags");

  /** {@link TraceContext#addExternalCall(long)} (WO-14). */
  static final AtomicIntegerFieldUpdater<TraceContext> EXT_COUNT =
      AtomicIntegerFieldUpdater.newUpdater(TraceContext.class, "extCount");

  /** {@link TraceContext#addExternalCall(long)} (WO-14). */
  static final AtomicLongFieldUpdater<TraceContext> EXT_NANOS =
      AtomicLongFieldUpdater.newUpdater(TraceContext.class, "extNanos");

  private EndState() {}
}
