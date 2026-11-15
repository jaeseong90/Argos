// SPDX-License-Identifier: Apache-2.0
//
// /tx/:txId deep-link alias (docs/14 §6, ADR-0010 C-166): looks up the transaction's application
// and replaces the URL with the app-scoped route /apps/:appId/transactions/:txId (keeping ts, tab,
// compare and the global keys). A transaction not found near the time in its id answers 404
// clock_skew_possible: the page then says how to open it (list or ts hint).
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Navigate, useParams } from 'react-router';

import { errorInfo, isApiError } from '../api/errors';
import { queryKeys } from '../api/queryKeys';
import { locateTransaction } from '../api/transactions';
import { useDocumentTitle } from '../app/useDocumentTitle';
import { EmptyState } from '../components/EmptyState';
import { ErrorState } from '../components/ErrorState';
import { LoadingState } from '../components/LoadingState';
import { formatInstant, TxDetailQ, writeQuery, type TxDetailState } from '../state/urlState';
import { useUrlState } from '../state/useUrlState';

import { useCarriedNavigation } from './navigation';
import { screenById } from './screens';

const TX_ID = /^[0-9]{1,20}$/;

export function TransactionAlias() {
  const { t } = useTranslation(['common', 'shell']);
  const { txId = '' } = useParams();
  const { state } = useUrlState(TxDetailQ);
  const { goToScreen } = useCarriedNavigation();
  useDocumentTitle(t('shell:screen.transaction'));
  const valid = TX_ID.test(txId);
  const ts = state.ts === undefined ? undefined : formatInstant(state.ts);
  const query = useQuery({
    queryKey: queryKeys.transactions.location(txId, ts ?? null),
    queryFn: () => locateTransaction(txId, ts),
    enabled: valid,
    retry: false,
    staleTime: Infinity,
  });

  if (!valid || (query.isError && isApiError(query.error) && query.error.status === 404)) {
    return (
      <EmptyState
        data-testid="transaction-not-found"
        title={t('shell:transactionLink.notFoundTitle')}
        description={t('shell:transactionLink.notFoundDescription')}
        actions={[
          {
            label: t('shell:transactionLink.overview'),
            onClick: () => {
              goToScreen(screenById('overview'));
            },
          },
        ]}
      />
    );
  }
  if (query.isError) {
    return <ErrorState error={errorInfo(query.error)} onRetry={() => void query.refetch()} />;
  }
  if (query.isPending) {
    return <LoadingState data-testid="loading-state" />;
  }
  const start = Date.parse(query.data.ts);
  const located: TxDetailState = { ...state, ts: Number.isNaN(start) ? state.ts : start };
  const search = writeQuery(TxDetailQ, located);
  const path = `/apps/${String(query.data.appId)}/transactions/${txId}`;
  return <Navigate to={search === '' ? path : `${path}?${search}`} replace />;
}
