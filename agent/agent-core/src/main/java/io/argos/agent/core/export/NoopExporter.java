/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.agent.core.export;

import io.argos.agent.core.trace.Dictionary;
import io.argos.agent.core.trace.ProfileRecord;
import io.argos.agent.core.trace.Transaction;
import io.argos.agent.core.transport.BatchEncoder;
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
 *       every captured profile with the sender's protobuf encoder ({@link BatchEncoder}), then
 *       drops them.
 * </ul>
 */
public final class NoopExporter implements Exporter {
  private final BatchEncoder encoder;
  private final AtomicLong exported = new AtomicLong();
  private final AtomicLong profiles = new AtomicLong();
  private final AtomicLong encodedBytes = new AtomicLong();

  /** Records and drops ({@code test.exporter=drop}). */
  public NoopExporter() {
    this(null);
  }

  /**
   * @param encodeWith dictionary of the encoder: everything is encoded before it is dropped; null
   *     to drop without encoding
   */
  NoopExporter(Dictionary encodeWith) {
    this.encoder = encodeWith == null ? null : new BatchEncoder(encodeWith);
  }

  @Override
  public void export(List<Transaction> batch) {
    if (encoder != null) {
      long bytes = 0;
      for (Transaction tx : batch) {
        bytes += encoder.encode(tx);
      }
      encodedBytes.addAndGet(bytes);
    }
    exported.addAndGet(batch.size());
  }

  @Override
  public void exportProfiles(List<ProfileRecord> batch) {
    if (encoder != null) {
      long bytes = 0;
      for (ProfileRecord profile : batch) {
        bytes += encoder.encode(profile);
      }
      encodedBytes.addAndGet(bytes);
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

  /** Bytes encoded so far (0 unless created with an encoder). */
  public long encodedBytes() {
    return encodedBytes.get();
  }

  @Override
  public String describe() {
    return encoder == null ? "drop" : "noop (encode, then drop)";
  }
}
