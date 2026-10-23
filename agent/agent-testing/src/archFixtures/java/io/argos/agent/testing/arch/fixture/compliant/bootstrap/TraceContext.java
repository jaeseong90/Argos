/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.testing.arch.fixture.compliant.bootstrap;

import java.lang.ref.WeakReference;

/** Transaction handle with bootstrap, primitive and String fields only (10 §3, F-A36, T-AG-004). */
public final class TraceContext {
  public final long txId;
  public final int kind;
  public final long startNanos;
  public volatile String serviceName;
  public volatile boolean ended;
  public final WeakReference<Thread> thread;
  public final long[] spanIds;
  public volatile Integer status;
  public volatile TraceContext parent;

  /** The core Transaction, opaque to the bootstrap (10 §3). */
  public volatile Object coreRef;

  public TraceContext(long txId, int kind, long startNanos, Thread thread) {
    this.txId = txId;
    this.kind = kind;
    this.startNanos = startNanos;
    this.thread = new WeakReference<>(thread);
    this.spanIds = new long[2];
  }
}
