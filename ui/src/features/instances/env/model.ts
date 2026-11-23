// SPDX-License-Identifier: Apache-2.0
//
// Pure parts of the environment tab (05 §2.7-3, docs/15 §9.8):
//
// - Effective configuration: the server knows only the keys a collector may change, the value it
//   holds for the application and the instance's state of it (`GET /instances/{id}/effective-
//   config`); the agent never sends its default, file or environment values. The latest self
//   diagnosis the user may read reports every effective key with its source (docs/10 §12.1), so
//   the table merges both: the agent's value and source when there is a self diagnosis, the
//   server's otherwise (source unknown for the rest; plan assumption, PM question 3).
// - Module status: modules the agent reported disabled (instance.modules_disabled), the error
//   counter `argos_agent_module_errors_total{module}` over the selected range and the modules of
//   the self diagnosis.
// - Public self-diagnosis summary (PM question 4): the agent's own series every viewer may read
//   (drops, agent CPU, estimated heap, stack capture cost, clock offset, dictionary overflow).
//   Missing series are null (the dash), never 0.
import type {
  EffectiveConfigEntry,
  Instance,
  InstanceMetrics,
  MetricSeries,
} from '../../../api/instances';
import type { Untrusted } from '../../../api/untrusted';
import { latestValue } from '../metrics/panels';

/** One effective key the latest self diagnosis reported (lane C parser output). */
export interface ReportedConfig {
  readonly key: string;
  readonly value: string | null;
  readonly source: string | null;
}

/** One module of the latest self diagnosis (lane C parser output). */
export interface ReportedModule {
  readonly name: string;
  readonly state: string | null;
  readonly errors: number | null;
  /** The agent's reason for a module that does not run (C-234 (1)); null when enabled. */
  readonly reason?: string | null;
}

export interface ConfigRow {
  readonly key: string;
  /** The effective value: the agent's (self diagnosis) or the server's when the instance runs it. */
  readonly value: Untrusted | string | null;
  /** `file`, `env`, `server`, `default`, ...; null when unknown. */
  readonly source: string | null;
  /** Where the row's value comes from. */
  readonly origin: 'agent' | 'server' | 'none';
  readonly serverValue: Untrusted | null;
  /** applied, pending, rejected, not_allowed; null without a server-held value. */
  readonly serverStatus: string | null;
  /** True when remote-config.allow names the key; null when the server did not list the key. */
  readonly remoteAllowed: boolean | null;
}

/**
 * Merges the server's effective configuration with the latest self diagnosis. Keys of either side
 * are listed once, by key. Without a report the value and source are the server's own (`server`
 * for a server-held value the instance runs, null otherwise).
 */
export function mergeEffectiveConfig(
  server: readonly EffectiveConfigEntry[] | null | undefined,
  reported: readonly ReportedConfig[] | null | undefined,
): ConfigRow[] {
  const rows = new Map<string, ConfigRow>();
  for (const entry of server ?? []) {
    rows.set(entry.key, {
      key: entry.key,
      value: entry.value,
      source: entry.source,
      origin: entry.value === null && entry.source === null ? 'none' : 'server',
      serverValue: entry.server_value,
      serverStatus: entry.server_status,
      remoteAllowed: entry.remote_allowed,
    });
  }
  for (const item of reported ?? []) {
    const existing = rows.get(item.key);
    rows.set(item.key, {
      key: item.key,
      value: item.value,
      source: item.source,
      origin: 'agent',
      serverValue: existing?.serverValue ?? null,
      serverStatus: existing?.serverStatus ?? null,
      remoteAllowed: existing?.remoteAllowed ?? null,
    });
  }
  return [...rows.values()].sort((a, b) => (a.key < b.key ? -1 : a.key > b.key ? 1 : 0));
}

export type ModuleState = 'active' | 'disabled' | 'unknown';

export interface ModuleRow {
  readonly name: string;
  readonly state: ModuleState;
  /** The state text of the self diagnosis when it is not active/disabled (shown as text). */
  readonly reportedState: string | null;
  /** argos_agent_module_errors_total increase over the selected range; null without the series. */
  readonly errorsInRange: number | null;
  /** The error counter of the latest self diagnosis; null without one. */
  readonly errorsReported: number | null;
  /** Why the module does not run, as the self diagnosis names it; null when none. */
  readonly reason: string | null;
}

/** Sum of the non-null buckets of a series; null when every bucket is null. */
export function sumValues(values: readonly (number | null | undefined)[]): number | null {
  let sum: number | null = null;
  for (const value of values) {
    if (typeof value === 'number' && Number.isFinite(value)) {
      sum = (sum ?? 0) + value;
    }
  }
  return sum;
}

