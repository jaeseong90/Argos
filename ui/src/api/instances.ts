// SPDX-License-Identifier: Apache-2.0
//
// Instances of an application and their metadata, metric series, effective configuration and
// events (docs/14 §4.3, §4.10, §4.12, §7.3; docs/15 §9.8; WO-37). Every call goes through the
// generated client; the cache keys of these queries live in this file only.
//
// Metric series: `GET /instances/{id}/metrics` takes 1 to 50 names per call (docs/14 §4.10), so a
// screen that needs more asks in chunks of 50 and merges the answers on the first chunk's time grid
// (every chunk asks the same range and step, so the server answers on the same grid). A bucket
// without samples stays null (D-09): the merge never fills a gap with 0.
import { keepPreviousData, useQuery, type UseQueryResult } from '@tanstack/react-query';

import { api, unwrap, type Enveloped, type EnvelopeOf, type ResponseMeta } from './client';
import type { components } from './generated/schema';

export type Instance = components['schemas']['Instance'];
export type MetricName = components['schemas']['MetricName'];
export type MetricSeries = components['schemas']['MetricSeries'];
export type InstanceMetrics = components['schemas']['InstanceMetrics'];
export type EffectiveConfigEntry = components['schemas']['EffectiveConfigEntry'];
export type InstanceEvent = components['schemas']['Event'];

type InstanceEnvelope = EnvelopeOf<'/api/v1/instances/{instanceId}', 'get'>;
type InstancesEnvelope = EnvelopeOf<'/api/v1/apps/{appId}/instances', 'get'>;
type NamesEnvelope = EnvelopeOf<'/api/v1/instances/{instanceId}/metrics/names', 'get'>;
type MetricsEnvelope = EnvelopeOf<'/api/v1/instances/{instanceId}/metrics', 'get'>;
type EffectiveConfigEnvelope = EnvelopeOf<'/api/v1/instances/{instanceId}/effective-config', 'get'>;
type EventsEnvelope = EnvelopeOf<'/api/v1/apps/{appId}/events', 'get'>;

/** Names one metrics call takes at most (docs/14 §4.10). */
export const METRIC_NAMES_PER_CALL = 50;

/** An absolute query range (epoch ms, [from, to)). */
export interface InstanceRange {
  readonly from: number;
  readonly to: number;
}

function iso(ms: number): string {
  return new Date(ms).toISOString();
}

function withSignal(signal: AbortSignal | undefined): { signal?: AbortSignal } {
  return signal === undefined ? {} : { signal };
}

// ---------------------------------------------------------------------------------------------
// Cache keys
// ---------------------------------------------------------------------------------------------

/** Cache keys of the instance queries (all start with 'instances'). */
export const instanceKeys = {
  all: () => ['instances'] as const,
  list: (appId: number | null) => ['instances', 'list', appId] as const,
  detail: (instanceId: number | null) => ['instances', 'detail', instanceId] as const,
  metricNames: (instanceId: number | null) => ['instances', 'metric-names', instanceId] as const,
  metrics: (instanceId: number | null, names: readonly string[], range: InstanceRange) =>
    ['instances', 'metrics', instanceId, iso(range.from), iso(range.to), [...names]] as const,
  effectiveConfig: (instanceId: number | null) =>
    ['instances', 'effective-config', instanceId] as const,
  events: (appId: number | null, range: InstanceRange) =>
    ['instances', 'events', appId, iso(range.from), iso(range.to)] as const,
};

// ---------------------------------------------------------------------------------------------
// Calls
// ---------------------------------------------------------------------------------------------

/** `GET /instances/{id}`: the instance with all its metadata (404 not_found when unknown). */
export async function getInstance(
  instanceId: number,
  signal?: AbortSignal,
): Promise<Enveloped<Instance>> {
  return unwrap<InstanceEnvelope>(
    api().client.GET('/api/v1/instances/{instanceId}', {
      params: { path: { instanceId } },
      ...withSignal(signal),
    }),
  );
}

