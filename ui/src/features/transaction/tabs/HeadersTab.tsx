// SPDX-License-Identifier: Apache-2.0
//
// The headers tab (05 §2.2 "수집된 헤더·요청 ID", docs/15 §8.6): the request headers the agent was
// allowed to collect (the configured allow list; others are never collected) as name/value text, and
// the request id. Names and values are collected strings (SafeText, SEC-60).
import { useTranslation } from 'react-i18next';

import { SafeText } from '../../../components/SafeText';
import type { TransactionDetail } from '../data/transactionApi';
import { Field } from '../parts';

export function HeadersTab({ detail }: { readonly detail: TransactionDetail }) {
  const { t } = useTranslation(['common', 'tx']);
  const headers = Object.entries(detail.headers).sort(([a], [b]) => a.localeCompare(b));
  return (
    <div className="tx-tab tx-headers" data-testid="transaction-headers">
      <dl className="tx-totals">
        <Field label={t('tx:summary.requestId')}>
          <SafeText value={detail.request_id} mono data-testid="transaction-request-id" />
        </Field>
      </dl>
      <p className="tx-muted">{t('tx:headers.allowList')}</p>
      {headers.length === 0 ? (
        <p className="tx-muted" data-testid="transaction-headers-empty">
          {t('tx:headers.empty')}
        </p>
      ) : (
        <table className="tx-table" data-testid="transaction-headers-table">
          <caption className="visually-hidden">{t('tx:headers.caption')}</caption>
          <thead>
            <tr>
              <th scope="col">{t('tx:headers.name')}</th>
              <th scope="col">{t('tx:headers.value')}</th>
            </tr>
          </thead>
          <tbody>
            {headers.map(([name, value]) => (
              <tr key={name} data-testid="transaction-header-row">
                <th scope="row">
                  <SafeText value={name} mono data-testid="transaction-header-name" />
                </th>
                <td>
                  <SafeText
                    value={value}
                    mono
                    maxLines={3}
                    data-testid="transaction-header-value"
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
