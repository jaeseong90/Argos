// SPDX-License-Identifier: Apache-2.0
//
// Links out of the thread dump viewer (05 §2.7-5 "트랜잭션 매칭", WO-37): a thread's tx_id opens
// the transaction detail (`/apps/:appId/transactions/:txId`, C-166). The dump is a snapshot: the
// transaction may have finished (or still run) when the link is followed, and its detail exists
// only once it was collected.

/** The detail route of a transaction; txId is a decimal string (C-175). */
export function transactionHref(appId: number, txId: string): string {
  return `/apps/${String(appId)}/transactions/${encodeURIComponent(txId)}`;
}

/**
 * The download name of a dump: the instance id and the time only (no collected string such as a
 * host or thread name), e.g. `argos-thread-dump-12-20261124T101500Z.txt`.
 */
export function dumpFileName(instanceId: number, at: string | null): string {
  const ms = at === null ? Number.NaN : Date.parse(at);
  const stamp = Number.isNaN(ms)
    ? 'unknown-time'
    : new Date(ms)
        .toISOString()
        .replace(/[-:]/g, '')
        .replace(/\.\d{3}/, '');
  return `argos-thread-dump-${String(instanceId)}-${stamp}.txt`;
}