/** `GET /apps/{appId}/instances`: at most 1,000 instances by name (archived ones left out). */
export async function listInstances(
  appId: number,
  signal?: AbortSignal,
): Promise<Enveloped<Instance[]>> {
  return unwrap<InstancesEnvelope>(
    api().client.GET('/api/v1/apps/{appId}/instances', {
      params: { path: { appId } },
      ...withSignal(signal),
    }),
  );
}

/** `GET /instances/{id}/metrics/names`: the series the instance reported in the last 30 days. */
export async function listMetricNames(
  instanceId: number,
  signal?: AbortSignal,
): Promise<Enveloped<MetricName[]>> {
  return unwrap<NamesEnvelope>(
    api().client.GET('/api/v1/instances/{instanceId}/metrics/names', {
      params: { path: { instanceId } },
      ...withSignal(signal),
    }),
  );
}

/** Splits series names into the chunks one metrics call takes (order kept, duplicates dropped). */
export function chunkNames(
  names: readonly string[],
  size: number = METRIC_NAMES_PER_CALL,
): string[][] {
  const unique = [...new Set(names)];
  const chunks: string[][] = [];
  for (let i = 0; i < unique.length; i += size) {
    chunks.push(unique.slice(i, i + size));
  }
  return chunks;
}

/**
 * Merges the answers of chunked metrics calls: the time grid of the first answer, every series in
 * request order. A series of a later chunk whose grid differs is aligned by bucket start; buckets
 * it lacks stay null (never 0). The meta is the first answer's, truncated when any answer was.
 */
export function mergeMetricChunks(
  answers: readonly Enveloped<InstanceMetrics>[],
): Enveloped<InstanceMetrics> | null {
  const first = answers[0];
  if (first === undefined) {
    return null;
  }
  const ts = first.data.ts;
  const position = new Map(ts.map((value, index) => [Date.parse(value), index] as const));
  const series: MetricSeries[] = [];
  let truncated = false;
  for (const answer of answers) {
    truncated = truncated || answer.meta.truncated === true;
    const sameGrid =
      answer.data.ts.length === ts.length &&
      answer.data.ts.every((value, index) => Date.parse(value) === Date.parse(ts[index] ?? ''));
    for (const one of answer.data.series) {
      if (sameGrid) {
        series.push(one);
        continue;
      }
      const align = (values: readonly (number | null)[]): (number | null)[] => {
        const out: (number | null)[] = ts.map(() => null);
        one.ts.forEach((bucket, index) => {
          const at = position.get(Date.parse(bucket));
          if (at !== undefined) {
            out[at] = values[index] ?? null;
          }
        });
        return out;
      };
      series.push({
        ...one,
        ts,
        value: align(one.value),
        min: align(one.min),
        max: align(one.max),
      });
    }
  }
  const meta: ResponseMeta = truncated ? { ...first.meta, truncated } : first.meta;
  return { data: { ts, series }, meta };
}

/**
 * `GET /instances/{id}/metrics` for any number of names: chunks of 50 asked in parallel and merged
 * (mergeMetricChunks). No names: an empty answer without a request.
 */
export async function getMetricSeries(
  instanceId: number,
  names: readonly string[],
  range: InstanceRange,
  signal?: AbortSignal,
): Promise<Enveloped<InstanceMetrics> | null> {
  const chunks = chunkNames(names);
  if (chunks.length === 0) {
    return null;
  }
  const answers = await Promise.all(
    chunks.map((chunk) =>
      unwrap<MetricsEnvelope>(
        api().client.GET('/api/v1/instances/{instanceId}/metrics', {
          params: {
            path: { instanceId },
            query: { names: chunk, from: iso(range.from), to: iso(range.to) },
          },
          ...withSignal(signal),
        }),
      ),
    ),
  );
  return mergeMetricChunks(answers);
}

/** `GET /instances/{id}/effective-config` (operator): the keys a collector may change. */
export async function getEffectiveConfig(
  instanceId: number,
  signal?: AbortSignal,
): Promise<Enveloped<EffectiveConfigEntry[]>> {
  return unwrap<EffectiveConfigEnvelope>(
    api().client.GET('/api/v1/instances/{instanceId}/effective-config', {
      params: { path: { instanceId } },
      ...withSignal(signal),
    }),
  );
}

