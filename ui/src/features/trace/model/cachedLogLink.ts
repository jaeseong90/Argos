// SPDX-License-Identifier: Apache-2.0
//
// The log link of the trace view (05 §2.8-5, F-U18). The trace response carries no log link; the
// server builds one per transaction (`links.logs` of the detail, null unless the log-link template
// is set, WO-75). The trace view uses the root transaction's link when its detail is already in
// the query cache of this session (the user came from that detail). It never requests a detail
// for it: a detail read is audited and the user did not open that transaction.
import type { QueryClient } from '@tanstack/react-query';

import type { Enveloped } from '../../../api/client';
import type { TransactionDetail } from '../../transaction/data/transactionApi';
import { transactionKeys } from '../../transaction/data/useTransaction';

/** The cached `links.logs` of a transaction (any ts hint), or null. */
export function cachedLogLink(queryClient: QueryClient, txId: string | null): string | null {
  if (txId === null) {
    return null;
  }
  // [resource, part, txId]: every cached detail of the transaction, whatever its ts hint.
  const prefix = transactionKeys.detail(txId, null).slice(0, 3);
  for (const [, data] of queryClient.getQueriesData<Enveloped<TransactionDetail>>({
    queryKey: prefix,
  })) {
    const logs = data?.data.links.logs;
    if (typeof logs === 'string' && logs !== '') {
      return logs;
    }
  }
  return null;
}
