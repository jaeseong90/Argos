/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.export;

import io.argos.agent.core.trace.Transaction;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drops every transaction after it went through the whole recording path (P-2 baseline: "no-op 익스포터
 * 기준", 01 §7.3). Counts what it dropped.
 */
public final class NoopExporter implements Exporter {
  private final AtomicLong exported = new AtomicLong();

  @Override
  public void export(List<Transaction> batch) {
    exported.addAndGet(batch.size());
  }

  @Override
  public void flush() {}

  @Override
  public void close() {}

  /** Transactions received so far. */
  public long exported() {
    return exported.get();
  }

  @Override
  public String describe() {
    return "noop";
  }
}
