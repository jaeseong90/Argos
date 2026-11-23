// SPDX-License-Identifier: Apache-2.0
//
// /tx/:txId deep-link alias (docs/14 §6, ADR-0010 C-166): looks up the transaction's application
// and replaces the URL with the app-scoped route /apps/:appId/transactions/:txId (keeping ts, tab,
// compare, back and the global keys). A transaction not found near the time in its id answers 404
// clock_skew_possible: the page then says how to open it (list or ts hint). The detail it fetched
// is stored under the key the detail route asks for (primeTransaction), so the redirect does not
// fetch it a second time.
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Navigate, useParams } from 'react-router';

import { errorInfo, isApiError } from '../api/errors';
import { queryKeys } from '../api/queryKeys';
import { useDocumentTitle } from '../app/useDocumentTitle';
import { EmptyState } from '../components/EmptyState';
import { ErrorState } from '../components/ErrorState';
import { LoadingState } from '../components/LoadingState';
import { fetchTransaction, isTxIdForm } from '../features/transaction/data/transactionApi';
import { primeTransaction } from '../features/transaction/data/useTransaction';
import { formatInstant, TxDetailQ, writeQuery, type TxDetailState } from '../state/urlState';
import { useUrlState } from '../state/useUrlState';

import { useCarriedNavigation } from './navigation';
import { screenById } from './screens';

export function TransactionAlias() {
  const { t } = useTranslation(['common', 'shell', 'tx']);
  const { txId = '' } = useParams();
  const { state } = useUrlState(TxDetailQ);
  const { goToScreen } = useCarriedNavigation();
  const queryClient = useQueryClient();
  useDocumentTitle(t('shell:screen.transaction'));
  const valid = isTxIdForm(txId);
  const ts = state.ts === undefined ? undefined : formatInstant(state.ts);
  const query = useQuery({
    queryKey: queryKeys.transactions.location(txId, ts ?? null),
    queryFn: async ({ signal }) => {
      const result = await fetchTransaction(txId, ts, signal);
      primeTransaction(queryClient, txId, result);
      return { appId: result.data.app.app_id, ts: result.data.ts };
    },
    enabled: valid,
    retry: false,
    staleTime: Infinity,
  });

  if (!valid || (query.isError && isApiError(query.error) && query.error.status === 404)) {
    return (
      <EmptyState
        data-testid="transaction-not-found"
        title={t('shell:transactionLink.notFoundTitle')}
        description={
          !valid
            ? t('tx:notFound.invalidId')
            : ts !== undefined
              ? // The link already had the C-50 time hint: the ts advice would not help.
                t('tx:notFound.hinted')
              : t('shell:transactionLink.notFoundDescription')
        }
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
    return <LoadingState slowHint="wait" data-testid="loading-state" />;
  }
  const start = Date.parse(query.data.ts);
  const located: TxDetailState = { ...state, ts: Number.isNaN(start) ? state.ts : start };
  const search = writeQuery(TxDetailQ, located);
  const path = `/apps/${String(query.data.appId)}/transactions/${txId}`;
  return <Navigate to={search === '' ? path : `${path}?${search}`} replace />;
}
