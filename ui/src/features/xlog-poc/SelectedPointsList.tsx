// SPDX-License-Identifier: Apache-2.0
//
// Selected-points list of the XLog PoC: the slowest points of the selection (docs/15 §7.4,
// ≤ 1,000 rows), filtered by a summary row. The common transaction table (DataTable, 13 required
// columns, sorting, CSV) is out of the PoC scope (WO-04). Collected strings render as text only
// (SEC-60).
import { useTranslation } from 'react-i18next';

import type { PointList } from '../../xlog/selectionSummary';
import { useXLogFormat } from '../../xlog/useXLogFormat';

export interface SelectedPointsListProps {
  readonly list: PointList;
  /**
   * The active filter, e.g. field "Service" and value "GET /api/orders"; null without filter. The
   * value is collected data: it is rendered as a text node, never passed to i18next (SEC-60).
   */
  readonly filterLabel: { readonly field: string; readonly value: string } | null;
  readonly onClearFilter: () => void;
}

export function SelectedPointsList({ list, filterLabel, onClearFilter }: SelectedPointsListProps) {
  const { t } = useTranslation(['common', 'xlog']);
  const format = useXLogFormat();
  const none = t('xlog:value.none');
  return (
    <section className="xlog-list" data-testid="xlog-list" aria-labelledby="xlog-list-title">
      <div className="xlog-list__header">
        <h2 id="xlog-list-title" className="xlog-poc__heading">
          {t('xlog:list.title')}
        </h2>
        <p className="xlog-poc__muted" data-testid="xlog-list-count">
          {list.truncated
            ? t('xlog:list.top', { shown: list.rows.length, matching: list.matching })
            : t('xlog:list.all', { count: list.matching })}
        </p>
        {filterLabel !== null && (
          <p className="xlog-list__filter" data-testid="xlog-list-filter">
            <span data-testid="xlog-list-filter-label">
              {t('xlog:list.filterField', { field: filterLabel.field })}{' '}
              <span className="xlog-list__filter-value">{filterLabel.value}</span>
            </span>
            <button type="button" data-testid="xlog-list-clear-filter" onClick={onClearFilter}>
              {t('xlog:list.clearFilter')}
            </button>
          </p>
        )}
      </div>
      {list.rows.length === 0 ? (
        <p className="xlog-poc__muted" data-testid="xlog-list-empty">
          {t('xlog:list.empty')}
        </p>
      ) : (
        <div className="xlog-list__scroll">
          <table className="xlog-table xlog-list__table" data-testid="xlog-list-table">
            <thead>
              <tr>
                <th scope="col">{t('xlog:list.columns.time')}</th>
                <th scope="col">{t('xlog:list.columns.service')}</th>
                <th scope="col">{t('xlog:list.columns.elapsed')}</th>
                <th scope="col">{t('xlog:list.columns.status')}</th>
                <th scope="col">{t('xlog:list.columns.http')}</th>
                <th scope="col">{t('xlog:list.columns.errorClass')}</th>
                <th scope="col">{t('xlog:list.columns.instance')}</th>
                <th scope="col">{t('xlog:list.columns.txId')}</th>
              </tr>
            </thead>
            <tbody>
              {list.rows.map((row) => {
                const service = format.name(row.serviceName, row.service);
                const errorClass =
                  row.errorClass === 0 ? none : format.name(row.errorClassName, row.errorClass - 1);
                return (
                  <tr key={row.key} data-testid="xlog-list-row">
                    <td className="xlog-table__num">{format.time(row.ts)}</td>
                    <td className="xlog-list__text" title={service}>
                      {service}
                    </td>
                    <td className="xlog-table__num">{format.duration(row.elapsed)}</td>
                    <td>
                      <span
                        className={`xlog-status xlog-status--${String(row.status)}`}
                        aria-hidden="true"
                      />
                      {format.status(row.status)}
                    </td>
                    <td className="xlog-table__num">{format.httpStatus(row.httpStatus)}</td>
                    <td className="xlog-list__text" title={errorClass}>
                      {errorClass}
                    </td>
                    <td className="xlog-list__text">
                      {format.name(row.instanceName, row.instance)}
                    </td>
                    <td className="xlog-table__mono">{row.txId}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
