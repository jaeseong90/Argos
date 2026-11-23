// SPDX-License-Identifier: Apache-2.0
//
// XLog screen /apps/:appId/xlog (docs/15 §9.3, 05 §2.1, WO-33): the WO-04 canvas on the points API
// (docs/14 §4.5) and the `xlog` topic (docs/14 §5.3), with the chart options toolbar, the f=
// filters (chips in the shell; applied to the scatter at once where the points API can, listed
// otherwise), sampling "Showing N of M", the latency threshold of the response, collection outage
// shading and event markers (alert= emphasised), drag or keyboard selection → `sel=` → summary
// and transaction list (SelectionSummaryPanel, TxListPanel), point click → transaction detail
// with the `ts` hint and the `back` link (ADR-0010 C-206). All shared state is URL state (XLogQ).
import { useQuery } from '@tanstack/react-query';
import {
  lazy,
  Suspense,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation, useNavigate } from 'react-router';

import { excludedCount } from '../../api/collectionHealth';
import { errorInfo } from '../../api/errors';
import { queryKeys } from '../../api/queryKeys';
import { toXlogPointFilters } from '../../api/transactionSearch';
import type { ChartMarker } from '../../charts/chartModel';
import { EmptyState, type StateAction } from '../../components/EmptyState';
import { ErrorState, errorKind } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { PartialBanner } from '../../components/PartialBanner';
import { useRegisterFilterLabels } from '../../routes/filterLabels';
import { useAppId } from '../../routes/navigation';
import { resolveForQuery } from '../../state/timeRange';
import { XLogQ, type Filter, type TimeRange } from '../../state/urlState';
import { useClock, useTimeRange } from '../../state/useTimeRange';
import { useUrlState } from '../../state/useUrlState';
import type { SamplingInfo } from '../../xlog/sampling';
import { DEFAULT_CAPACITY, XLogBuffer } from '../../xlog/XLogBuffer';
import { XLogCanvas, type XLogCanvasHandle, type XLogSelection } from '../../xlog/XLogCanvas';

import { SelectionAnnouncer } from './a11y/SelectionAnnouncer';
import { mergeErrorClassNames, xlogFilterValueLabel, type ErrorClassNames } from './chipLabels';
import { LocalDictionary } from './data/dictionary';
import { eventMarkers, metaOutages } from './data/marks';
import { useXLogPerf } from './data/perfHook';
import { rectFromSelection, selectionFromRect } from './data/selection';
import { formatU64 } from './data/u64';
import { useXLogPoints } from './data/useXLogPoints';
import { fetchAppEvents } from './data/xlogApi';
import { xlogRequest, type XLogRequestQuery } from './data/xlogQuery';
import { transactionDetailUrl } from './list/detailLink';
import type { ListPreviewSource, XLogListChange } from './list/TxListPanel';
import { UnappliedFilters } from './list/UnappliedFilters';
import type { UseXLogLiveOptions, XLogLiveState } from './live/useXLogLive';
import type { SummaryTiming } from './summary/summaryModel';
import { XLogToolbar } from './toolbar/XLogToolbar';

import './xlogPage.css';

/*
 * Parts loaded in their own chunks (docs/15 §14 initial path, WO-33 review r1-new-2): the first
 * frame needs only the skeleton, the toolbar, the canvas and the points hook, which stay static.
 * The selection summary and the transaction list (with its list context, preview rows and CSV
 * export) start loading in the first render (their idle state is part of the screen, and a URL
 * with sel= shows them at once), so they arrive while the points request runs and a first drag
 * never waits for code. The realtime part loads only while live (live=1), the alternative table
 * once there are points. The browser keeps every loaded module, so each loads once per page.
 */
// Alert transition markers and the alert= banner (ADR-0010 C-226 (8)): not on the first frame.
const AlertOverlay = lazy(async () => ({
  default: (await import('../../components/AlertOverlay')).AlertOverlay,
}));
const SelectionSummaryPanel = lazy(async () => ({
  default: (await import('./summary/SelectionSummaryPanel')).SelectionSummaryPanel,
}));
const TxListPanel = lazy(async () => ({
  default: (await import('./list/TxListPanel')).TxListPanel,
}));
const XLogLiveSource = lazy(async () => ({
  default: (await import('./live/XLogLiveSource')).XLogLiveSource,
}));
const XLogAltTable = lazy(async () => ({
  default: (await import('./a11y/XLogAltTable')).XLogAltTable,
}));

