// SPDX-License-Identifier: Apache-2.0
//
// Selection summary of the XLog PoC (docs/05 §2.1, docs/15 §7.4): point count, top 10 services
// (count, average, maximum, errors), distribution per instance, status, HTTP status and error
// class. Selecting a row filters the list. Names are collected data and render as text only
// (SEC-60).
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import type { SelectionSummary, SummaryFilter } from '../../xlog/selectionSummary';
import { useXLogFormat } from '../../xlog/useXLogFormat';
import { STATUS_ERROR, STATUS_NORMAL, STATUS_SLOW } from '../../xlog/XLogBuffer';

export interface SelectionSummaryPanelProps {
  readonly summary: SelectionSummary | null;
  readonly filter: SummaryFilter | null;
  readonly onFilter: (filter: SummaryFilter | null) => void;
  readonly onClear: () => void;
}

function sameFilter(a: SummaryFilter | null, b: SummaryFilter): boolean {
  return a !== null && a.field === b.field && a.value === b.value;
}

interface FilterCellProps {
  readonly filter: SummaryFilter;
  readonly active: SummaryFilter | null;
  readonly onFilter: (filter: SummaryFilter | null) => void;
  readonly children: ReactNode;
  readonly title?: string;
}

/** First cell of a summary row: a button that toggles the list filter. */
function FilterCell({ filter, active, onFilter, children, title }: FilterCellProps) {
  const pressed = sameFilter(active, filter);
  return (
    <th scope="row" className="xlog-summary__name">
      <button
        type="button"
        className="xlog-summary__filter"
        aria-pressed={pressed}
        title={title}
        onClick={() => {
          onFilter(pressed ? null : filter);
        }}
      >
        {children}
      </button>
    </th>
  );
}