function seriesOf(metrics: InstanceMetrics | null | undefined, metric: string): MetricSeries[] {
  return (metrics?.series ?? []).filter((series) => series.metric === metric);
}

function normalState(state: string | null): ModuleState | null {
  switch (state?.trim().toLowerCase()) {
    case 'active':
    case 'enabled':
    case 'on':
      return 'active';
    case 'disabled':
    case 'auto_disabled':
    case 'auto-disabled':
    case 'off':
    case 'disabled_by_config': // the agent's ModuleHealth states (C-234 (1))
    case 'disabled_by_errors':
    case 'disabled_by_remote':
      return 'disabled';
    default:
      return null;
  }
}

/**
 * The module table: every module named by modules_disabled, the error counter series or the self
 * diagnosis, by name. Disabled when the instance reports it disabled (or the self diagnosis says
 * so); otherwise the self diagnosis' state (unknown for a state it does not name active or
 * disabled, the text is kept), else active when the server tracks the instance and does not list
 * it as disabled, unknown otherwise.
 */
export function moduleRows(
  instance: Pick<Instance, 'modules_disabled'>,
  agentMetrics: InstanceMetrics | null | undefined,
  reported: readonly ReportedModule[] | null | undefined,
): ModuleRow[] {
  const disabled = new Set<string>(instance.modules_disabled ?? []);
  const errors = new Map<string, number | null>();
  for (const series of seriesOf(agentMetrics, 'argos_agent_module_errors_total')) {
    const name = series.labels.module;
    if (name === undefined || name === '') {
      continue;
    }
    const sum = sumValues(series.value);
    const before = errors.get(name);
    errors.set(
      name,
      before === undefined || before === null ? sum : sum === null ? before : before + sum,
    );
  }
  const report = new Map<string, ReportedModule>();
  for (const module of reported ?? []) {
    report.set(module.name, module);
  }
  const names = new Set<string>([...disabled, ...errors.keys(), ...report.keys()]);
  const rows: ModuleRow[] = [];
  for (const name of names) {
    const fromReport = report.get(name);
    const reportedState = fromReport === undefined ? null : fromReport.state;
    let state: ModuleState;
    if (disabled.has(name)) {
      state = 'disabled';
    } else if (fromReport !== undefined) {
      state = normalState(reportedState) ?? 'unknown';
    } else {
      state = instance.modules_disabled === null ? 'unknown' : 'active';
    }
    rows.push({
      name,
      state,
      reportedState: normalState(reportedState) === null ? reportedState : null,
      errorsInRange: errors.has(name) ? (errors.get(name) ?? null) : null,
      errorsReported: fromReport?.errors ?? null,
      reason: fromReport?.reason ?? null,
    });
  }
  return rows.sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
}

export interface AgentSummary {
  /** argos_agent_dropped_total increase over the range, all queues. */
  readonly dropped: number | null;
  /** argos_agent_cpu_seconds_total increase over the range. */
  readonly cpuSeconds: number | null;
  /** argos_agent_heap_bytes_estimated, latest value. */
  readonly heapBytes: number | null;
  /** argos_agent_stack_capture_seconds_total increase over the range. */
  readonly stackCaptureSeconds: number | null;
  /** argos_agent_clock_offset_ms, latest value. */
  readonly clockOffsetMs: number | null;
  /** argos_agent_dictionary_overflow_total increase over the range, all kinds. */
  readonly dictionaryOverflow: number | null;
}

function sumOf(metrics: InstanceMetrics | null | undefined, metric: string): number | null {
  let total: number | null = null;
  for (const series of seriesOf(metrics, metric)) {
    const sum = sumValues(series.value);
    if (sum !== null) {
      total = (total ?? 0) + sum;
    }
  }
  return total;
}

function latestOf(metrics: InstanceMetrics | null | undefined, metric: string): number | null {
  for (const series of seriesOf(metrics, metric)) {
    const value = latestValue(series.value);
    if (value !== null) {
      return value;
    }
  }
  return null;
}

/** The public self-diagnosis summary of the agent's own series over the selected range. */
export function agentSummary(metrics: InstanceMetrics | null | undefined): AgentSummary {
  return {
    dropped: sumOf(metrics, 'argos_agent_dropped_total'),
    cpuSeconds: sumOf(metrics, 'argos_agent_cpu_seconds_total'),
    heapBytes: latestOf(metrics, 'argos_agent_heap_bytes_estimated'),
    stackCaptureSeconds: sumOf(metrics, 'argos_agent_stack_capture_seconds_total'),
    clockOffsetMs: latestOf(metrics, 'argos_agent_clock_offset_ms'),
    dictionaryOverflow: sumOf(metrics, 'argos_agent_dictionary_overflow_total'),
  };
}
