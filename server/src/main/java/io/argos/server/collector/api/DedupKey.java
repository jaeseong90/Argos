/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.collector.api;

import io.argos.proto.v1.BatchHeader;
import io.argos.server.wal.api.WalRecordType;
import java.util.Objects;

/**
 * The batch deduplication key (C-3, DSN-02-11, DSN-11-02): instance, agent epoch, batch kind and
 * seq. The seq counts per kind from 1 for the life of the agent process; the epoch, random per
 * process, separates the seqs of a restarted agent.
 *
 * @param instanceId the session's instance ({@code argos-instance-id}; the batch header carries the
 *     same value)
 * @param agentEpoch {@code BatchHeader.agent_epoch} (unsigned 64-bit, held as {@code long})
 * @param type the batch kind: a data type of the WAL
 * @param seq {@code BatchHeader.seq} (unsigned 64-bit, held as {@code long})
 */
public record DedupKey(int instanceId, long agentEpoch, WalRecordType type, long seq) {

  /** Only the four WAL data kinds are deduplicated (active snapshots are not, 12 §3.2). */
  public DedupKey {
    Objects.requireNonNull(type, "type");
    if (!type.isData()) {
      throw new IllegalArgumentException("Not a batch kind: " + type);
    }
  }

  /**
   * The key of a batch of {@code type} with {@code header}, received in {@code instanceId}'s
   * session.
   */
  public static DedupKey of(int instanceId, WalRecordType type, BatchHeader header) {
    return new DedupKey(instanceId, header.getAgentEpoch(), type, header.getSeq());
  }
}
