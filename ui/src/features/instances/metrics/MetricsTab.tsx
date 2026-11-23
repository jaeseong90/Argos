// SPDX-License-Identifier: Apache-2.0
//
// The metrics tab of the instance detail (05 §2.7-2, docs/15 §9.8): the panels of panels.ts on
// TimeSeriesChart with one cursor for all (syncKey 'instance'), drag → global absolute range (live
// off), event markers (application deploys, this instance's start/stop/config) and the comparison
// baseline: the same window seven days earlier, fetched by the browser and moved onto the current
// grid (docs/14 §4.10 has no baseline parameter). A panel the instance never reported says "Not
// measured" instead of drawing empty lines. Each panel keeps a short summary of its series values
// (`instance-chart-<panel>-values`, data-summary) and its baseline series count
// (`data-baseline-series`) for tests and tooling. The baseline toggle is the `baseline=1` URL key.
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';

import { errorInfo } from '../../../api/errors';
import { useMetricNames, useMetricSeries, type InstanceRange } from '../../../api/instances';
import { untrusted } from '../../../api/untrusted';
import type { ChartMarker, TimeRange as ChartRange } from '../../../charts/chartModel';
import { TimeSeriesChart } from '../../../charts/TimeSeriesChart';
import { EmptyState } from '../../../components/EmptyState';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';
import { useFormat } from '../../../i18n/format';
import { buildUrl, carriedState, defaultState, InstanceQ, XLogQ } from '../../../state/urlState';
import { useUrlState } from '../../../state/useUrlState';
import type { CarriedState } from '../links';

import {
  BASELINE_OFFSET_MS,
  buildPanels,
  chartNames,
  gridMs,
  seriesSummary,
  shiftBaseline,
  type PanelId,
  type PanelNote,
  type SeriesLabelKind,
} from './panels';

/** Plot height of an instance panel. */
export const INSTANCE_PANEL_HEIGHT_PX = 150;
/** Cursor sync group of the instance charts. */
export const INSTANCE_SYNC_KEY = 'instance';

/** DOM id of a panel's title. */
function panelTitleId(panel: PanelId): string {
  return `instance-panel-${panel}-title`;
}

export interface MetricsTabProps {
  readonly appId: number;
  readonly instanceId: number;
  readonly range: InstanceRange;
  readonly markers: readonly ChartMarker[];
  readonly current: CarriedState;
  readonly onZoom: (from: number, to: number) => void;
  /** Widens the global range (empty state action). */
  readonly onWidenRange: () => void;
}

