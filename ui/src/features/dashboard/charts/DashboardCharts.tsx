// SPDX-License-Identifier: Apache-2.0
//
// The nine time series panels of the dashboard (docs/15 §9.2, 05 §2.3-2) on TimeSeriesChart: one
// cursor for all (syncKey 'dashboard'), drag → global absolute range (live off), point click →
// XLog at that time ± range/60 (min 1 minute), the baseline of the transaction series (dashed),
// event markers (the `alert=` event emphasised) and collection outages shaded on every chart with
// the "N outages" legend (C-5, T-ACC-18 display). Notes say what a panel cannot show (active
// transactions without samples, no pools, no error source). Each panel keeps a short summary of
// its series values (`dashboard-chart-<panel>-values`, data-summary) for tests and tooling.
import { useTranslation } from 'react-i18next';

import type { ResponseMeta } from '../../../api/client';
import type { ChartMarker, ChartOutage, TimeRange } from '../../../charts/chartModel';
import { TimeSeriesChart } from '../../../charts/TimeSeriesChart';
import type { DashboardModel } from '../data/model';

import {
  dashboardPanels,
  seriesSummary,
  type PanelId,
  type PanelNote,
  type SeriesLabel,
} from './panels';

/** Plot height of a dashboard panel (three rows of panels, docs/15 §9.2). */
export const PANEL_HEIGHT_PX = 160;
/** Cursor sync group of the dashboard's charts (docs/15 §9.2). */
export const DASHBOARD_SYNC_KEY = 'dashboard';

/** DOM id of a panel (tile click scrolls to it). */
export function panelElementId(panel: PanelId): string {
  return `dashboard-panel-${panel}`;
}

export interface DashboardChartsProps {
  readonly model: DashboardModel;
  readonly range: TimeRange;
  readonly markers: readonly ChartMarker[];
  readonly outages: readonly ChartOutage[];
  readonly emphasizedMarkerId: string | null;
  readonly source: ResponseMeta['source'] | null;
  readonly onZoom: (from: number, to: number) => void;
  readonly onOpenXLog: (range: TimeRange) => void;
}

export function DashboardCharts({
  model,
  range,
  markers,
  outages,
  emphasizedMarkerId,
  source,
  onZoom,
  onOpenXLog,
}: DashboardChartsProps) {
  const { t } = useTranslation(['common', 'dashboard']);
  const seriesLabel = (key: SeriesLabel): string => {
    switch (key) {
      case 'tps':
        return t('dashboard:series.tps');
      case 'avg':
        return t('dashboard:series.avg');
      case 'p95':
        return t('dashboard:series.p95');
      case 'p99':
        return t('dashboard:series.p99');
      case 'errorRate':
        return t('dashboard:series.errorRate');
      case 'active':
        return t('dashboard:series.active');
      case 'heapUsed':
        return t('dashboard:series.heapUsed');
      case 'heapMax':
        return t('dashboard:series.heapMax');
      case 'gcPause':
        return t('dashboard:series.gcPause');
      case 'cpu':
        return t('dashboard:series.cpu');
      case 'systemCpu':
        return t('dashboard:series.systemCpu');
      case 'tomcatBusy':
        return t('dashboard:series.tomcatBusy');
      case 'extTime':
        return t('dashboard:series.extTime');
      case 'extErrors':
        return t('dashboard:series.extErrors');
    }
  };
  const title = (panel: PanelId): string => {
    switch (panel) {
      case 'tps':
        return t('dashboard:charts.tps');
      case 'response':
        return t('dashboard:charts.response');
      case 'error-rate':
        return t('dashboard:charts.errorRate');
      case 'active':
        return t('dashboard:charts.active');
      case 'heap-gc':
        return t('dashboard:charts.heapGc');
      case 'cpu':
        return t('dashboard:charts.cpu');
      case 'tomcat':
        return t('dashboard:charts.tomcat');
      case 'pool':
        return t('dashboard:charts.pool');
      case 'ext':
        return t('dashboard:charts.ext');
    }
  };
  const noteText = (note: PanelNote): string => {
    switch (note) {
      case 'activeNotMeasured':
        return t('dashboard:notes.activeNotMeasured');
      case 'noPools':
        return t('dashboard:notes.noPools');
      case 'extErrorsNotMeasured':
        return t('dashboard:notes.extErrorsNotMeasured');
    }
  };
  const panels = dashboardPanels(model, seriesLabel);
  return (
    <div className="dashboard-charts" role="group" aria-label={t('dashboard:charts.label')}>
      {panels.map((panel) => (
        <section
          key={panel.id}
          id={panelElementId(panel.id)}
          className="dashboard-charts__panel"
          tabIndex={-1}
          aria-labelledby={`${panelElementId(panel.id)}-title`}
          data-series-count={panel.series.length}
        >
          <h2 id={`${panelElementId(panel.id)}-title`} className="dashboard-charts__title">
            {title(panel.id)}
          </h2>
          <TimeSeriesChart
            title={title(panel.id)}
            ts={model.ts}
            series={panel.series}
            range={range}
            baseline={panel.baseline}
            markers={markers}
            emphasizedMarkerId={emphasizedMarkerId}
            outages={outages}
            source={source}
            syncKey={DASHBOARD_SYNC_KEY}
            height={PANEL_HEIGHT_PX}
            onZoom={onZoom}
            onOpenXLog={onOpenXLog}
            data-testid={`dashboard-chart-${panel.id}`}
          />
          <span
            hidden
            data-testid={`dashboard-chart-${panel.id}-values`}
            data-summary={seriesSummary(panel.series)}
          />
          {panel.notes.map((note) => (
            <p
              key={note}
              className="dashboard-charts__note"
              data-testid={`dashboard-chart-${panel.id}-note`}
              data-note={note}
            >
              {noteText(note)}
            </p>
          ))}
        </section>
      ))}
    </div>
  );
}
