/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Write-ahead log (12 §3.4, DSN-12-01, DSN-12-02): {@code WalWriter}, {@code WalSegment}, {@code
 * WalReader}, {@code BlockIndex}, {@code WalRecovery}. Recovery runs as the {@code WAL_RECOVERY}
 * phase of the start sequence.
 *
 * <p>Owner: WO-21.
 */
package io.argos.server.wal;
