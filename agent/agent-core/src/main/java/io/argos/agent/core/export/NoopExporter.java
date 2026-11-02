/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.export;

import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.Transaction;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drops every transaction after it went through the whole recording path (P-2 baseline: "no-op 익스포터
 * 기준", 01 §7.3). Counts what it dropped.
 *
 * <p>Two flavours:
 *
 * <ul>
 *   <li>{@code exporter=noop} (WO-02 PoC key, the P-2 measurements): records and drops, no
 *       encoding;
 *   <li>{@code test.exporter=noop} (18 §5.4 "인코딩까지 하고 버림"): encodes every transaction with the
 *       agent's encoder (the JSONL line until the gRPC encoder of WO-11 replaces it), then drops
 *       it.
 * </ul>
 */
public final class NoopExporter implements Exporter {
  private final Dictionary encodeWith;
  private final AtomicLong exported = new AtomicLong();
  private final AtomicLong encodedChars = new AtomicLong();

  /** Records and drops ({@code exporter=noop}). */
  public NoopExporter() {
    this(null);
  }

  /**
   * @param encodeWith dictionary of the encoder: every transaction is encoded before it is dropped;
   *     null to drop without encoding
   */
  NoopExporter(Dictionary encodeWith) {
    this.encodeWith = encodeWith;
  }

  @Override
  public void export(List<Transaction> batch) {
    if (encodeWith != null) {
      long chars = 0;
      for (Transaction tx : batch) {
        chars += JsonlFileExporter.encode(tx, encodeWith).length();
      }
      encodedChars.addAndGet(chars);
    }
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

  /** Characters encoded so far (0 unless created with an encoder). */
  public long encodedChars() {
    return encodedChars.get();
  }

  @Override
  public String describe() {
    return encodeWith == null ? "noop" : "noop (encode, then drop)";
  }
}
