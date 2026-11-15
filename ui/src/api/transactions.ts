// SPDX-License-Identifier: Apache-2.0
//
// Transaction lookups used by the shell: the /tx/:txId deep-link alias finds the application of a
// transaction and redirects to the app-scoped route (ADR-0010 C-166). The transaction screens
// (WO-33, WO-34) add their calls here.
import { api, unwrap, type EnvelopeOf } from './client';

export interface TransactionLocation {
  readonly appId: number;
  /** Start of the transaction (ISO-8601 UTC), the `ts` hint of the app-scoped route. */
  readonly ts: string;
}

/**
 * Where a transaction lives (docs/14 §4.6 transactions_get): `ts` is the optional start hint (ISO).
 * 404 not_found with detail clock_skew_possible when it is not near the time in its id (C-150).
 */
export async function locateTransaction(txId: string, ts?: string): Promise<TransactionLocation> {
  const query: { ts?: string } = {};
  if (ts !== undefined) {
    query.ts = ts;
  }
  const result = await unwrap<EnvelopeOf<'/api/v1/transactions/{txId}', 'get'>>(
    api().client.GET('/api/v1/transactions/{txId}', {
      params: { path: { txId }, query },
    }),
  );
  return { appId: result.data.app.app_id, ts: result.data.ts };
}
