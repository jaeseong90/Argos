/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.proto;

/**
 * Bits of {@code TransactionRecord.flags} and of the ClickHouse {@code xlog.flags} column (ADR-0007
 * C-69). Other bits are reserved and sent as 0. The agent constants {@code
 * io.argos.agent.bootstrap.TraceContext.FLAG_*} use the same values (checked by the :proto contract
 * test).
 */
public final class TransactionFlags {

  /**
   * The transaction began on a virtual thread (equals {@code TransactionRecord.virtual_thread}).
   */
  public static final int VIRTUAL_THREAD = 1;

  /**
   * The agent ended the transaction itself because its context outlived the request (10 §8, §13.1
   * {@code max-duration-ms}).
   */
  public static final int LEAKED = 2;

  /** The step buffer reached {@code sampling.profile.max-steps} (10 §9.1 summary mode). */
  public static final int SUMMARIZED = 4;

  /** Every defined bit. */
  public static final int MASK = VIRTUAL_THREAD | LEAKED | SUMMARIZED;

  private TransactionFlags() {}
}
