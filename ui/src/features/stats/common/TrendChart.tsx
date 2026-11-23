// SPDX-License-Identifier: Apache-2.0
//
// Trend of a statistics detail (docs/15 §9.7, 05 §2.0-5, §2.5): calls and errors (`stats-trend`;
// outgoing call hosts add timeouts and connection failures per bucket, ADR-0010 C-222 (7))
// and, when the view has latencies, response time (`stats-trend-latency`) on TimeSeriesChart with
// the range's event markers (deploy, start, stop, config, ...), collection outages shaded (C-5),
// the rollup source under the axis, drag → global absolute range (live off) and point click → the
// XLog around that time with the view's filter. Bucket-estimated percentiles carry the estimated
// badge (C-14).
import { useCallback, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';

import type { ResponseMeta } from '../../../api/client';
import type { StatsRange, StatsSeries } from '../../../api/stats';
import type { ChartMarker, ChartSeries, TimeRange } from '../../../charts/chartModel';
import { TimeSeriesChart } from '../../../charts/TimeSeriesChart';

import { EstimatedBadge } from './cells';
import { chartSeries, metaOutages, seriesTimes } from './model';

/** Cursor sync group of the two trend charts. */
export const STATS_TREND_SYNC_KEY = 'stats-trend';
const CHART_HEIGHT_PX = 180;

/** Volume fields; timeouts and connect_failures exist on outgoing call hosts only (C-222 (7)). */
export type VolumeField = 'count' | 'errors' | 'error_rate' | 'timeouts' | 'connect_failures';
export type LatencyField = 'avg_ms' | 'p95_ms' | 'p99_ms';

export interface TrendChartProps {
  readonly series: StatsSeries;
  readonly meta: ResponseMeta | null;
  /** The query range (x scale and click-menu window). */
  readonly range: StatsRange;
  readonly markers: readonly ChartMarker[];
  /** The latencies are bucket estimates (C-14). */
  readonly estimated: boolean;
  readonly volume: readonly VolumeField[];
  readonly latency: readonly LatencyField[];
  /**
   * The XLog of a clicked point's window (± range/60, at least 1 minute) with the view's filter,
   * kind and environment (links.ts xlogAtRangeHref).
   */
  readonly xlogAt: (window: TimeRange) => string;
  /** Drag-selected range: the global absolute range. */
  readonly onZoom: (from: number, to: number) => void;
}

export function TrendChart({
  series,
  meta,
  range,
  markers,
  estimated,
  volume,
  latency,
  xlogAt,
  onZoom,
}: TrendChartProps) {
  const { t } = useTranslation(['common', 'stats']);
  const navigate = useNavigate();
  const ts = useMemo(() => seriesTimes(series), [series]);
  const outages = useMemo(() => metaOutages(meta), [meta]);
  const chartRange = useMemo<TimeRange>(() => ({ from: range.from, to: range.to }), [range]);
  const label = useCallback(
    (field: VolumeField | LatencyField): string => {
      switch (field) {
        case 'count':
          return t('stats:trend.count');
        case 'errors':
          return t('stats:trend.errors');
        case 'error_rate':
          return t('stats:trend.errorRate');
        case 'timeouts':
          return t('stats:trend.timeouts');
        case 'connect_failures':
          return t('stats:trend.connectFailures');
        case 'avg_ms':
          return t('stats:trend.avg');
        case 'p95_ms':
          return t('stats:trend.p95');
        case 'p99_ms':
          return t('stats:trend.p99');
      }
    },
    [t],
  );
  const volumeSeries = useMemo(
    () =>
      volume
        .map((field) =>
          chartSeries(series, field, label(field), field === 'error_rate' ? 'ratio' : 'count'),
        )
        .filter((item): item is ChartSeries => item !== null),
    [series, volume, label],
  );
  const latencySeries = useMemo(
    () =>
      latency
        .map((field) => chartSeries(series, field, label(field), 'ms'))
        .filter((item): item is ChartSeries => item !== null),
    [series, latency, label],
  );
  const onOpenXLog = useCallback(
    (window: TimeRange) => {
      void navigate(xlogAt(window));
    },
    [xlogAt, navigate],
  );
  const source = meta?.source ?? null;
  return (
    <section className="stats-trend" aria-label={t('stats:trend.title')}>
      <h2 className="stats-section__title">{t('stats:trend.title')}</h2>
      <div className="stats-trend__charts">
        <div className="stats-trend__chart">
          <TimeSeriesChart
            title={t('stats:trend.volumeTitle')}
            ts={ts}
            series={volumeSeries}
            range={chartRange}
            markers={markers}
            outages={outages}
            source={source}
            syncKey={STATS_TREND_SYNC_KEY}
            height={CHART_HEIGHT_PX}
            onZoom={onZoom}
            onOpenXLog={onOpenXLog}
            data-testid="stats-trend"
          />
        </div>
        {latencySeries.length === 0 ? null : (
          <div className="stats-trend__chart">
            <p className="stats-trend__caption">
              <span>{t('stats:trend.latencyTitle')}</span>
              {estimated ? <EstimatedBadge /> : null}
            </p>
            <TimeSeriesChart
              title={t('stats:trend.latencyTitle')}
              ts={ts}
              series={latencySeries}
              range={chartRange}
              markers={markers}
              outages={outages}
              source={source}
              syncKey={STATS_TREND_SYNC_KEY}
              height={CHART_HEIGHT_PX}
              onZoom={onZoom}
              onOpenXLog={onOpenXLog}
              data-testid="stats-trend-latency"
            />
          </div>
        )}
      </div>
    </section>
  );
}