/** Event types the instance screen draws (deploy markers and the instance's own events). */
export const INSTANCE_EVENT_TYPES = ['deploy', 'start', 'stop', 'config'] as const;

/**
 * `GET /apps/{appId}/events?type=deploy,start,stop,config` of a range (newest first, at most
 * 1,000; meta.truncated when cut). The screen keeps the app's deploys and this instance's events.
 */
export async function listInstanceEvents(
  appId: number,
  range: InstanceRange,
  signal?: AbortSignal,
): Promise<Enveloped<InstanceEvent[]>> {
  return unwrap<EventsEnvelope>(
    api().client.GET('/api/v1/apps/{appId}/events', {
      params: {
        path: { appId },
        query: { from: iso(range.from), to: iso(range.to), type: [...INSTANCE_EVENT_TYPES] },
      },
      ...withSignal(signal),
    }),
  );
}

// ---------------------------------------------------------------------------------------------
// Hooks
// ---------------------------------------------------------------------------------------------

/** One instance; null id requests nothing. */
export function useInstance(instanceId: number | null): UseQueryResult<Enveloped<Instance>> {
  return useQuery({
    queryKey: instanceKeys.detail(instanceId),
    queryFn: ({ signal }) => getInstance(instanceId ?? 0, signal),
    enabled: instanceId !== null,
    // Heartbeat, state and uptime move: refresh while the screen is open (not in the background).
    refetchInterval: 30_000,
  });
}

/** The instances of an application. */
export function useInstances(appId: number | null): UseQueryResult<Enveloped<Instance[]>> {
  return useQuery({
    queryKey: instanceKeys.list(appId),
    queryFn: ({ signal }) => listInstances(appId ?? 0, signal),
    enabled: appId !== null,
    refetchInterval: 30_000,
  });
}

/** The series names of an instance (discovery for the charts). */
export function useMetricNames(instanceId: number | null): UseQueryResult<Enveloped<MetricName[]>> {
  return useQuery({
    queryKey: instanceKeys.metricNames(instanceId),
    queryFn: ({ signal }) => listMetricNames(instanceId ?? 0, signal),
    enabled: instanceId !== null,
    staleTime: 60_000,
  });
}

/**
 * The named series of an instance over a range (chunked, merged). The previous answer stays
 * shown while a new range of the same instance loads.
 */
export function useMetricSeries(
  instanceId: number | null,
  names: readonly string[],
  range: InstanceRange,
  enabled = true,
): UseQueryResult<Enveloped<InstanceMetrics> | null> {
  const key = instanceKeys.metrics(instanceId, names, range);
  return useQuery({
    queryKey: key,
    queryFn: ({ signal }) => getMetricSeries(instanceId ?? 0, names, range, signal),
    enabled: enabled && instanceId !== null && names.length > 0,
    placeholderData: (previous, previousQuery) =>
      previousQuery?.queryKey[2] === instanceId && previousQuery.queryKey[5].length === names.length
        ? keepPreviousData(previous)
        : undefined,
  });
}

/** The effective configuration (operator); `enabled` false for roles that may not read it. */
export function useEffectiveConfig(
  instanceId: number | null,
  enabled: boolean,
): UseQueryResult<Enveloped<EffectiveConfigEntry[]>> {
  return useQuery({
    queryKey: instanceKeys.effectiveConfig(instanceId),
    queryFn: ({ signal }) => getEffectiveConfig(instanceId ?? 0, signal),
    enabled: enabled && instanceId !== null,
  });
}

/** Deploy, start, stop and config events of an application over a range. */
export function useInstanceEvents(
  appId: number | null,
  range: InstanceRange,
): UseQueryResult<Enveloped<InstanceEvent[]>> {
  return useQuery({
    queryKey: instanceKeys.events(appId, range),
    queryFn: ({ signal }) => listInstanceEvents(appId ?? 0, range, signal),
    enabled: appId !== null,
    placeholderData: (previous, previousQuery) =>
      previousQuery?.queryKey[2] === appId ? keepPreviousData(previous) : undefined,
  });
}
