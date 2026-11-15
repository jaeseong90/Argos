/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.export;

import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.ProfileRecord;
import io.argos.agent.core.trace.Transaction;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drops everything after it went through the whole recording path (P-2 baseline: "no-op 익스포터 기준",
 * 01 §7.3, 18 §5.4). Counts what it dropped. Two flavours:
 *
 * <ul>
 *   <li>{@code test.exporter=drop} (ADR-0009 C-80, the WO-02 P-2 setting): records and drops, no
 *       encoding;
 *   <li>{@code test.exporter=noop} (18 §5.4 "인코딩까지 하고 버림"): encodes every transaction record and
 *       every captured profile with the agent's encoder (the JSONL encoding until the gRPC encoder
 *       of WO-11 replaces it), then drops them.
 * </ul>
 */
public final class NoopExporter implements Exporter {
  private final Dictionary encodeWith;
  private final AtomicLong exported = new AtomicLong();
  private final AtomicLong profiles = new AtomicLong();
  private final AtomicLong encodedChars = new AtomicLong();

  /** Records and drops ({@code test.exporter=drop}). */
  public NoopExporter() {
    this(null);
  }

  /**
   * @param encodeWith dictionary of the encoder: everything is encoded before it is dropped; null
   *     to drop without encoding
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
  public void exportProfiles(List<ProfileRecord> batch) {
    if (encodeWith != null) {
      long chars = 0;
      for (ProfileRecord profile : batch) {
        StringBuilder text = new StringBuilder(256);
        JsonWriter json = new JsonWriter(text).beginObject();
        json.field("tx_id", Long.toUnsignedString(profile.txId()));
        JsonlFileExporter.steps(json, profile.build(), encodeWith);
        json.endObject();
        chars += text.length();
      }
      encodedChars.addAndGet(chars);
    }
    profiles.addAndGet(batch.size());
  }

  @Override
  public void flush() {}

  @Override
  public void close() {}

  /** Transactions received so far. */
  public long exported() {
    return exported.get();
  }

  /** Profiles received so far. */
  public long profiles() {
    return profiles.get();
  }

  /** Characters encoded so far (0 unless created with an encoder). */
  public long encodedChars() {
    return encodedChars.get();
  }

  @Override
  public String describe() {
    return encodeWith == null ? "drop" : "noop (encode, then drop)";
  }
}
