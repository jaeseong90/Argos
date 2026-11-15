/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.wal;

import io.argos.server.wal.api.WalPosition;
import io.argos.server.wal.api.WalRecordType;
import java.util.Collection;

/**
 * What the ClickHouse side has made durable, as {@link WalGc} needs it (12 §3.4 "회수"): a segment is
 * deleted only when every data record in it has all its rows in ClickHouse (covered by a {@code
 * BLOCK_COMMIT}, or known to have no rows for a table) and no block intent in it waits for its
 * commit. Implemented by the ingest side.
 */
public interface WalCoverage {

  /**
   * The packed position ({@link WalPosition#packed()}) up to which every row produced by data
   * records of {@code dataType} is committed in every table that type feeds; -1 when nothing is.
   */
  long durableThroughPacked(WalRecordType dataType);

  /** Positions of the block intents whose insert has not been committed yet. */
  Collection<WalPosition> pendingIntents();
}
