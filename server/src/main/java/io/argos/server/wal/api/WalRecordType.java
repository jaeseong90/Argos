/* SPDX-License-Identifier: Apache-2.0 */
package io.argos.server.wal.api;

/**
 * The {@code type u8} of a WAL record (12 §3.4). The four data types are also the batch kinds of
 * the deduplication key (C-3, DSN-11-02); {@code ActiveTransactionSnapshot} never reaches the WAL
 * (12 §3.2) and has no type.
 *
 * <p>Tables fed by each data type (12 §3.5, §3.6): {@link #TX} {@code xlog}; {@link #PROFILE}
 * {@code profile_step}; {@link #METRIC} {@code metric}; {@link #STAT} {@code sql_stat_raw}, {@code
 * ext_stat_raw} and, for its {@code CounterDelta}s, {@code metric}.
 */
public enum WalRecordType {
  /** {@code TransactionBatch} ({@code SendTransactions}). */
  TX(1),
  /** {@code ProfileBatch} ({@code SendProfiles}). */
  PROFILE(2),
  /** {@code MetricBatch} ({@code SendMetrics}). */
  METRIC(3),
  /** {@code StatBatch} ({@code SendStats}). */
  STAT(4),
  /**
   * Insert intent of one ClickHouse block, written and fsynced before the insert (12 §3.8, C-61).
   */
  BLOCK_INTENT(16),
  /** The block of an intent is inserted (12 §3.8). */
  BLOCK_COMMIT(17);

  private final int code;

  WalRecordType(int code) {
    this.code = code;
  }

  /** The {@code type u8} value. */
  public int code() {
    return code;
  }

  /**
   * Whether records of this type carry a batch ({@link WalDataPayload}) and a deduplication key.
   */
  public boolean isData() {
    return code < BLOCK_INTENT.code;
  }

  /**
   * The type of a {@code type u8} value.
   *
   * @throws IllegalArgumentException for a value that is no type (a corrupt record)
   */
  public static WalRecordType fromCode(int code) {
    for (WalRecordType type : values()) {
      if (type.code == code) {
        return type;
      }
    }
    throw new IllegalArgumentException("Unknown WAL record type " + code);
  }
}
