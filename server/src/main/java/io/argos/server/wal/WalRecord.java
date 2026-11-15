/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.wal;

import io.argos.server.wal.api.WalPosition;
import io.argos.server.wal.api.WalRecordType;
import java.util.Objects;

/**
 * One record read back from the WAL ({@link WalFormat}).
 *
 * @param position where the record is
 * @param type record type
 * @param instanceId {@code instance_id} (0 for block records)
 * @param epoch agent epoch of a data record (0 for block records)
 * @param seq batch seq of a data record (0 for block records)
 * @param receivedMs server receive time of a data record; write time of a block record
 * @param tsCorrectionMs the ADR-0010 C-129 correction decided at receipt (0 for block records)
 * @param payload the payload bytes (owned by this record)
 */
// The payload array is owned by the record and never compared: records are not used as values.
@SuppressWarnings("ArrayRecordComponent")
public record WalRecord(
    WalPosition position,
    WalRecordType type,
    int instanceId,
    long epoch,
    long seq,
    long receivedMs,
    long tsCorrectionMs,
    byte[] payload) {

  /** Validates the references. */
  public WalRecord {
    Objects.requireNonNull(position, "position");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(payload, "payload");
  }

  @Override
  public String toString() {
    // Never the payload: it carries collected data.
    return "WalRecord[" + type + " at " + position + ", instance " + instanceId + "]";
  }
}
