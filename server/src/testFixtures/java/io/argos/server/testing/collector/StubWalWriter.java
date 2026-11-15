/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.testing.collector;

import com.google.protobuf.ByteString;
import io.argos.server.collector.api.DedupKey;
import io.argos.server.wal.api.WalFullException;
import io.argos.server.wal.api.WalIdentity;
import io.argos.server.wal.api.WalPosition;
import io.argos.server.wal.api.WalRecordType;
import io.argos.server.wal.api.WalWriteException;
import io.argos.server.wal.api.WalWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A {@link WalWriter} in memory for the collector's tests (lane collector of WO-21): records every
 * data record as appended, can play a full WAL ({@link WalFullException}), an undurable write
 * ({@link WalWriteException}), a slow fsync and a usage ratio. The durable WAL itself is lane
 * durable-ingest's; tests of the collector's protocol behaviour need only this contract.
 */
public final class StubWalWriter implements WalWriter {

  /** One appended data record. */
  public record Appended(
      WalRecordType type,
      int instanceId,
      long agentEpoch,
      long seq,
      long receivedMs,
      long tsCorrectionMs,
      ByteString payload,
      WalPosition position) {

    /** The deduplication key of the record (what {@code WalRecovery} restores). */
    public DedupKey key() {
      return new DedupKey(instanceId, agentEpoch, type, seq);
    }
  }

  private final List<Appended> appended = new ArrayList<>();
  private volatile boolean full;
  private volatile boolean failing;
  private volatile double usage;
  private volatile long fsyncDelayMs;
  private long offset;

  @Override
  public WalPosition append(
      WalRecordType type,
      int instanceId,
      long agentEpoch,
      long seq,
      long receivedMs,
      long tsCorrectionMs,
      byte[] payload)
      throws WalFullException, WalWriteException {
    if (!type.isData()) {
      throw new IllegalArgumentException("Not a data type: " + type);
    }
    long delay = fsyncDelayMs;
    if (delay > 0) {
      try {
        TimeUnit.MILLISECONDS.sleep(delay);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new WalWriteException("Interrupted while waiting for the fsync", e);
      }
    }
    if (full) {
      throw new WalFullException(maxBytes(), maxBytes());
    }
    if (failing) {
      throw new WalWriteException("Simulated undurable write", null);
    }
    synchronized (this) {
      WalPosition position = new WalPosition(1, offset, payload.length + 45);
      offset += payload.length + 45L;
      appended.add(
          new Appended(
              type,
              instanceId,
              agentEpoch,
              seq,
              receivedMs,
              tsCorrectionMs,
              ByteString.copyFrom(payload),
              position));
      return position;
    }
  }

  @Override
  public WalPosition appendBlockIntent(byte[] payload) {
    throw new UnsupportedOperationException("Block records are the batch writer's");
  }

  @Override
  public WalPosition appendBlockCommit(byte[] payload) {
    throw new UnsupportedOperationException("Block records are the batch writer's");
  }

  @Override
  public WalIdentity identity() {
    return new WalIdentity("collector-test", 1);
  }

  @Override
  public long bytesInUse() {
    return (long) (usage * maxBytes());
  }

  @Override
  public long maxBytes() {
    return 10L * 1024 * 1024 * 1024;
  }

  /** Every data record appended so far, in order. */
  public synchronized List<Appended> appended() {
    return List.copyOf(appended);
  }

  /** Records of {@code type}. */
  public synchronized List<Appended> appended(WalRecordType type) {
    return appended.stream().filter(a -> a.type() == type).toList();
  }

  /** Plays a full WAL (DSN-11-09). */
  public void full(boolean value) {
    this.full = value;
  }

  /** Plays undurable writes. */
  public void failing(boolean value) {
    this.failing = value;
  }

  /** Sets {@link #usageRatio()}. */
  public void usage(double ratio) {
    this.usage = ratio;
  }

  /** Delays every append by {@code millis} (a slow fsync). */
  public void fsyncDelayMs(long millis) {
    this.fsyncDelayMs = millis;
  }
}