export function MetricsTab({
  appId,
  instanceId,
  range,
  markers,
  current,
  onZoom,
  onWidenRange,
}: MetricsTabProps) {
  const { t } = useTranslation(['common', 'instance']);
  const format = useFormat();
  const navigate = useNavigate();
  // The baseline toggle is URL state (`baseline=1`, 05 §2.0): a deep link restores it.
  const { state: urlState, set: setUrlState } = useUrlState(InstanceQ);
  const baselineOn = urlState.baseline;

  const namesQuery = useMetricNames(instanceId);
  const reported = namesQuery.data?.data;
  const names = useMemo(() => chartNames(reported ?? []), [reported]);
  const seriesQuery = useMetricSeries(instanceId, names, range);
  const baselineRange = useMemo<InstanceRange>(
    () => ({ from: range.from - BASELINE_OFFSET_MS, to: range.to - BASELINE_OFFSET_MS }),
    [range.from, range.to],
  );
  const baselineQuery = useMetricSeries(instanceId, names, baselineRange, baselineOn);

  const answer = seriesQuery.data ?? null;
  const ts = useMemo(() => gridMs(answer?.data), [answer]);
  const baselineData = baselineQuery.data?.data;
  const shifted = useMemo(
    () => (baselineOn ? shiftBaseline(ts, baselineData, BASELINE_OFFSET_MS) : null),
    [baselineOn, ts, baselineData],
  );

  const label = (kind: SeriesLabelKind, name: string): string => {
    switch (kind) {
      case 'memoryUsed':
        return t('instance:series.memoryUsed', { name });
      case 'memoryMax':
        return t('instance:series.memoryMax', { name });
      case 'poolUsed':
        return t('instance:series.poolUsed', { name });
      case 'poolMax':
        return t('instance:series.poolMax', { name });
      case 'gcCount':
        return t('instance:series.gcCount', { name });
      case 'gcPause':
        return t('instance:series.gcPause', { name });
      case 'threadsLive':
        return t('instance:series.threadsLive');
      case 'threadsDaemon':
        return t('instance:series.threadsDaemon');
      case 'threadsPeak':
        return t('instance:series.threadsPeak');
      case 'threadsVirtualStarted':
        return t('instance:series.threadsVirtualStarted');
      case 'classesLoaded':
        return t('instance:series.classesLoaded');
      case 'classesUnloaded':
        return t('instance:series.classesUnloaded');
      case 'cpuProcess':
        return t('instance:series.cpuProcess');
      case 'cpuSystem':
        return t('instance:series.cpuSystem');
      case 'fdOpen':
        return t('instance:series.fdOpen');
      case 'fdMax':
        return t('instance:series.fdMax');
      case 'tomcatBusy':
        return t('instance:series.tomcatBusy', { name });
      case 'tomcatThreads':
        return t('instance:series.tomcatThreads', { name });
      case 'tomcatThreadsMax':
        return t('instance:series.tomcatThreadsMax', { name });
      case 'tomcatConnections':
        return t('instance:series.tomcatConnections', { name });
      case 'tomcatConnectionsMax':
        return t('instance:series.tomcatConnectionsMax', { name });
      case 'jdbcActive':
        return t('instance:series.jdbcActive', { name });
      case 'jdbcIdle':
        return t('instance:series.jdbcIdle', { name });
      case 'jdbcWaiting':
        return t('instance:series.jdbcWaiting', { name });
      case 'jdbcMax':
        return t('instance:series.jdbcMax', { name });
      case 'systemMemoryUsed':
        return t('instance:series.systemMemoryUsed');
      case 'systemMemoryTotal':
        return t('instance:series.systemMemoryTotal');
      case 'containerMemoryLimit':
        return t('instance:series.containerMemoryLimit');
      case 'diskUsed':
        return t('instance:series.diskUsed', { name });
      case 'diskTotal':
        return t('instance:series.diskTotal', { name });
      case 'networkReceive':
        return t('instance:series.networkReceive', { name });
      case 'networkTransmit':
        return t('instance:series.networkTransmit', { name });
    }
  };
  const title = (panel: PanelId): string => {
    switch (panel) {
      case 'heap':
        return t('instance:charts.heap');
      case 'memory-pools':
        return t('instance:charts.memoryPools');
      case 'gc-count':
        return t('instance:charts.gcCount');
      case 'gc-pause':
        return t('instance:charts.gcPause');
      case 'threads':
        return t('instance:charts.threads');
      case 'classes':
        return t('instance:charts.classes');
      case 'cpu':
        return t('instance:charts.cpu');
      case 'fd':
        return t('instance:charts.fd');
      case 'tomcat':
        return t('instance:charts.tomcat');
      case 'jdbc-pools':
        return t('instance:charts.jdbcPools');
      case 'system-memory':
        return t('instance:charts.systemMemory');
      case 'disk':
        return t('instance:charts.disk');
      case 'network':
        return t('instance:charts.network');
    }
  };

  const panels = buildPanels(reported ?? [], answer, label, shifted);

  const onOpenXLog = (window: ChartRange): void => {
    void navigate(
      buildUrl(`/apps/${String(appId)}/xlog`, XLogQ, {
        ...defaultState(XLogQ),
        ...carriedState({ kind: current.kind, env: current.env }),
        time: { kind: 'absolute', from: Math.floor(window.from), to: Math.ceil(window.to) },
        live: false,
        filters: [{ field: 'instance', op: 'eq', value: untrusted(String(instanceId)) }],
        color: 'instance',
      }),
    );
  };

  const noteText = (note: PanelNote, limit: number | null): string => {
    switch (note) {
      case 'notMeasured':
        return t('instance:charts.notMeasured');
      case 'containerCpuLimit':
        return t('instance:charts.containerCpuLimit', {
          cores: limit === null ? '' : format.number(limit, 2),
        });
    }
  };

  let state: 'loading' | 'error' | 'empty' | 'ready';
  if (namesQuery.isError || seriesQuery.isError) {
    state = 'error';
  } else if (reported === undefined) {
    state = 'loading';
  } else if (names.length === 0) {
    state = 'empty';
  } else if (answer === null) {
    state = 'loading';
  } else {
    state = 'ready';
  }

  return (
    <div className="instance-metrics" data-testid="instance-metrics" data-state={state}>
      <div className="instance-metrics__toolbar">
        <button
          type="button"
          className={`segmented__option${baselineOn ? ' segmented__option--checked' : ''}`}
          aria-pressed={baselineOn}
          onClick={() => {
            setUrlState({ baseline: !baselineOn }, { replace: true });
          }}
          data-testid="instance-baseline-toggle"
        >
          {t('instance:charts.baseline')}
        </button>
        {baselineOn && baselineQuery.isError ? (
          <span className="instance-warning instance-warning--warn" role="status">
            {t('instance:charts.baselineFailed')}
          </span>
        ) : null}
      </div>
      {state === 'error' ? (
        <ErrorState
          error={errorInfo(namesQuery.error ?? seriesQuery.error)}
          onRetry={() => {
            void namesQuery.refetch();
            void seriesQuery.refetch();
          }}
          data-testid="instance-metrics-error"
        />
      ) : state === 'loading' ? (
        <LoadingState variant="chart" data-testid="instance-metrics-loading" />
      ) : state === 'empty' ? (
        <EmptyState
          title={t('instance:charts.empty.title')}
          description={t('instance:charts.empty.description')}
          onWidenRange={onWidenRange}
          data-testid="instance-metrics-empty"
        />
      ) : (
        <div
          className="instance-metrics__grid"
          role="group"
          aria-label={t('instance:charts.label')}
        >
          {panels.map((panel) => {
            const titleId = panelTitleId(panel.id);
            const measured = !panel.notes.includes('notMeasured');
            return (
              <section
                key={panel.id}
                className="instance-metrics__panel"
                aria-labelledby={titleId}
                data-testid={`instance-panel-${panel.id}`}
                data-measured={String(measured)}
                data-series-count={panel.series.length}
                data-baseline-series={
                  panel.baseline === null ? 0 : Object.keys(panel.baseline.values).length
                }
              >
                <h2 id={titleId} className="instance-metrics__title">
                  {title(panel.id)}
                </h2>
                {measured ? (
                  <TimeSeriesChart
                    title={title(panel.id)}
                    ts={ts}
                    series={panel.series}
                    range={range}
                    baseline={panel.baseline}
                    markers={markers}
                    source={answer?.meta.source ?? null}
                    syncKey={INSTANCE_SYNC_KEY}
                    height={INSTANCE_PANEL_HEIGHT_PX}
                    onZoom={onZoom}
                    onOpenXLog={onOpenXLog}
                    data-testid={`instance-chart-${panel.id}`}
                  />
                ) : null}
                <span
                  hidden
                  data-testid={`instance-chart-${panel.id}-values`}
                  data-summary={seriesSummary(panel.series)}
                />
                {panel.notes.map((note) => (
                  <p
                    key={note}
                    className="instance-metrics__note"
                    data-testid={`instance-chart-${panel.id}-note`}
                    data-note={note}
                  >
                    {noteText(note, panel.cpuLimitCores)}
                  </p>
                ))}
              </section>
            );
          })}
        </div>
      )}
    </div>
  );
}
