// SPDX-License-Identifier: Apache-2.0
//
// The time series panels of the instance detail (05 §2.7-2, docs/15 §9.8) from the agent's metric
// names (docs/10 §11): heap by area and memory pools (used, max), GC count and pause time per
// collector, threads (live, daemon, peak, virtual threads started), classes, CPU (process and
// system; the system ratio uses the container limit as denominator, the limit is noted), file
// descriptors, Tomcat per connector, connection pools (active, idle, waiting, max), system memory,
// disk and network.
//
// The screen discovers the series the instance actually reported (`GET /instances/{id}/metrics/
// names`), asks for the ones of these panels and the agent's own series (chunks of 50, api/
// instances.ts) and keys every chart series by its stored name with labels, so a test compares a
// panel's values with the API answer for the same names. A panel without any reported series says
// "Not measured" instead of drawing empty lines; a bucket without samples stays null (D-09), never 0.
import type { Enveloped } from '../../../api/client';
import type { InstanceMetrics, MetricName, MetricSeries } from '../../../api/instances';
import type { ChartBaseline, ChartSeries } from '../../../charts/chartModel';
import type { ValueUnit } from '../../../utils/format';

export type PanelId =
  | 'heap'
  | 'memory-pools'
  | 'gc-count'
  | 'gc-pause'
  | 'threads'
  | 'classes'
  | 'cpu'
  | 'fd'
  | 'tomcat'
  | 'jdbc-pools'
  | 'system-memory'
  | 'disk'
  | 'network';

/** Series label kinds (instance:series.* keys); `name` is the label part (pool, connector, ...). */
export type SeriesLabelKind =
  | 'memoryUsed'
  | 'memoryMax'
  | 'poolUsed'
  | 'poolMax'
  | 'gcCount'
  | 'gcPause'
  | 'threadsLive'
  | 'threadsDaemon'
  | 'threadsPeak'
  | 'threadsVirtualStarted'
  | 'classesLoaded'
  | 'classesUnloaded'
  | 'cpuProcess'
  | 'cpuSystem'
  | 'fdOpen'
  | 'fdMax'
  | 'tomcatBusy'
  | 'tomcatThreads'
  | 'tomcatThreadsMax'
  | 'tomcatConnections'
  | 'tomcatConnectionsMax'
  | 'jdbcActive'
  | 'jdbcIdle'
  | 'jdbcWaiting'
  | 'jdbcMax'
  | 'systemMemoryUsed'
  | 'systemMemoryTotal'
  | 'containerMemoryLimit'
  | 'diskUsed'
  | 'diskTotal'
  | 'networkReceive'
  | 'networkTransmit';

interface SeriesRule {
  readonly metric: string;
  readonly kind: SeriesLabelKind;
  readonly unit: ValueUnit;
  /** Labels whose values name the series (joined by a space), in this order. */
  readonly labels?: readonly string[];
}

export interface PanelDef {
  readonly id: PanelId;
  readonly rules: readonly SeriesRule[];
}

