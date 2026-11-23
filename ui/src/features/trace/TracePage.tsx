// SPDX-License-Identifier: Apache-2.0
//
// Distributed trace /traces/:traceId (docs/15 §9.5, 05 §2.8, docs/14 §4.7, F-U03): the tree of the
// trace's transactions on the left and their timeline on the right (DOM virtual list), each node
// with app (env), service, instance, time, status, error and version; the caller's HTTP step and
// the callee both drawn with the network / wait part between them (D-05); missing nodes of three
// kinds (external calls to uninstrumented systems, "not collected yet [query again]", the D-07
// reason of a profile that was not captured); the critical path; node → transaction detail with
// the ts hint, app → that app's dashboard for the same time range; copy the trace id, open logs
// (when a log link is known), export the loaded trace as JSON (ADR-0010 C-207 (1)); the searched
// range from the `ts` hint and `window` with 7- and 30-day searches; a partial banner beyond 500
// transactions. Nothing is reconstructed: every value is the server's.
import { useQueryClient } from '@tanstack/react-query';
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, useParams } from 'react-router';

import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { SafeText } from '../../components/SafeText';
import { TraceQ } from '../../state/urlState';
import { useUrlState } from '../../state/useUrlState';

import { TraceActions } from './components/TraceActions';
import { TraceLegend, TraceNotices, TraceSummary } from './components/TraceSummary';
import { TraceTimeline, type TraceLinks } from './components/TraceTimeline';
import { TraceWindowBar, WindowSearch } from './components/TraceWindowBar';
import { saveTraceJson } from './data/traceApi';
import { useTrace } from './data/useTrace';
import { cachedLogLink } from './model/cachedLogLink';
import { appDashboardHref, traceRange, transactionDetailHref } from './model/traceLinks';
import { buildTraceTree } from './model/traceTree';

import './trace.css';

export function TracePage() {
  const { t } = useTranslation(['common', 'trace']);
  const { traceId = '' } = useParams();
  const navigate = useNavigate();
  const { state: url, set } = useUrlState(TraceQ);
  const { state, refetch, fetching } = useTrace(traceId, url.ts, url.window);
  const trace = state.status === 'success' ? state.trace : null;
  const tree = useMemo(() => (trace === null ? null : buildTraceTree(trace)), [trace]);
  const queryClient = useQueryClient();
  const logsHref = cachedLogLink(queryClient, tree?.root?.node.tx_id ?? null);

  const searchWindow = (window: '7d' | '30d'): void => {
    set({ window, ts: undefined });
  };
  const onNavigate = (href: string): void => {
    void navigate(href);
  };

  let body;
  switch (state.status) {
    case 'pending':
      body = <LoadingState data-testid="loading-state" />;
      break;
    case 'invalid':
    case 'not_found': {
      const widen = state.status === 'not_found' && state.reason === 'not_in_window';
      body = (
        <EmptyState
          data-testid="trace-not-found"
          title={t('trace:notFound.title')}
          description={
            state.status === 'invalid'
              ? t('trace:notFound.invalidId')
              : widen
                ? t('trace:notFound.notInWindow')
                : t('trace:notFound.other')
          }
        />
      );
      if (widen) {
        body = (
          <>
            {body}
            <div className="trace-not-found__actions">
              <WindowSearch
                current={url.window}
                onSearch={searchWindow}
                testIds={{ week: 'trace-search-week', month: 'trace-search-month' }}
              />
            </div>
          </>
        );
      }
      break;
    }
    case 'error':
      body = <ErrorState error={state.info} onRetry={refetch} />;
      break;
    case 'success': {
      const loaded = tree ?? buildTraceTree(state.trace);
      const range = traceRange(state.trace.window);
      const links: TraceLinks = {
        detail: (appId, txId, ts, tab) => transactionDetailHref(appId, txId, ts, url, tab),
        dashboard: (appId) => appDashboardHref(appId, range, url),
      };
      body = (
        <>
          <TraceActions
            traceId={state.trace.trace_id}
            logsHref={logsHref}
            onExport={() => saveTraceJson(state.response)}
            onQueryAgain={refetch}
            fetching={fetching}
          />
          <TraceWindowBar
            range={state.trace.window}
            window={url.window}
            hintMs={url.ts}
            onSearch={searchWindow}
          />
          <TraceSummary tree={loaded} />
          <TraceNotices
            tree={loaded}
            meta={state.meta}
            onQueryAgain={refetch}
            fetching={fetching}
          />
          <TraceLegend />
          {loaded.roots.length === 0 ? (
            <EmptyState
              data-testid="trace-empty"
              title={t('trace:notFound.title')}
              description={t('trace:notFound.other')}
            />
          ) : (
            <TraceTimeline
              tree={loaded}
              links={links}
              onNavigate={onNavigate}
              onQueryAgain={refetch}
              fetching={fetching}
            />
          )}
        </>
      );
      break;
    }
  }

  return (
    <section
      className="trace-page"
      data-testid="trace-page"
      data-state={state.status}
      aria-busy={fetching}
    >
      <h1 className="trace-page__title">
        {t('trace:page.title')} <SafeText value={traceId} mono data-testid="trace-id" />
        {fetching && state.status === 'success' ? (
          <span className="trace-page__refreshing" data-testid="trace-refreshing">
            {t('trace:page.refreshing')}
          </span>
        ) : null}
      </h1>
      {body}
    </section>
  );
}