const noop = (): void => undefined;
/** The realtime state of a screen that is not live (useXLogLive with enabled = false). */
const LIVE_IDLE: XLogLiveState = {
  status: 'idle',
  error: null,
  meta: null,
  dictionary: new LocalDictionary(),
  window: null,
  paused: false,
  lastFill: null,
  fillError: null,
  polling: false,
  size: 0,
  pause: noop,
  resume: noop,
  stats: () => null,
};
/** Live, until the lazily loaded realtime part reports its first state. */
const LIVE_PENDING: XLogLiveState = { ...LIVE_IDLE, status: 'loading' };

/** A relative range that is not live is resolved again at most once a minute (100,000 points). */
export const STATIC_REFRESH_MS = 60_000;
/**
 * Height of the chart, CSS px: at 1366×768 the chart and, beside the summary's first rows
 * (xlogPage.css), the list's header and first rows are on the first screen right after a drag,
 * without scrolling (docs/15 §9.3, §13; T-E2E-075 measures it). The rest scrolls vertically.
 */
export const CHART_HEIGHT_PX = 340;
/** Live event markers are queried per whole minute, so the window's moves do not re-query. */
const EVENT_STEP_MS = 60_000;
/**
 * The points API answers at most 24 hours (docs/14 §4.5): a longer range (the global 7d or 30d
 * brought from another screen) is not requested; the screen offers shorter ranges instead.
 */
export const XLOG_MAX_RANGE_MS = 24 * 60 * 60_000;