/** Panels in display order (docs/10 §11 names). */
export const PANELS: readonly PanelDef[] = [
  {
    id: 'heap',
    rules: [
      { metric: 'jvm_memory_used_bytes', kind: 'memoryUsed', unit: 'bytes', labels: ['area'] },
      { metric: 'jvm_memory_max_bytes', kind: 'memoryMax', unit: 'bytes', labels: ['area'] },
    ],
  },
  {
    id: 'memory-pools',
    rules: [
      { metric: 'jvm_memory_pool_used_bytes', kind: 'poolUsed', unit: 'bytes', labels: ['pool'] },
      { metric: 'jvm_memory_pool_max_bytes', kind: 'poolMax', unit: 'bytes', labels: ['pool'] },
    ],
  },
  {
    id: 'gc-count',
    rules: [
      {
        metric: 'jvm_gc_collection_count_total',
        kind: 'gcCount',
        unit: 'count',
        labels: ['gc', 'gc_type'],
      },
    ],
  },
  {
    id: 'gc-pause',
    rules: [
      { metric: 'jvm_gc_pause_seconds_total', kind: 'gcPause', unit: 'seconds', labels: ['gc'] },
    ],
  },
  {
    id: 'threads',
    rules: [
      { metric: 'jvm_threads_live', kind: 'threadsLive', unit: 'count' },
      { metric: 'jvm_threads_daemon', kind: 'threadsDaemon', unit: 'count' },
      { metric: 'jvm_threads_peak', kind: 'threadsPeak', unit: 'count' },
      { metric: 'jvm_threads_virtual_started_total', kind: 'threadsVirtualStarted', unit: 'count' },
    ],
  },
  {
    id: 'classes',
    rules: [
      { metric: 'jvm_classes_loaded', kind: 'classesLoaded', unit: 'count' },
      { metric: 'jvm_classes_unloaded_total', kind: 'classesUnloaded', unit: 'count' },
    ],
  },
  {
    id: 'cpu',
    rules: [
      { metric: 'process_cpu_usage_ratio', kind: 'cpuProcess', unit: 'ratio' },
      { metric: 'system_cpu_usage_ratio', kind: 'cpuSystem', unit: 'ratio' },
    ],
  },
  {
    id: 'fd',
    rules: [
      { metric: 'process_open_fds', kind: 'fdOpen', unit: 'count' },
      { metric: 'process_max_fds', kind: 'fdMax', unit: 'count' },
    ],
  },
  {
    id: 'tomcat',
    rules: [
      { metric: 'tomcat_threads_busy', kind: 'tomcatBusy', unit: 'count', labels: ['connector'] },
      {
        metric: 'tomcat_threads_current',
        kind: 'tomcatThreads',
        unit: 'count',
        labels: ['connector'],
      },
      {
        metric: 'tomcat_threads_max',
        kind: 'tomcatThreadsMax',
        unit: 'count',
        labels: ['connector'],
      },
      {
        metric: 'tomcat_connections_current',
        kind: 'tomcatConnections',
        unit: 'count',
        labels: ['connector'],
      },
      {
        metric: 'tomcat_connections_max',
        kind: 'tomcatConnectionsMax',
        unit: 'count',
        labels: ['connector'],
      },
    ],
  },
  {
    id: 'jdbc-pools',
    rules: [
      { metric: 'jdbc_pool_active', kind: 'jdbcActive', unit: 'count', labels: ['pool'] },
      { metric: 'jdbc_pool_idle', kind: 'jdbcIdle', unit: 'count', labels: ['pool'] },
      { metric: 'jdbc_pool_waiting', kind: 'jdbcWaiting', unit: 'count', labels: ['pool'] },
      { metric: 'jdbc_pool_max', kind: 'jdbcMax', unit: 'count', labels: ['pool'] },
    ],
  },
  {
    id: 'system-memory',
    rules: [
      { metric: 'system_memory_used_bytes', kind: 'systemMemoryUsed', unit: 'bytes' },
      { metric: 'system_memory_total_bytes', kind: 'systemMemoryTotal', unit: 'bytes' },
      { metric: 'container_memory_limit_bytes', kind: 'containerMemoryLimit', unit: 'bytes' },
    ],
  },
  {
    id: 'disk',
    rules: [
      { metric: 'system_disk_used_bytes', kind: 'diskUsed', unit: 'bytes', labels: ['mount'] },
      { metric: 'system_disk_total_bytes', kind: 'diskTotal', unit: 'bytes', labels: ['mount'] },
    ],
  },
  {
    id: 'network',
    rules: [
      {
        metric: 'system_network_receive_bytes_total',
        kind: 'networkReceive',
        unit: 'bytes',
        labels: ['iface'],
      },
      {
        metric: 'system_network_transmit_bytes_total',
        kind: 'networkTransmit',
        unit: 'bytes',
        labels: ['iface'],
      },
    ],
  },
];

/** The container CPU limit (cores), noted under the CPU panel. */
export const CONTAINER_CPU_LIMIT = 'container_cpu_limit_cores';

/**
 * The agent's own series the environment tab summarises (05 §2.7-3 "최근 자가 진단 결과", the
 * public summary every viewer may read; docs/10 §11 "에이전트 자기").
 */
export const AGENT_METRICS = [
  'argos_agent_dropped_total',
  'argos_agent_cpu_seconds_total',
  'argos_agent_heap_bytes_estimated',
  'argos_agent_stack_capture_seconds_total',
  'argos_agent_clock_offset_ms',
  'argos_agent_dictionary_overflow_total',
  'argos_agent_module_errors_total',
] as const;
export type AgentMetric = (typeof AGENT_METRICS)[number];

const RULE_OF = new Map<string, { readonly panel: PanelId; readonly rule: SeriesRule }>();
for (const panel of PANELS) {
  for (const rule of panel.rules) {
    RULE_OF.set(rule.metric, { panel: panel.id, rule });
  }
}

/** True for the metric (base name) of a chart panel. */
export function isPanelMetric(metric: string): boolean {
  return RULE_OF.has(metric);
}

/**
 * The series names to request for the metrics tab: every reported name of a panel metric and the
 * container CPU limit, in the order of `names` (by name, as the server lists them).
 */
export function chartNames(names: readonly MetricName[]): string[] {
  return names
    .filter((name) => isPanelMetric(name.metric) || name.metric === CONTAINER_CPU_LIMIT)
    .map((name) => name.name);
}

/** The series names of the agent's own metrics (environment tab summary). */
export function agentNames(names: readonly MetricName[]): string[] {
  const wanted: ReadonlySet<string> = new Set(AGENT_METRICS);
  return names.filter((name) => wanted.has(name.metric)).map((name) => name.name);
}

/** Translates a series label (the screen passes its t() switch). */
export type SeriesLabeler = (kind: SeriesLabelKind, name: string) => string;

export type PanelNote = 'notMeasured' | 'containerCpuLimit';

