// SPDX-License-Identifier: Apache-2.0
//
// The alert metrics of 12 §5.3 (F-S31) as the rule editor needs them: unit (threshold input and
// value formatting), whether it is a request metric (min_samples applies, 12 §5.4), the target
// types a rule of the metric may have, whether the 7-day preview can compute it (the minute
// rollups: request windows and metric rings; not active transactions, server self metrics,
// instance state or batch elapsed time) and whether this version computes it at all. The list
// mirrors the server enum AlertMetric; the OpenAPI contract carries the metric enum on
// `AlertRuleRef.metric` (ADR-0010 C-227 (4)) and the check below fails the build when the two lists
// differ.
import type {
  AlertMetricUnitWire,
  AlertMetricWire,
  AlertTargetType,
} from '../../../api/alertRules';
import type { AlertUnit } from '../../../components/AlertValue';

export type { AlertTargetType };

export interface MetricInfo {
  readonly unit: AlertUnit;
  /** Request metric: min_samples applies and no_data defaults to ignore. */
  readonly request: boolean;
  /** Target types a rule of this metric may have (server AlertMetric.targets()). */
  readonly targets: readonly AlertTargetType[];
  /** The 7-day preview can compute it (minute rollups). */
  readonly preview: boolean;
  /** This version computes a value (false: the rule never fires). */
  readonly available: boolean;
}

const REQUEST: readonly AlertTargetType[] = ['app', 'instance', 'service'];
const APP_INSTANCE: readonly AlertTargetType[] = ['app', 'instance'];
const EXT_HOST: readonly AlertTargetType[] = ['ext_host'];
const SERVICE: readonly AlertTargetType[] = ['service'];
const SERVER: readonly AlertTargetType[] = ['server'];
const APP_ONLY: readonly AlertTargetType[] = ['app'];

function m(
  unit: AlertUnit,
  targets: readonly AlertTargetType[],
  options: { request?: boolean; preview?: boolean; available?: boolean } = {},
): MetricInfo {
  return {
    unit,
    targets,
    request: options.request ?? false,
    preview: options.preview ?? false,
    available: options.available ?? true,
  };
}

/** Metric wire names in the order of 12 §5.3 (the editor's option order). */
export const ALERT_METRICS = [
  'response_time_p50',
  'response_time_p95',
  'response_time_p99',
  'tps',
  'error_rate',
  'apdex',
  'active_transactions',
  'active_ratio_to_max_threads',
  'jvm_heap_used_ratio',
  'jvm_old_gen_after_gc_ratio',
  'jvm_gc_pause_ratio',
  'jvm_threads_live',
  'tomcat_threads_usage_ratio',
  'jdbc_pool_waiting',
  'jdbc_pool_usage_ratio',
  'ext_call_p95',
  'ext_call_error_ratio',
  'ext_call_timeout_ratio',
  'batch_elapsed_seconds',
  'system_cpu_ratio',
  'system_memory_ratio',
  'system_disk_usage_ratio',
  'argos_agent_dropped_total',
  'argos_server_dropped_total',
  'argos_server_ingest_lag_seconds',
  'argos_server_disk_usage_ratio',
  'argos_notification_failures_total',
  'argos_server_tls_cert_expiry_days',
  'agent_token_expiry_days',
  'instance_state',
  'error_group_new',
] as const;

export type AlertMetricName = (typeof ALERT_METRICS)[number];