export function XLogPage() {
  const { t } = useTranslation(['common', 'xlog']);
  const appIdOrNull = useAppId();
  const appId = appIdOrNull ?? 0;
  const location = useLocation();
  const navigate = useNavigate();
  const { state, set } = useUrlState(XLogQ);
  const time = useTimeRange(XLogQ);
  const canvasRef = useRef<XLogCanvasHandle>(null);
  const [staticBuffer] = useState(() => new XLogBuffer(DEFAULT_CAPACITY));
  const [liveBuffer] = useState(() => new XLogBuffer(DEFAULT_CAPACITY));
  const [attempt, setAttempt] = useState(0);
  const [selectedCount, setSelectedCount] = useState<number | null>(null);

  const live = state.live;
  const buffer = live ? liveBuffer : staticBuffer;
  const perf = useXLogPerf(buffer, canvasRef);

  // Filters: one vocabulary with the list and the summary (api/transactionSearch.ts). The key
  // keeps the identity while the URL changes elsewhere (selection, colour).
  const filtersKey = JSON.stringify(state.filters);
  const pointFilters = useMemo(
    () => toXlogPointFilters(JSON.parse(filtersKey) as Filter[]),
    [filtersKey],
  );

  // Static data: the absolute range of the URL, or a relative one resolved once a minute.
  const clock = useClock(STATIC_REFRESH_MS);
  const timeKey = JSON.stringify(state.time);
  const staticRange = useMemo(
    () => resolveForQuery(JSON.parse(timeKey) as TimeRange, clock, time.timeZone),
    [timeKey, clock, time.timeZone],
  );
  const rangeTooLong = !live && staticRange.to - staticRange.from > XLOG_MAX_RANGE_MS;
  const requestJson =
    live || pointFilters.empty || appIdOrNull === null || rangeTooLong
      ? null
      : JSON.stringify(xlogRequest(staticRange, state.kind, pointFilters));
  const staticQuery = useMemo(
    () => (requestJson === null ? null : (JSON.parse(requestJson) as XLogRequestQuery)),
    [requestJson],
  );
  // The first full frame after every static load completes its timing (__xlogPerf.lastLoad())
  // and makes the page data-ready: the hook reports the load in the commit that puts its points in
  // the buffer, before the next animation frame.
  const staticData = useXLogPoints({
    appId,
    query: staticQuery,
    buffer: staticBuffer,
    attempt,
    onLoaded: perf.loaded,
  });
  // The realtime state comes from the lazily loaded XLogLiveSource (rendered only while live), which
  // runs useXLogLive with these options (the environment is a topic parameter, never rendered).
  const env = state.env;
  const kind = state.kind;
  const liveOptions = useMemo<UseXLogLiveOptions>(
    () => ({
      enabled: appIdOrNull !== null,
      appId,
      env,
      kind,
      filters: pointFilters,
      buffer: liveBuffer,
      attempt,
    }),
    [appIdOrNull, appId, env, kind, pointFilters, liveBuffer, attempt],
  );
  const [liveReport, setLiveReport] = useState<XLogLiveState | null>(null);
  const liveData = live ? (liveReport ?? LIVE_PENDING) : LIVE_IDLE;

  const status = live ? liveData.status : staticData.status;
  const error = live ? liveData.error : staticData.error;
  const meta = live ? liveData.meta : staticData.meta;
  const dictionary = live ? liveData.dictionary : staticData.dictionary;
  const range = live ? (liveData.window ?? staticRange) : (staticData.range ?? staticRange);

  // Event markers (docs/14 §4.12): the range of the chart, per minute while live.
  const eventsRange = useMemo(
    () =>
      live
        ? {
            from: Math.floor(range.from / EVENT_STEP_MS) * EVENT_STEP_MS,
            to: Math.ceil(range.to / EVENT_STEP_MS) * EVENT_STEP_MS,
          }
        : { from: range.from, to: range.to },
    [live, range.from, range.to],
  );
  const events = useQuery({
    queryKey: queryKeys.screen('xlog-events', { appId, range: eventsRange }),
    queryFn: ({ signal }) => fetchAppEvents(appId, eventsRange, signal),
    enabled: appIdOrNull !== null && !rangeTooLong,
    staleTime: 60_000,
  });
  // Alert transitions of the same range (docs/15 §4.3 item 4, ADR-0010 C-226 (8)), reported by
  // the lazily loaded AlertOverlay: drawn with the event markers, `alert=` emphasised.
  const [alertMarkers, setAlertMarkers] = useState<readonly ChartMarker[]>([]);
  const alertRequest = useMemo(
    () => ({ appId, env: env ?? null, range: eventsRange }),
    [appId, env, eventsRange],
  );
  const markers = useMemo(
    () => [...eventMarkers(events.data?.events ?? []), ...alertMarkers],
    [events.data, alertMarkers],
  );
  const outages = useMemo(() => metaOutages(meta), [meta]);

  const sampling = useMemo<SamplingInfo | null>(
    () =>
      typeof meta?.shown === 'number' && typeof meta.total === 'number'
        ? { shown: meta.shown, total: meta.total }
        : null,
    [meta],
  );
  const threshold = typeof meta?.slow_threshold_ms === 'number' ? meta.slow_threshold_ms : null;
  const selection = useMemo(
    () => (state.sel === undefined ? null : rectFromSelection(state.sel)),
    [state.sel],
  );
  const back = `${location.pathname}${location.search}`;
  // The list previews a new selection from these points while its server search runs (C-204):
  // only when they are the points of the current range, kind and filters, all applied by the
  // points API.
  const previewApplicable = status === 'ready' && pointFilters.unapplied.length === 0;
  const previewSampled = sampling !== null && sampling.shown < sampling.total;
  // The sampling facts of the response decide which preview rows are exactly the server's first
  // rows (previewRows.ts exactPreview): without them a sampled response previews nothing and the
  // list waits for the search (ADR-0003 C-36, review fe-perf-2).
  const previewTruncated = typeof meta?.truncated === 'boolean' ? meta.truncated : undefined;
  const listPreview = useMemo<ListPreviewSource>(
    () => ({
      buffer,
      dictionary,
      applicable: previewApplicable,
      sampled: previewSampled,
      truncated: previewTruncated,
      slowThresholdMs: threshold,
    }),
    [buffer, dictionary, previewApplicable, previewSampled, previewTruncated, threshold],
  );

  const dispatch = time.dispatch;
  const { selectionStarted } = perf;
  // While live the chart shows the 5-minute live window, not the URL range (docs/15 §7.5): a drag
  // freezes that window (whole milliseconds), so the chart keeps its scale and the selection.
  const liveWindow = live ? liveData.window : null;
  const frozenFrom = liveWindow === null ? null : Math.floor(liveWindow.from);
  const frozenTo = liveWindow === null ? null : Math.ceil(liveWindow.to);
  const onSelect = useCallback(
    (next: XLogSelection) => {
      const sel = selectionFromRect(next.rect);
      if (sel === null) {
        return;
      }
      selectionStarted(next.queryMs ?? null, next);
      // A drag over the past while live turns live off and offers "resume live" (WO-30 reducer).
      dispatch(
        frozenFrom === null || frozenTo === null
          ? { type: 'xlogDrag', sel }
          : { type: 'xlogDrag', sel, frozen: { from: frozenFrom, to: frozenTo } },
      );
    },
    [dispatch, selectionStarted, frozenFrom, frozenTo],
  );
  const onPointClick = useCallback(
    (index: number) => {
      const txId = formatU64(buffer.txIdHi[index] ?? 0, buffer.txIdLo[index] ?? 0);
      const ts = buffer.ts[index] ?? 0;
      void navigate(transactionDetailUrl(appId, txId, ts, back));
    },
    [appId, back, buffer, navigate],
  );

  const { selectionComputed } = perf;
  const onComputed = useCallback(
    (timing: SummaryTiming) => {
      setSelectedCount(timing.points);
      selectionComputed(timing);
    },
    [selectionComputed],
  );

  // The shell's filter chips name the f= keys (service, instance, error class) instead of showing
  // them (chipLabels.ts, routes/filterLabels.tsx); error class names come from server summaries.
  const [errorClassNames, setErrorClassNames] = useState<ErrorClassNames>(() => new Map());
  const onErrorClassNames = useCallback(
    (names: readonly { readonly id: string | null; readonly name: string | null }[]) => {
      setErrorClassNames((known) => mergeErrorClassNames(known, names));
    },
    [],
  );
  const chipLabel = useMemo(
    () => xlogFilterValueLabel(dictionary, errorClassNames),
    [dictionary, errorClassNames],
  );
  useRegisterFilterLabels(chipLabel);

  const onListChange = (change: XLogListChange): void => {
    set(change);
  };

  // A range over the points API's 24 hours drops the points of an earlier range: the summary and
  // the alternative table must not count points the chart no longer shows.
  useEffect(() => {
    if (rangeTooLong) {
      staticBuffer.clear();
    }
  }, [rangeTooLong, staticBuffer]);
  // "Last 24 hours of this range" and "Last 1 hour" (05 §2.0 next action of an error state).
  const rangeEnd = staticRange.to;
  const rangeActions = useMemo<StateAction[]>(
    () => [
      {
        label: t('xlog:page.rangeLimit.last24h'),
        onClick: () => {
          dispatch({ type: 'absolute', from: rangeEnd - XLOG_MAX_RANGE_MS, to: rangeEnd });
        },
        'data-testid': 'xlog-range-last24h',
      },
      {
        label: t('xlog:page.rangeLimit.last1h'),
        onClick: () => {
          dispatch({ type: 'preset', range: '1h' });
        },
        'data-testid': 'xlog-range-last1h',
      },
    ],
    [t, dispatch, rangeEnd],
  );

  const hasData = !rangeTooLong && (buffer.size > 0 || status === 'ready');
  const noPoints = !rangeTooLong && status === 'ready' && buffer.size === 0;
  // "N requests excluded in this range" for an empty chart (D-19, docs/15 §6.7, 05 §2.0-8); null
  // from the server means no source (not shown), never 0.
  const excludedFrom = new Date(range.from).toISOString();
  const excludedTo = new Date(range.to).toISOString();
  const excluded = useQuery({
    queryKey: ['xlog-excluded', appId, excludedFrom, excludedTo] as const,
    queryFn: ({ signal }) => excludedCount(appId, excludedFrom, excludedTo, signal),
    enabled: noPoints && !live && appIdOrNull !== null,
    staleTime: 60_000,
  });
  const excludedTotal = noPoints ? (excluded.data ?? null) : null;

  let chart: ReactNode;
  if (pointFilters.empty) {
    chart = (
      <EmptyState
        title={t('xlog:page.empty.title')}
        description={t('xlog:page.empty.impossible')}
        onClearFilters={() => {
          set({ filters: [] });
        }}
        data-testid="xlog-empty"
      />
    );
  } else if (rangeTooLong) {
    chart = (
      <EmptyState
        title={t('xlog:page.rangeLimit.title', { hours: XLOG_MAX_RANGE_MS / 3_600_000 })}
        description={t('xlog:page.rangeLimit.description')}
        actions={rangeActions}
        data-testid="xlog-range-limit"
      />
    );
  } else if (status === 'error' && !hasData) {
    const info = errorInfo(error);
    chart = (
      <>
        <ErrorState
          error={info}
          onRetry={() => {
            setAttempt((n) => n + 1);
          }}
          onNarrowRange={() => {
            dispatch({ type: 'preset', range: '1h' });
          }}
          onClearFilters={() => {
            set({ filters: [] });
          }}
          data-testid="xlog-error"
        />
        {errorKind(info) === 'rangeInvalid' ? (
          // The server refused the range: the same shorter ranges as a range over 24 hours.
          <div className="state__actions xlog-page__range-actions" data-testid="xlog-range-actions">
            {rangeActions.map((action) => (
              <button
                key={action['data-testid']}
                type="button"
                className="state__button"
                onClick={action.onClick}
                data-testid={action['data-testid']}
              >
                {action.label}
              </button>
            ))}
          </div>
        ) : null}
      </>
    );
  } else if (!hasData) {
    chart = <LoadingState variant="chart" data-testid="xlog-loading" />;
  } else {
    chart = (
      <XLogCanvas
        ref={canvasRef}
        buffer={buffer}
        dictionary={dictionary}
        from={range.from}
        to={range.to}
        colorBy={state.color}
        yScale={state.y === 'log' ? 'log' : 'linear'}
        threshold={threshold}
        sampling={sampling}
        selection={selection}
        incremental={live}
        height={CHART_HEIGHT_PX}
        outages={outages}
        markers={markers}
        emphasizedMarkerId={state.alert ?? null}
        keyboardSelection
        onSelect={onSelect}
        onPointClick={onPointClick}
        onFrame={perf.onFrame}
      />
    );
  }

  return (
    <section
      className="xlog-page"
      aria-labelledby="xlog-page-title"
      data-testid="xlog-page"
      data-state={pointFilters.empty ? 'empty' : rangeTooLong ? 'rangeLimit' : status}
      data-ready={status === 'ready' && (live || perf.ready) ? 'true' : 'false'}
      data-point-count={buffer.size}
      data-live={live ? 'true' : 'false'}
      data-range-from={Math.round(range.from)}
      data-range-to={Math.round(range.to)}
    >
      <header className="xlog-page__header">
        <h1 id="xlog-page-title" className="xlog-page__title">
          {t('xlog:page.title')}
        </h1>
        <XLogToolbar
          colorBy={state.color}
          onColorBy={(color) => {
            set({ color }, { replace: true });
          }}
          yAxis={state.y}
          onYAxis={(y) => {
            set({ y }, { replace: true });
          }}
          threshold={threshold}
          live={live}
          liveAvailable={time.liveAvailable}
          onLive={(next) => {
            dispatch({ type: 'setLive', live: next });
          }}
          paused={liveData.paused}
          onPause={liveData.pause}
          onResume={liveData.resume}
          onSelectVisible={() => {
            canvasRef.current?.selectTimeBand(range.from, range.to);
          }}
          selectVisibleDisabled={buffer.size === 0}
        />
      </header>
      <UnappliedFilters
        unapplied={pointFilters.unapplied}
        title={t('xlog:page.unapplied')}
        data-testid="xlog-chart-unapplied"
      />
      <PartialBanner meta={meta} data-testid="xlog-partial" />
      <Suspense fallback={null}>
        <AlertOverlay
          request={alertRequest}
          enabled={appIdOrNull !== null && !rangeTooLong}
          alertId={state.alert}
          onMarkers={setAlertMarkers}
        />
      </Suspense>
      {noPoints ? (
        <EmptyState
          compact
          title={t('xlog:page.empty.title')}
          description={t('xlog:page.empty.description')}
          onWidenRange={() => {
            dispatch({ type: 'zoomOut' });
          }}
          onClearFilters={
            state.filters.length > 0
              ? () => {
                  set({ filters: [] });
                }
              : undefined
          }
          excluded={
            excludedTotal !== null && excludedTotal > 0
              ? {
                  count: excludedTotal,
                  onOpen: () => {
                    void navigate(`/apps/${String(appId)}/dashboard`);
                  },
                }
              : null
          }
          data-testid="xlog-empty"
        />
      ) : null}
      {staticData.refreshing && !live ? (
        <p className="xlog-page__refreshing" role="status" data-testid="xlog-refreshing">
          {t('xlog:page.refreshing')}
        </p>
      ) : null}
      {live ? (
        // The live notes (failed gap fill, REST polling) are rendered by the realtime part.
        <Suspense fallback={null}>
          <XLogLiveSource options={liveOptions} onState={setLiveReport} />
        </Suspense>
      ) : null}
      <div className="xlog-page__chart">{chart}</div>
      {hasData ? (
        <Suspense fallback={null}>
          <XLogAltTable buffer={buffer} from={range.from} to={range.to} />
        </Suspense>
      ) : null}
      <SelectionAnnouncer count={state.sel === undefined ? null : selectedCount} />
      <div className="xlog-page__results">
        <Suspense fallback={null}>
          <SelectionSummaryPanel
            appId={appId}
            query={{ time: state.time, kind: state.kind, filters: state.filters, sel: state.sel }}
            buffer={buffer}
            dictionary={dictionary}
            sampling={sampling}
            onFilter={(filters) => {
              set({ filters });
            }}
            onClear={() => {
              dispatch({ type: 'clearSelection' });
            }}
            onComputed={onComputed}
            announce={false}
            onErrorClassNames={onErrorClassNames}
          />
          <TxListPanel
            appId={appId}
            query={{
              time: state.time,
              kind: state.kind,
              filters: state.filters,
              sort: state.sort,
              sel: state.sel,
              cols: state.cols,
            }}
            onQueryChange={onListChange}
            back={back}
            preview={listPreview}
          />
        </Suspense>
      </div>
    </section>
  );
}
