// SPDX-License-Identifier: Apache-2.0
//
// Application dashboard /apps/:appId/dashboard (docs/15 §9.2, 05 §2.3, F-U01, WO-32): the summary
// tiles with the delta basis `tileRef`, the nine time series with the baseline toggle, event
// markers (alert= emphasised) and outage shading, and below them the instance and collection
// parts (sections/DashboardSections.tsx). Data: `GET /apps/{appId}/dashboard` for the URL state
// (one request, or two when the tile basis needs another baseline parameter; data/plan.ts) and,
// in live mode or kiosk on a range that ends now, the `counter` topic every 5 s on top of it
// (data/useDashboard.ts). Drag on a chart sets the global absolute range (live off); a point click
// opens the XLog around that time. All shared state is URL state (DashboardQ).
import { useCallback, useMemo, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';

import { mergeMarkers } from '../../api/alertMarkers';
import { errorInfo } from '../../api/errors';
import { untrusted, type Untrusted } from '../../api/untrusted';
import type { ChartMarker, TimeRange as ChartRange } from '../../charts/chartModel';
import { AlertOverlay } from '../../components/AlertOverlay';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import type { FilterValueLabel } from '../../components/FilterChipBar';
import { LoadingState } from '../../components/LoadingState';
import { PartialBanner } from '../../components/PartialBanner';
import { SafeText } from '../../components/SafeText';
import { useRegisterFilterLabels } from '../../routes/filterLabels';
import { useAppId } from '../../routes/navigation';
import { endsNow } from '../../state/timeRange';
import { DashboardQ } from '../../state/urlState';
import { useTimeRange } from '../../state/useTimeRange';
import { useUrlState } from '../../state/useUrlState';

import { DashboardCharts, panelElementId } from './charts/DashboardCharts';
import type { PanelId } from './charts/panels';
import { xlogAtRange } from './data/links';
import { useDashboard } from './data/useDashboard';
import { DashboardSections } from './sections/DashboardSections';
import { Segmented } from './Segmented';
import { DashboardTiles } from './tiles/DashboardTiles';

import './dashboardPage.css';

const OP_SYMBOLS: Readonly<Record<string, string>> = {
  eq: '=',
  ne: '≠',
  gt: '>',
  gte: '≥',
  lt: '<',
  lte: '≤',
  contains: '∋',
};

export function DashboardPage() {
  const { t } = useTranslation(['common', 'dashboard']);
  const appIdOrNull = useAppId();
  const appId = appIdOrNull ?? 0;
  const navigate = useNavigate();
  const { state, set } = useUrlState(DashboardQ);
  const time = useTimeRange(DashboardQ);
  const dispatch = time.dispatch;

  // Live mode, or the kiosk layout (status board, C-189 (3)), on a range that ends now.
  const realtime = appIdOrNull !== null && (state.live || state.kiosk) && endsNow(state.time);
  const data = useDashboard({ appId: appIdOrNull, state, timeZone: time.timeZone, realtime });
  const model = data.model;

  // The charts' window: the request range; while live it ends at the newest of the clock and the
  // last applied push, so the window moves between the minute refreshes.
  const queryFrom = data.queryRange.from;
  const queryTo = data.queryRange.to;
  const displayTo = realtime ? Math.max(queryTo, time.resolved.to, data.liveT ?? 0) : queryTo;
  const range = useMemo<ChartRange>(
    () => ({ from: displayTo - (queryTo - queryFrom), to: displayTo }),
    [displayTo, queryFrom, queryTo],
  );

  // Alert transitions of the range as chart markers (docs/15 §4.3 item 4, ADR-0010 C-226 (8)):
  // `alert=` emphasises its marker and the banner summarises that event.
  const [alertMarkers, setAlertMarkers] = useState<readonly ChartMarker[]>([]);
  const alertEnv = state.env;
  const alertRequest = useMemo(
    () => ({ appId, env: alertEnv ?? null, range: data.queryRange }),
    [appId, alertEnv, data.queryRange],
  );
  const modelMarkers = model?.markers;
  const markers = useMemo(
    () => mergeMarkers(modelMarkers ?? [], alertMarkers),
    [modelMarkers, alertMarkers],
  );

  const onZoom = useCallback(
    (from: number, to: number) => {
      // A drag sets the global absolute range and turns live off (WO-30 reducer, 05 §2.0-1/-2).
      dispatch({ type: 'chartDrag', from, to });
    },
    [dispatch],
  );
  const kind = state.kind;
  const env = state.env;
  const onOpenXLog = useCallback(
    (window: ChartRange) => {
      void navigate(xlogAtRange(appId, window, { kind, env }));
    },
    [appId, kind, env, navigate],
  );
  const onOpenPanel = useCallback((panel: PanelId) => {
    // Focus moves to the panel (keyboard users land on it) and the browser scrolls it into view.
    document.getElementById(panelElementId(panel))?.focus();
  }, []);
  const filters = state.filters;
  const onFilterInstance = useCallback(
    (instanceId: number, op: 'eq' | 'ne' = 'eq') => {
      // The same id with either operator is replaced, so include after exclude flips it
      // (05 §2.0-4). Exclusion has no API parameter: the page lists it as not applied.
      const value = String(instanceId);
      const others = filters.filter(
        (filter) =>
          !(
            filter.field === 'instance' &&
            (filter.op === 'eq' || filter.op === 'ne') &&
            filter.value === value
          ),
      );
      set({ filters: [...others, { field: 'instance', op, value: untrusted(value) }] });
    },
    [filters, set],
  );

  // The shell's chips name f.instance ids with the instance names of the response.
  const instanceNames = useMemo(() => {
    const names = new Map<string, Untrusted>();
    for (const instance of model?.instances ?? []) {
      names.set(String(instance.instance_id), instance.name);
    }
    return names;
  }, [model?.instances]);
  const chipLabel = useMemo<FilterValueLabel>(
    () => (filter) =>
      filter.field === 'instance' ? (instanceNames.get(filter.value) ?? null) : null,
    [instanceNames],
  );
  useRegisterFilterLabels(chipLabel);

  const empty = model !== null && model.count === 0;
  const pageState = data.status === 'ready' ? (empty ? 'empty' : 'ready') : data.status;

  let body: ReactNode;
  if (data.status === 'loading' || model === null) {
    body =
      data.status === 'error' ? (
        <ErrorState
          error={errorInfo(data.error)}
          onRetry={data.retry}
          onNarrowRange={() => {
            dispatch({ type: 'preset', range: '1h' });
          }}
          onClearFilters={
            filters.length > 0
              ? () => {
                  set({ filters: [] });
                }
              : undefined
          }
          data-testid="dashboard-error"
        />
      ) : (
        <div className="dashboard-page__loading" data-testid="dashboard-loading">
          <LoadingState variant="tiles" />
          <LoadingState variant="chart" />
        </div>
      );
  } else {
    const excluded = model.collection.excluded;
    body = (
      <>
        {data.error !== null && data.error !== undefined ? (
          <ErrorState
            compact
            error={errorInfo(data.error)}
            onRetry={data.retry}
            data-testid="dashboard-refresh-error"
          />
        ) : null}
        {empty ? (
          <div className="dashboard-page__empty">
            <EmptyState
              compact
              title={t('dashboard:page.empty.title')}
              description={t('dashboard:page.empty.description')}
              onWidenRange={() => {
                dispatch({ type: 'zoomOut' });
              }}
              onClearFilters={
                filters.length > 0
                  ? () => {
                      set({ filters: [] });
                    }
                  : undefined
              }
              data-testid="dashboard-empty"
            />
            {excluded !== null && excluded > 0 ? (
              // Requests the agent's exclusion rules turned away (D-19, 05 §2.0-8); the collection
              // panel lists the reasons (the ranking screen with its link comes with WO-39).
              <p className="dashboard-page__excluded" data-testid="dashboard-empty-excluded">
                {t('dashboard:page.empty.excluded', { count: excluded })}
              </p>
            ) : null}
          </div>
        ) : null}
        {data.tilesPending ? (
          <LoadingState variant="tiles" data-testid="dashboard-tiles-loading" />
        ) : (
          <DashboardTiles model={model} onOpenPanel={onOpenPanel} />
        )}
        <AlertOverlay
          request={alertRequest}
          enabled={appIdOrNull !== null}
          alertId={state.alert}
          onMarkers={setAlertMarkers}
        />
        <DashboardCharts
          model={model}
          range={range}
          markers={markers}
          outages={model.outages}
          emphasizedMarkerId={state.alert ?? null}
          source={model.meta.source ?? null}
          onZoom={onZoom}
          onOpenXLog={onOpenXLog}
        />
        <DashboardSections
          appId={appId}
          model={model}
          range={data.queryRange}
          kind={state.kind}
          realtime={realtime}
          onFilterInstance={onFilterInstance}
        />
      </>
    );
  }

  return (
    <section
      className="dashboard-page"
      aria-labelledby="dashboard-page-title"
      data-testid="dashboard-page"
      data-state={pageState}
      data-live={realtime ? 'true' : 'false'}
      data-live-ts={data.liveTs ?? undefined}
      data-request-count={data.plan === null ? 0 : data.plan.tiles === null ? 1 : 2}
      data-range-from={Math.round(range.from)}
      data-range-to={Math.round(range.to)}
    >
      <header className="dashboard-page__header">
        <h1 id="dashboard-page-title" className="dashboard-page__title">
          {t('dashboard:page.title')}
        </h1>
        <div className="dashboard-page__toggles">
          <Segmented
            label={t('dashboard:tileRef.label')}
            value={state.tileRef}
            options={[
              { value: 'prev', label: t('dashboard:tileRef.prev') },
              { value: '7d', label: t('dashboard:tileRef.sevenDays') },
            ]}
            onChange={(tileRef) => {
              set({ tileRef }, { replace: true });
            }}
            data-testid="dashboard-tile-ref"
          />
          <Segmented
            label={t('dashboard:baseline.label')}
            value={state.baseline}
            options={[
              { value: 'none', label: t('dashboard:baseline.none') },
              { value: '1d', label: t('dashboard:baseline.oneDay') },
              { value: '7d', label: t('dashboard:baseline.sevenDays') },
            ]}
            onChange={(baseline) => {
              set({ baseline }, { replace: true });
            }}
            data-testid="dashboard-baseline"
          />
        </div>
      </header>
      {data.unapplied.length > 0 ? (
        <p className="dashboard-page__note" data-testid="dashboard-unapplied">
          {t('dashboard:page.unapplied')}{' '}
          {data.unapplied.map((filter, index) => (
            <span
              key={`${filter.field}:${filter.op}:${String(index)}`}
              className="dashboard-page__filter"
            >
              <SafeText value={filter.field} showTitle={false} />{' '}
              {OP_SYMBOLS[filter.op] ?? filter.op} <SafeText value={filter.value} mono />
            </span>
          ))}
        </p>
      ) : null}
      {model === null ? null : <PartialBanner meta={model.meta} data-testid="dashboard-partial" />}
      {data.polling ? (
        <p className="dashboard-page__note" role="status" data-testid="dashboard-polling">
          {t('dashboard:page.polling')}
        </p>
      ) : null}
      {data.refreshing && !realtime ? (
        <p className="dashboard-page__note" role="status" data-testid="dashboard-refreshing">
          {t('dashboard:page.refreshing')}
        </p>
      ) : null}
      {body}
    </section>
  );
}
