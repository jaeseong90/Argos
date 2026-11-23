// SPDX-License-Identifier: Apache-2.0
//
// The nine chart panels of the dashboard (docs/15 §9.2 layout, 05 §2.3-2): TPS; response time
// avg/p95/p99; error rate; active transactions; heap used/max with GC pause; process and system CPU
// (ADR-0010 C-212 (1)); Tomcat thread usage; connection pool usage per pool; external call time and
// errors. The transaction panels carry the baseline (F-U19); pool names are collected strings and
// only ever reach the DOM as series labels, which TimeSeriesChart renders through SafeText (series
// keys are positional).
import type { ChartBaseline, ChartSeries } from '../../../charts/chartModel';
import type { BaselineKey, DashboardModel, SeriesValues } from '../data/model';

export const PANEL_IDS = [
  'tps',
  'response',
  'error-rate',
  'active',
  'heap-gc',
  'cpu',
  'tomcat',
  'pool',
  'ext',
] as const;
export type PanelId = (typeof PANEL_IDS)[number];

/** Keys of dashboard:series.* (translated series names). */
export type SeriesLabel =
  | 'tps'
  | 'avg'
  | 'p95'
  | 'p99'
  | 'errorRate'
  | 'active'
  | 'heapUsed'
  | 'heapMax'
  | 'gcPause'
  | 'cpu'
  | 'systemCpu'
  | 'tomcatBusy'
  | 'extTime'
  | 'extErrors';

/** Keys of dashboard:notes.* shown under a panel. */
export type PanelNote = 'activeNotMeasured' | 'noPools' | 'extErrorsNotMeasured';

export interface PanelSpec {
  readonly id: PanelId;
  readonly series: readonly ChartSeries[];
  readonly baseline: ChartBaseline | null;
  readonly notes: readonly PanelNote[];
}

/**
 * The values of a panel's series in short for tests and tooling (`data-summary` of the hidden
 * `dashboard-chart-<panel>-values` element): per series key the number of buckets with a value and
 * their sum rounded to 6 decimals, so an E2E test compares the screen with the API series without
 * reading the canvas. Baseline lines are not included.
 */
export function seriesSummary(series: readonly ChartSeries[]): string {
  const out: Record<string, { n: number; sum: number }> = {};
  for (const s of series) {
    let n = 0;
    let sum = 0;
    for (const value of s.values) {
      if (typeof value === 'number' && Number.isFinite(value)) {
        n += 1;
        sum += value;
      }
    }
    out[s.key] = { n, sum: Math.round(sum * 1e6) / 1e6 };
  }
  return JSON.stringify(out);
}

function hasValue(values: SeriesValues): boolean {
  return values.some((value) => value !== null);
}

function baselineOf(model: DashboardModel, keys: readonly BaselineKey[]): ChartBaseline | null {
  const baseline = model.baseline;
  if (baseline === null) {
    return null;
  }
  const values: Record<string, SeriesValues> = {};
  for (const key of keys) {
    values[key] = baseline.values[key];
  }
  return { offset: baseline.offset, values };
}

/** The nine panels of a model, in layout order; `label` translates the series names. */
export function dashboardPanels(
  model: DashboardModel,
  label: (key: SeriesLabel) => string,
): PanelSpec[] {
  const s = model.series;
  const extSeries: ChartSeries[] = [
    { key: 'ext_time', label: label('extTime'), unit: 'ms', values: s.ext_time_ms },
  ];
  if (s.ext_errors !== null) {
    extSeries.push({
      key: 'ext_errors',
      label: label('extErrors'),
      unit: 'count',
      values: s.ext_errors,
      kind: 'bar',
    });
  }
  return [
    {
      id: 'tps',
      series: [{ key: 'tps', label: label('tps'), unit: 'rate', values: s.tps, kind: 'area' }],
      baseline: baselineOf(model, ['tps']),
      notes: [],
    },
    {
      id: 'response',
      series: [
        { key: 'avg', label: label('avg'), unit: 'ms', values: s.avg },
        { key: 'p95', label: label('p95'), unit: 'ms', values: s.p95 },
        { key: 'p99', label: label('p99'), unit: 'ms', values: s.p99 },
      ],
      baseline: baselineOf(model, ['avg', 'p95', 'p99']),
      notes: [],
    },
    {
      id: 'error-rate',
      series: [
        { key: 'error_rate', label: label('errorRate'), unit: 'ratio', values: s.error_rate },
      ],
      baseline: baselineOf(model, ['error_rate']),
      notes: [],
    },
    {
      id: 'active',
      series: [{ key: 'active', label: label('active'), unit: 'count', values: s.active }],
      baseline: null,
      // series.active is null until the server records active-transaction gauges (C-178).
      notes: hasValue(s.active) ? [] : ['activeNotMeasured'],
    },
    {
      id: 'heap-gc',
      series: [
        {
          key: 'heap_used',
          label: label('heapUsed'),
          unit: 'bytes',
          values: s.heap_used,
          kind: 'area',
        },
        { key: 'heap_max', label: label('heapMax'), unit: 'bytes', values: s.heap_max },
        {
          key: 'gc_pause',
          label: label('gcPause'),
          unit: 'ms',
          values: s.gc_pause_ms,
          kind: 'bar',
        },
      ],
      baseline: null,
      notes: [],
    },
    {
      id: 'cpu',
      series: [
        { key: 'cpu', label: label('cpu'), unit: 'ratio', values: s.cpu },
        { key: 'system_cpu', label: label('systemCpu'), unit: 'ratio', values: s.system_cpu },
      ],
      baseline: null,
      notes: [],
    },
    {
      id: 'tomcat',
      series: [
        {
          key: 'tomcat_busy',
          label: label('tomcatBusy'),
          unit: 'ratio',
          values: s.tomcat_busy_ratio,
        },
      ],
      baseline: null,
      notes: [],
    },
    {
      id: 'pool',
      series: s.pools.map((pool, index) => ({
        key: `pool-${String(index)}`,
        label: pool.name,
        unit: 'ratio' as const,
        values: pool.values,
      })),
      baseline: null,
      notes: s.pools.length === 0 ? ['noPools'] : [],
    },
    {
      id: 'ext',
      series: extSeries,
      baseline: null,
      notes: s.ext_errors === null ? ['extErrorsNotMeasured'] : [],
    },
  ];
}