export const METRIC_CATALOG: Readonly<Record<AlertMetricName, MetricInfo>> = {
  response_time_p50: m('ms', REQUEST, { request: true, preview: true }),
  response_time_p95: m('ms', REQUEST, { request: true, preview: true }),
  response_time_p99: m('ms', REQUEST, { request: true, preview: true }),
  tps: m('per_second', REQUEST, { preview: true }),
  error_rate: m('ratio', REQUEST, { request: true, preview: true }),
  apdex: m('score', REQUEST, { request: true, preview: true }),
  active_transactions: m('count', APP_INSTANCE),
  active_ratio_to_max_threads: m('ratio', APP_INSTANCE),
  jvm_heap_used_ratio: m('ratio', APP_INSTANCE, { preview: true }),
  jvm_old_gen_after_gc_ratio: m('ratio', APP_INSTANCE, { available: false }),
  jvm_gc_pause_ratio: m('ratio', APP_INSTANCE, { preview: true }),
  jvm_threads_live: m('count', APP_INSTANCE, { preview: true }),
  tomcat_threads_usage_ratio: m('ratio', APP_INSTANCE, { preview: true }),
  jdbc_pool_waiting: m('count', APP_INSTANCE, { preview: true }),
  jdbc_pool_usage_ratio: m('ratio', APP_INSTANCE, { preview: true }),
  ext_call_p95: m('ms', EXT_HOST, { request: true, preview: true }),
  ext_call_error_ratio: m('ratio', EXT_HOST, { request: true, preview: true }),
  ext_call_timeout_ratio: m('ratio', EXT_HOST, { request: true, preview: true }),
  batch_elapsed_seconds: m('seconds', SERVICE),
  system_cpu_ratio: m('ratio', APP_INSTANCE, { preview: true }),
  system_memory_ratio: m('ratio', APP_INSTANCE, { preview: true }),
  system_disk_usage_ratio: m('ratio', APP_INSTANCE, { preview: true }),
  argos_agent_dropped_total: m('count', APP_INSTANCE, { preview: true }),
  argos_server_dropped_total: m('count', SERVER),
  argos_server_ingest_lag_seconds: m('seconds', SERVER),
  argos_server_disk_usage_ratio: m('ratio', SERVER),
  argos_notification_failures_total: m('count', SERVER),
  argos_server_tls_cert_expiry_days: m('days', SERVER),
  agent_token_expiry_days: m('days', SERVER),
  instance_state: m('state', APP_INSTANCE),
  error_group_new: m('count', APP_ONLY, { available: false }),
};

// Compile-time checks against the contract (ADR-0010 C-227 (4)): the metric enum of
// `AlertRuleRef.metric` and the unit enum of `AlertRuleRef.unit` must equal these lists (a plain
// string in an older schema passes trivially).
type GeneratedMetric = AlertMetricWire;
type GeneratedUnit = AlertMetricUnitWire;
type SameUnion<A, B> = [A] extends [B] ? ([B] extends [A] ? true : false) : false;
export const METRICS_MATCH_CONTRACT: string extends GeneratedMetric
  ? true
  : SameUnion<GeneratedMetric, AlertMetricName> = true;
export const UNITS_MATCH_CONTRACT: string extends GeneratedUnit
  ? true
  : SameUnion<GeneratedUnit, AlertUnit> = true;

/** True for a known metric wire name. */
export function isAlertMetric(value: string): value is AlertMetricName {
  return Object.prototype.hasOwnProperty.call(METRIC_CATALOG, value);
}

/** Catalog entry of a metric, or null for an unknown name (a newer server). */
export function metricInfo(value: string | null | undefined): MetricInfo | null {
  return value !== null && value !== undefined && isAlertMetric(value)
    ? METRIC_CATALOG[value]
    : null;
}

/**
 * Thresholds of ratio metrics are entered as percentages and sent as 0..1; every other unit is
 * entered as the API value.
 */
export function thresholdScale(unit: AlertUnit | null): number {
  return unit === 'ratio' ? 100 : 1;
}

/** API value → input value (ratio × 100), rounded to drop binary noise (0.07 → 7, not 7.000001). */
export function toInputValue(value: number | null | undefined, unit: AlertUnit | null): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return '';
  }
  return String(Number((value * thresholdScale(unit)).toPrecision(12)));
}

/** Input value → API value (percent / 100 for ratios); null when empty or not a number. */
export function toApiValue(input: string, unit: AlertUnit | null): number | null {
  const text = input.trim();
  if (text === '') {
    return null;
  }
  const value = Number(text);
  if (!Number.isFinite(value)) {
    return null;
  }
  return Number((value / thresholdScale(unit)).toPrecision(12));
}
