// SPDX-License-Identifier: Apache-2.0
//
// Selection summary of the XLog screen (05 §2.1-2, docs/15 §7.4, ADR-0010 C-202): for the URL
// selection, the point count, the top 10 services (count, average, maximum, errors), the instance
// and status distributions computed in the browser from the points shown, and the HTTP status and
// error class distributions of the server summary (labelled "server aggregate"; the XLog points
// carry no such columns, C-202 (1)). On a sampled response the browser part says "based on the
// points shown" and the server count of the region is shown next to it. Clicking a service,
// instance, error status, HTTP status or error class row toggles the matching f= filter (the scatter
// and the list follow; the selection is kept, C-202 (2)). Names are collected data and render
// through SafeText only (SEC-60). The counts are announced politely (aria-live, WO-04 C-12).
import { useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useState, useSyncExternalStore, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import { errorInfo } from '../../../api/errors';
import { queryKeys } from '../../../api/queryKeys';
import {
  summarizeXlog,
  toXlogSummaryRequest,
  type XLogSearchQuery,
} from '../../../api/transactionSearch';
import { ErrorState } from '../../../components/ErrorState';
import { SafeText } from '../../../components/SafeText';
import type { Filter } from '../../../state/urlState';
import { FILTER_MAX } from '../../../state/urlState';
import { samplingIndicator, type SamplingInfo } from '../../../xlog/sampling';
import { useXLogFormat } from '../../../xlog/useXLogFormat';
import {
  STATUS_ERROR,
  STATUS_NORMAL,
  STATUS_SLOW,
  type XLogBuffer,
} from '../../../xlog/XLogBuffer';
import { selectionKey } from '../list/TxListPanel';
import { UnappliedFilters } from '../list/UnappliedFilters';

import {
  NO_CLIENT_COLUMNS,
  clientSummary,
  hasFilter,
  toggleFilter,
  type ClientColumns,
  type SummaryDictionary,
  type SummaryTiming,
} from './summaryModel';

import './summary.css';

export interface SelectionSummaryPanelProps {
  readonly appId: number;
  /** The XLog URL state: `sel` (the selection), and the kind and filters of the server summary. */
  readonly query: XLogSearchQuery;
  /** The points shown (the canvas buffer). */
  readonly buffer: XLogBuffer;
  readonly dictionary: SummaryDictionary;
  /** meta.shown / meta.total of the XLog response; null when not sampled or unknown. */
  readonly sampling: SamplingInfo | null;
  /** New URL filters after a row click (the page writes them; `sel` stays). */
  readonly onFilter: (filters: Filter[]) => void;
  /** Clears the selection (shows the "Clear selection" button). */
  readonly onClear?: () => void;
  /** Columns the points carry besides the API's (none per C-202). */
  readonly clientColumns?: ClientColumns;
  /**
   * Timing of each new summary (window.__xlogPerf, ADR-0003 C-36): `selectMs` finding the selected
   * points (judged, 5 ms), `computeMs` selection plus summary (part of the 100 ms screen reflect).
   */
  readonly onComputed?: (timing: SummaryTiming) => void;
  /**
   * Announces the counts politely (aria-live, WO-04 C-12). Default true; false when the page has
   * its own resident announcer (XLogPage's SelectionAnnouncer), so a selection is read once.
   */
  readonly announce?: boolean;
  /**
   * The error class names of each server summary (by_error_class id and name), so that the page
   * can name f.error_class keys in the filter chips (chipLabels.ts).
   */
  readonly onErrorClassNames?: (
    names: readonly { readonly id: string | null; readonly name: string | null }[],
  ) => void;
}

interface FilterCellProps {
  readonly pressed: boolean;
  readonly disabled?: boolean;
  readonly onToggle: () => void;
  readonly children: ReactNode;
}

/** First cell of a summary row: a toggle button for the row's f= filter. */
function FilterCell({ pressed, disabled = false, onToggle, children }: FilterCellProps) {
  return (
    <th scope="row" className="xlog-summary__name">
      <button
        type="button"
        className="xlog-summary__filter"
        aria-pressed={pressed}
        disabled={disabled}
        onClick={onToggle}
      >
        {children}
      </button>
    </th>
  );
}

const HTTP_TESTID = 'xlog-summary-http';
const ERRORS_TESTID = 'xlog-summary-errors';

/** React key of a server group without a key (an error class the dictionary does not know). */
function fallbackKey(index: number): string {
  return `none-${String(index)}`;
}

/** One row of the error class distribution, from the server or (with the column) the points. */
interface ErrorClassView {
  readonly key: string;
  /** error_class_id (decimal string, C-175); null when the source has no key. */
  readonly id: string | null;
  /** Collected text: SafeText only. */
  readonly name: string | null;
  readonly count: number;
}

/** A source label of a table: "Points shown" or "Server aggregate" (C-202). */
function SourceLabel({ server }: { readonly server: boolean }) {
  const { t } = useTranslation(['common', 'xlog']);
  return (
    <span
      className={`xlog-summary__source${server ? ' xlog-summary__source--server' : ''}`}
      title={server ? t('xlog:summary.source.serverHint') : t('xlog:summary.source.clientHint')}
      data-testid="xlog-summary-source"
      data-source={server ? 'server' : 'client'}
    >
      {server ? t('xlog:summary.source.server') : t('xlog:summary.source.client')}
    </span>
  );
}

/** Buffer version, so that new or replaced points recompute the summary. */
function useBufferVersion(buffer: XLogBuffer): number {
  return useSyncExternalStore(
    (listener) => buffer.subscribe(listener),
    () => buffer.version,
  );
}

export function SelectionSummaryPanel({
  appId,
  query,
  buffer,
  dictionary,
  sampling,
  onFilter,
  onClear,
  clientColumns = NO_CLIENT_COLUMNS,
  onComputed,
  announce = true,
  onErrorClassNames,
}: SelectionSummaryPanelProps) {
  const { t } = useTranslation(['common', 'xlog']);
  const format = useXLogFormat();
  const version = useBufferVersion(buffer);
  // The "too many filters" hint belongs to the selection and filters it was shown for.
  const [limitKey, setLimitKey] = useState<string | null>(null);
  const sel = query.sel;
  const selKey = selectionKey(query);
  const sampled = samplingIndicator(sampling) !== null;

  const client = useMemo(
    // The buffer is mutated in place: its version (never negative) makes new points recompute.
    () =>
      sel === undefined || version < 0 ? null : clientSummary(buffer, dictionary, sel, sampled),
    [buffer, version, dictionary, sel, sampled],
  );
  useEffect(() => {
    if (client !== null) {
      onComputed?.({
        points: client.summary.count,
        selectMs: client.selectMs,
        computeMs: client.computeMs,
      });
    }
  }, [client, onComputed]);

  const call = useMemo(
    () =>
      toXlogSummaryRequest(appId, {
        time: query.time,
        kind: query.kind,
        filters: query.filters,
        sel: query.sel,
      }),
    [appId, query.time, query.kind, query.filters, query.sel],
  );
  const server = useQuery({
    queryKey: queryKeys.screen('xlog-summary', {
      appId,
      // Without a selection the query is disabled; its key still needs valid instants.
      range:
        call === null
          ? { from: 0, to: 0 }
          : { from: Date.parse(call.body.from), to: Date.parse(call.body.to) },
      kind: query.kind,
      filters: query.filters,
      extra: { sel: selKey },
    }),
    queryFn: ({ signal }) => {
      if (call === null) {
        throw new Error('no selection');
      }
      return summarizeXlog(call, signal);
    },
    enabled: call !== null && !call.empty,
    staleTime: 30_000,
  });
  const errorClassGroups = server.data?.data.by_error_class;
  useEffect(() => {
    if (errorClassGroups !== undefined) {
      onErrorClassNames?.(errorClassGroups.map((g) => ({ id: g.error_class_id, name: g.name })));
    }
  }, [errorClassGroups, onErrorClassNames]);

  const filtersKey = `${selKey}|${JSON.stringify(query.filters)}`;
  const limitHint = limitKey === filtersKey;
  const toggle = (field: string, value: string): void => {
    const next = toggleFilter(query.filters, field, value);
    if (next === null) {
      setLimitKey(filtersKey);
      return;
    }
    setLimitKey(null);
    onFilter(next);
  };

  if (sel === undefined || client === null) {
    return (
      <section
        className="xlog-summary"
        aria-labelledby="xlog-summary-title"
        data-testid="xlog-summary"
        data-state="idle"
      >
        <h2 id="xlog-summary-title" className="xlog-summary__title">
          {t('xlog:summary.title')}
        </h2>
        <p className="xlog-summary__muted" data-testid="xlog-summary-empty">
          {t('xlog:summary.emptyKeyboard')}
        </p>
      </section>
    );
  }

  const summary = client.summary;
  const serverData = call?.empty === true ? null : (server.data?.data ?? null);
  const serverState =
    call === null || call.empty
      ? 'none'
      : server.isError
        ? 'error'
        : serverData === null
          ? 'loading'
          : 'ready';
  const statusRows = [
    { status: STATUS_NORMAL, count: summary.status.normal },
    { status: STATUS_SLOW, count: summary.status.slow },
    { status: STATUS_ERROR, count: summary.status.error },
  ];
  const httpClient = clientColumns.httpStatus;
  const errorsClient = clientColumns.errorClass && summary.errorClasses !== null;

  const serverPending = (testId: string): ReactNode =>
    serverState === 'error' ? (
      <div data-testid={`${testId}-error`}>
        <ErrorState
          error={errorInfo(server.error)}
          compact
          onRetry={() => {
            void server.refetch();
          }}
        />
      </div>
    ) : serverState === 'loading' ? (
      <p className="xlog-summary__muted" data-testid={`${testId}-loading`}>
        {t('xlog:summary.loadingServer')}
      </p>
    ) : null;

  return (
    <section
      className="xlog-summary"
      aria-labelledby="xlog-summary-title"
      data-testid="xlog-summary"
      data-state="ready"
      data-sel={selKey}
      data-client-count={summary.count}
      data-server-state={serverState}
    >
      <div className="xlog-summary__header">
        <h2 id="xlog-summary-title" className="xlog-summary__title">
          {t('xlog:summary.title')}
        </h2>
        <div className="xlog-summary__counts" aria-live={announce ? 'polite' : undefined}>
          <p
            className="xlog-summary__count"
            data-testid="xlog-summary-count"
            data-count={summary.count}
          >
            {t('xlog:summary.count', { count: summary.count })}
          </p>
          {serverData === null ? null : (
            <p
              className="xlog-summary__server-count"
              data-testid="xlog-summary-server-count"
              data-count={serverData.count}
            >
              {t('xlog:summary.serverCount', { count: serverData.count })}
            </p>
          )}
        </div>
        {onClear === undefined ? null : (
          <button
            type="button"
            className="control-button"
            data-testid="xlog-summary-clear"
            onClick={onClear}
          >
            {t('xlog:summary.clear')}
          </button>
        )}
      </div>
      {summary.sampled ? (
        <p className="xlog-summary__sampled" data-testid="xlog-summary-sampled">
          {t('xlog:summary.sampled')}
        </p>
      ) : null}
      <p className="xlog-summary__muted">{t('xlog:summary.rowFilterHint')}</p>
      {limitHint ? (
        <p className="xlog-summary__muted" role="status" data-testid="xlog-summary-filter-limit">
          {t('xlog:list.filterLimit', { max: FILTER_MAX })}
        </p>
      ) : null}
      <UnappliedFilters
        unapplied={call?.unapplied ?? []}
        title={t('xlog:summary.unapplied')}
        data-testid="xlog-summary-unapplied"
      />
      <div className="xlog-summary__grid">
        <table className="xlog-summary__table" data-testid="xlog-summary-services">
          <caption>
            {t('xlog:summary.services.title')} <SourceLabel server={false} />{' '}
            <span className="xlog-summary__muted">
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
              const key = row.service < 0 ? undefined : dictionary.serviceIds[row.service];
              return (
                <tr
                  key={row.service}
                  data-testid="xlog-summary-service-row"
                  data-service-id={key}
                  data-count={row.count}
                >
                  <FilterCell
                    pressed={key !== undefined && hasFilter(query.filters, 'service', key)}
                    disabled={key === undefined}
                    onToggle={() => {
                      if (key !== undefined) toggle('service', key);
                    }}
                  >
                    {row.service < 0 || row.name === null ? (
                      t('xlog:value.unknown', { id: key ?? '?' })
                    ) : (
                      <SafeText value={row.name} />
                    )}
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

        <table className="xlog-summary__table" data-testid="xlog-summary-instances">
          <caption>
            {t('xlog:summary.instances.title')} <SourceLabel server={false} />
          </caption>
          <thead>
            <tr>
              <th scope="col">{t('xlog:summary.instances.instance')}</th>
              <th scope="col">{t('xlog:summary.instances.count')}</th>
              <th scope="col">{t('xlog:summary.instances.errors')}</th>
            </tr>
          </thead>
          <tbody>
            {summary.instances.map((row) => {
              const id = row.instance < 0 ? undefined : dictionary.instanceIds[row.instance];
              const value = id === undefined ? undefined : String(id);
              return (
                <tr
                  key={row.instance}
                  data-testid="xlog-summary-instance-row"
                  data-instance-id={value}
                  data-count={row.count}
                >
                  <FilterCell
                    pressed={value !== undefined && hasFilter(query.filters, 'instance', value)}
                    disabled={value === undefined}
                    onToggle={() => {
                      if (value !== undefined) toggle('instance', value);
                    }}
                  >
                    {row.instance < 0 || row.name === null ? (
                      t('xlog:value.unknown', { id: value ?? '?' })
                    ) : (
                      <SafeText value={row.name} />
                    )}
                  </FilterCell>
                  <td>{format.count(row.count)}</td>
                  <td>{format.count(row.errorCount)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>

        <table className="xlog-summary__table" data-testid="xlog-summary-status">
          <caption>
            {t('xlog:summary.status.title')} <SourceLabel server={false} />
          </caption>
          <thead>
            <tr>
              <th scope="col">{t('xlog:summary.status.status')}</th>
              <th scope="col">{t('xlog:summary.status.count')}</th>
            </tr>
          </thead>
          <tbody>
            {statusRows.map((row) => {
              const label = (
                <>
                  <span
                    className={`xlog-summary__dot xlog-summary__dot--${String(row.status)}`}
                    aria-hidden="true"
                  />
                  {format.status(row.status)}
                </>
              );
              return (
                <tr
                  key={row.status}
                  data-testid="xlog-summary-status-row"
                  data-status={row.status}
                  data-count={row.count}
                >
                  {row.status === STATUS_ERROR ? (
                    // Only "error" has an API filter (docs/14 §4.5 error=true).
                    <FilterCell
                      pressed={hasFilter(query.filters, 'error', 'true')}
                      onToggle={() => {
                        toggle('error', 'true');
                      }}
                    >
                      {label}
                    </FilterCell>
                  ) : (
                    <th scope="row" className="xlog-summary__name">
                      {label}
                    </th>
                  )}
                  <td>{format.count(row.count)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>

        <div className="xlog-summary__block" data-testid="xlog-summary-http">
          <table className="xlog-summary__table">
            <caption>
              {t('xlog:summary.http.title')} <SourceLabel server={!httpClient} />
            </caption>
            <thead>
              <tr>
                <th scope="col">{t('xlog:summary.http.code')}</th>
                <th scope="col">{t('xlog:summary.http.count')}</th>
              </tr>
            </thead>
            <tbody>
              {(httpClient
                ? summary.httpStatus.map((r) => ({ code: r.code, count: r.count }))
                : (serverData?.by_status ?? []).map((r) => ({
                    code: r.http_status,
                    count: r.count,
                  }))
              ).map((row) => (
                <tr
                  key={row.code}
                  data-testid="xlog-summary-http-row"
                  data-code={row.code}
                  data-count={row.count}
                >
                  <FilterCell
                    pressed={hasFilter(query.filters, 'status', String(row.code))}
                    disabled={row.code <= 0}
                    onToggle={() => {
                      toggle('status', String(row.code));
                    }}
                  >
                    {format.httpStatus(row.code)}
                  </FilterCell>
                  <td>{format.count(row.count)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {httpClient ? null : serverPending(HTTP_TESTID)}
        </div>

        <div className="xlog-summary__block" data-testid="xlog-summary-errors">
          <table className="xlog-summary__table">
            <caption>
              {t('xlog:summary.errors.title')} <SourceLabel server={!errorsClient} />
            </caption>
            <thead>
              <tr>
                <th scope="col">{t('xlog:summary.errors.errorClass')}</th>
                <th scope="col">{t('xlog:summary.errors.count')}</th>
              </tr>
            </thead>
            <tbody>
              {(errorsClient
                ? summary.errorClasses.map((r): ErrorClassView => ({
                    key: String(r.errorClass),
                    id: null,
                    name: r.name,
                    count: r.count,
                  }))
                : (serverData?.by_error_class ?? []).map((r, i): ErrorClassView => ({
                    key: r.error_class_id ?? fallbackKey(i),
                    id: r.error_class_id,
                    name: r.name,
                    count: r.count,
                  }))
              ).map((row) => (
                <tr
                  key={row.key}
                  data-testid="xlog-summary-error-row"
                  data-error-class-id={row.id ?? undefined}
                  data-count={row.count}
                >
                  <FilterCell
                    pressed={row.id !== null && hasFilter(query.filters, 'error_class', row.id)}
                    disabled={row.id === null}
                    onToggle={() => {
                      if (row.id !== null) toggle('error_class', row.id);
                    }}
                  >
                    <SafeText value={row.name} />
                  </FilterCell>
                  <td>{format.count(row.count)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {!errorsClient && serverState === 'ready' && serverData?.by_error_class.length === 0 ? (
            <p className="xlog-summary__muted" data-testid="xlog-summary-errors-none">
              {t('xlog:summary.errors.none')}
            </p>
          ) : null}
          {errorsClient ? null : serverPending(ERRORS_TESTID)}
        </div>
      </div>
    </section>
  );
}
