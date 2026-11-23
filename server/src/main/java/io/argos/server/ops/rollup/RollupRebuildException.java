/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.ops.rollup;

/**
 * A rollup rebuild that could not finish: a ClickHouse statement failed or a table cannot be
 * rebuilt with the C-59 procedure. The message is one operator-facing line without secret values.
 * Tables rebuilt before the failure keep their new partitions (each swap is atomic); the failed
 * table keeps its old partition.
 */
public class RollupRebuildException extends Exception {

  private static final long serialVersionUID = 1L;

  /** A failure caused by another exception (ClickHouse error). */
  public RollupRebuildException(String message, Throwable cause) {
    super(message, cause);
  }

  /** A refusal without an underlying exception. */
  public RollupRebuildException(String message) {
    super(message);
  }
}