export function SelectionSummaryPanel({
  summary,
  filter,
  onFilter,
  onClear,
}: SelectionSummaryPanelProps) {
  const { t } = useTranslation(['common', 'xlog']);
  const format = useXLogFormat();

  if (summary === null) {
    return (
      <section
        className="xlog-summary"
        data-testid="xlog-summary"
        aria-labelledby="xlog-summary-title"
      >
        <h2 id="xlog-summary-title" className="xlog-poc__heading">
          {t('xlog:summary.title')}
        </h2>
        <p className="xlog-poc__muted" data-testid="xlog-summary-empty">
          {t('xlog:summary.empty')}
        </p>
      </section>
    );
  }

  const statusRows = [
    { status: STATUS_NORMAL, count: summary.status.normal },
    { status: STATUS_SLOW, count: summary.status.slow },
    { status: STATUS_ERROR, count: summary.status.error },
  ];

  return (
    <section
      className="xlog-summary"
      data-testid="xlog-summary"
      aria-labelledby="xlog-summary-title"
    >
      <div className="xlog-summary__header">
        <h2 id="xlog-summary-title" className="xlog-poc__heading">
          {t('xlog:summary.title')}
        </h2>
        <p className="xlog-summary__count" data-testid="xlog-summary-count">
          {t('xlog:summary.count', { count: summary.count })}
        </p>
        <button type="button" data-testid="xlog-summary-clear" onClick={onClear}>
          {t('xlog:summary.clear')}
        </button>
      </div>
      {summary.sampled && (
        <p className="xlog-summary__sampled" data-testid="xlog-summary-sampled">
          {t('xlog:summary.sampled')}
        </p>
      )}
      <p className="xlog-poc__muted">{t('xlog:summary.filterHint')}</p>
      <div className="xlog-summary__grid">
        <table className="xlog-table xlog-summary__services" data-testid="xlog-summary-services">
          <caption>
            {t('xlog:summary.services.title')}{' '}
            <span className="xlog-poc__muted">
              {t('xlog:summary.services.more', {
                shown: summary.services.length,
                total: summary.serviceCount,
              })}
            </span>
          </caption>
          <thead>
            <tr>
              <th scope="col">{t('xlog:summary.services.service')}</th>
              <th scope="col">{t('xlog:summary.services.count')}</th>
              <th scope="col">{t('xlog:summary.services.avg')}</th>
              <th scope="col">{t('xlog:summary.services.max')}</th>
              <th scope="col">{t('xlog:summary.services.errors')}</th>
            </tr>
          </thead>
          <tbody>
            {summary.services.map((row) => {
              const name = format.name(row.name, row.service);
              return (
                <tr key={row.service} data-testid="xlog-summary-service-row">
                  <FilterCell
                    filter={{ field: 'service', value: row.service }}
                    active={filter}
                    onFilter={onFilter}
                    title={name}
                  >
                    {name}
                  </FilterCell>
                  <td>{format.count(row.count)}</td>
                  <td>{format.duration(row.avgElapsed)}</td>
                  <td>{format.duration(row.maxElapsed)}</td>
                  <td>{format.count(row.errorCount)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>

        <table className="xlog-table" data-testid="xlog-summary-instances">
          <caption>{t('xlog:summary.instances.title')}</caption>
          <thead>
            <tr>
              <th scope="col">{t('xlog:summary.instances.instance')}</th>
              <th scope="col">{t('xlog:summary.instances.count')}</th>
              <th scope="col">{t('xlog:summary.instances.errors')}</th>
            </tr>
          </thead>
          <tbody>
            {summary.instances.map((row) => {
              const name = format.name(row.name, row.instance);
              return (
                <tr key={row.instance} data-testid="xlog-summary-instance-row">
                  <FilterCell
                    filter={{ field: 'instance', value: row.instance }}
                    active={filter}
                    onFilter={onFilter}
                    title={name}
                  >
                    {name}
                  </FilterCell>
                  <td>{format.count(row.count)}</td>
                  <td>{format.count(row.errorCount)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>

        <table className="xlog-table" data-testid="xlog-summary-status">
          <caption>{t('xlog:summary.status.title')}</caption>
          <thead>
            <tr>
              <th scope="col">{t('xlog:summary.status.status')}</th>
              <th scope="col">{t('xlog:summary.status.count')}</th>
            </tr>
          </thead>
          <tbody>
            {statusRows.map((row) => (
              <tr key={row.status}>
                <FilterCell
                  filter={{ field: 'status', value: row.status }}
                  active={filter}
                  onFilter={onFilter}
                >
                  <span
                    className={`xlog-status xlog-status--${String(row.status)}`}
                    aria-hidden="true"
                  />
                  {format.status(row.status)}
                </FilterCell>
                <td>{format.count(row.count)}</td>
              </tr>
            ))}
          </tbody>
        </table>

        <table className="xlog-table" data-testid="xlog-summary-http">
          <caption>{t('xlog:summary.http.title')}</caption>
          <thead>
            <tr>
              <th scope="col">{t('xlog:summary.http.code')}</th>
              <th scope="col">{t('xlog:summary.http.count')}</th>
            </tr>
          </thead>
          <tbody>
            {summary.httpStatus.map((row) => (
              <tr key={row.code}>
                <FilterCell
                  filter={{ field: 'httpStatus', value: row.code }}
                  active={filter}
                  onFilter={onFilter}
                >
                  {format.httpStatus(row.code)}
                </FilterCell>
                <td>{format.count(row.count)}</td>
              </tr>
            ))}
          </tbody>
        </table>

        <div data-testid="xlog-summary-errors">
          {summary.errorClasses === null ? (
            <p className="xlog-poc__muted">{t('xlog:summary.errors.unavailable')}</p>
          ) : summary.errorClasses.length === 0 ? (
            <p className="xlog-poc__muted">{t('xlog:summary.errors.none')}</p>
          ) : (
            <table className="xlog-table">
              <caption>{t('xlog:summary.errors.title')}</caption>
              <thead>
                <tr>
                  <th scope="col">{t('xlog:summary.errors.errorClass')}</th>
                  <th scope="col">{t('xlog:summary.errors.count')}</th>
                </tr>
              </thead>
              <tbody>
                {summary.errorClasses.map((row) => {
                  const name = format.name(row.name, row.errorClass - 1);
                  return (
                    <tr key={row.errorClass} data-testid="xlog-summary-error-row">
                      <FilterCell
                        filter={{ field: 'errorClass', value: row.errorClass }}
                        active={filter}
                        onFilter={onFilter}
                        title={name}
                      >
                        {name}
                      </FilterCell>
                      <td>{format.count(row.count)}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </section>
  );
}