export interface PanelModel {
  readonly id: PanelId;
  readonly series: readonly ChartSeries[];
  readonly baseline: ChartBaseline | null;
  readonly notes: readonly PanelNote[];
  /** CPU panel: the latest container CPU limit in cores, null when the agent reports none. */
  readonly cpuLimitCores: number | null;
}

/** Epoch ms of the answer's bucket starts (empty without an answer). */
export function gridMs(metrics: InstanceMetrics | null | undefined): number[] {
  return (metrics?.ts ?? []).map((ts) => Date.parse(ts));
}

/** The name part of a series: the values of the rule's labels, joined by a space. */
export function seriesNamePart(
  labels: Readonly<Record<string, string>>,
  keys: readonly string[],
): string {
  return keys
    .map((key) => labels[key])
    .filter((value): value is string => value !== undefined && value !== '')
    .join(' ');
}

/** The latest non-null value of a series, or null. */
export function latestValue(values: readonly (number | null | undefined)[]): number | null {
  for (let i = values.length - 1; i >= 0; i -= 1) {
    const value = values[i];
    if (typeof value === 'number' && Number.isFinite(value)) {
      return value;
    }
  }
  return null;
}

/**
 * The baseline of a panel: the same series of the window `offsetMs` earlier, moved onto the
 * current grid (bucket t of the current window takes the baseline bucket t - offset). Missing
 * buckets stay null. Keys are the series keys (stored names).
 */
export function shiftBaseline(
  currentTs: readonly number[],
  baseline: InstanceMetrics | null | undefined,
  offsetMs: number,
): Record<string, (number | null)[]> {
  const out: Record<string, (number | null)[]> = {};
  if (baseline === null || baseline === undefined) {
    return out;
  }
  const index = new Map(baseline.ts.map((ts, i) => [Date.parse(ts), i] as const));
  for (const series of baseline.series) {
    out[series.name] = currentTs.map((ts) => {
      const at = index.get(ts - offsetMs);
      return at === undefined ? null : (series.value[at] ?? null);
    });
  }
  return out;
}

/** Seven days, the instance chart baseline (docs/14 §4.10 has no baseline parameter). */
export const BASELINE_OFFSET_MS = 7 * 24 * 60 * 60 * 1000;

/**
 * The panels of an answer: for each panel the series of the requested names that belong to it
 * (stored name as key, request order), the baseline when given, and the notes. `reported` are the
 * discovered names: a panel with none of its metrics reported is "Not measured".
 */
export function buildPanels(
  reported: readonly MetricName[],
  answer: Enveloped<InstanceMetrics> | null | undefined,
  label: SeriesLabeler,
  baseline: Record<string, (number | null)[]> | null = null,
): PanelModel[] {
  const byPanel = new Map<PanelId, MetricSeries[]>();
  for (const series of answer?.data.series ?? []) {
    const owner = RULE_OF.get(series.metric);
    if (owner === undefined) {
      continue;
    }
    const list = byPanel.get(owner.panel) ?? [];
    list.push(series);
    byPanel.set(owner.panel, list);
  }
  const reportedPanels = new Set<PanelId>();
  for (const name of reported) {
    const owner = RULE_OF.get(name.metric);
    if (owner !== undefined) {
      reportedPanels.add(owner.panel);
    }
  }
  const cpuLimitSeries = answer?.data.series.filter((s) => s.metric === CONTAINER_CPU_LIMIT) ?? [];
  const cpuLimit = cpuLimitSeries.reduce<number | null>((found, s) => {
    return found ?? latestValue(s.value);
  }, null);

  return PANELS.map((panel) => {
    const series: ChartSeries[] = (byPanel.get(panel.id) ?? []).map((one) => {
      const owner = RULE_OF.get(one.metric);
      const rule = owner?.rule;
      return {
        key: one.name,
        label: label(rule?.kind ?? 'memoryUsed', seriesNamePart(one.labels, rule?.labels ?? [])),
        unit: rule?.unit ?? 'none',
        values: one.value,
      };
    });
    const notes: PanelNote[] = [];
    if (!reportedPanels.has(panel.id)) {
      notes.push('notMeasured');
    }
    if (panel.id === 'cpu' && cpuLimit !== null) {
      notes.push('containerCpuLimit');
    }
    let panelBaseline: ChartBaseline | null = null;
    if (baseline !== null && series.length > 0) {
      const values: Record<string, (number | null)[]> = {};
      for (const one of series) {
        const shifted = baseline[one.key];
        if (shifted !== undefined) {
          values[one.key] = shifted;
        }
      }
      panelBaseline = { offset: '7d', values };
    }
    return {
      id: panel.id,
      series,
      baseline: panelBaseline,
      notes,
      cpuLimitCores: panel.id === 'cpu' ? cpuLimit : null,
    };
  });
}

/**
 * The values of a panel's series in short (`data-summary` of the hidden
 * `instance-chart-<panel>-values` element): per series key (stored name) the number of buckets
 * with a value and their sum rounded to 6 decimals, the same form as the dashboard's, so an E2E
 * test compares the screen with `GET /instances/{id}/metrics` without reading the canvas.
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
