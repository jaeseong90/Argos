// SPDX-License-Identifier: Apache-2.0
//
// Instance administration (docs/14 §4.3, 05 §2.11-2 and §2.11-8, docs/15 §9.10 `/admin/instances`,
// WO-39): the instances of an application (the archived list with `archived=true`), archive
// (operator, only while not connected), delete (admin, archived instances only), and the
// per-instance collection health totals for the ranking (drops, ingest lag, clock offset, excluded
// requests; null is "no source", never 0, D-23). Generated client only; keys start with 'admin'.
import { useQuery } from '@tanstack/react-query';

import { api, expectNoContent, unwrap, type Enveloped, type EnvelopeOf } from '../client';
import type { components } from '../generated/schema';

export type Instance = components['schemas']['Instance'];
export type InstanceCollection = components['schemas']['InstanceCollection'];

export const adminInstanceKeys = {
  all: () => ['admin', 'instances'] as const,
  list: (appId: number | null, archived: boolean) =>
    ['admin', 'instances', 'list', appId, archived] as const,
  collection: (appId: number | null, from: string, to: string) =>
    ['admin', 'instances', 'collection', appId, from, to] as const,
};

/** Instances of an application by name; `archived` lists the archived ones (at most 1,000). */
export async function listAdminInstances(
  appId: number,
  archived: boolean,
  signal?: AbortSignal,
): Promise<Enveloped<Instance[]>> {
  return unwrap<EnvelopeOf<'/api/v1/apps/{appId}/instances', 'get'>>(
    api().client.GET('/api/v1/apps/{appId}/instances', {
      params: { path: { appId }, query: archived ? { archived: true } : {} },
      ...(signal === undefined ? {} : { signal }),
    }),
  );
}

export function useAdminInstances(appId: number | null, archived: boolean) {
  return useQuery({
    queryKey: adminInstanceKeys.list(appId, archived),
    queryFn: ({ signal }) => listAdminInstances(appId ?? 0, archived, signal),
    enabled: appId !== null,
    staleTime: 5_000,
  });
}

/** Archives an instance (operator; 409 invalid_state while it is connected). */
export async function archiveInstance(instanceId: number): Promise<void> {
  await expectNoContent(
    api().client.POST('/api/v1/instances/{instanceId}/archive', {
      params: { path: { instanceId } },
    }),
  );
}

/** Deletes an archived, unconnected instance (admin; 409 invalid_state otherwise). */
export async function deleteInstance(instanceId: number): Promise<void> {
  await expectNoContent(
    api().client.DELETE('/api/v1/instances/{instanceId}', {
      params: { path: { instanceId } },
    }),
  );
}

/** Per-instance collection totals of [from, to) (`per_instance` of the collection health). */
export async function instanceCollection(
  appId: number,
  from: string,
  to: string,
  signal?: AbortSignal,
): Promise<Enveloped<InstanceCollection[]>> {
  const result = await unwrap<EnvelopeOf<'/api/v1/apps/{appId}/collection-health', 'get'>>(
    api().client.GET('/api/v1/apps/{appId}/collection-health', {
      params: { path: { appId }, query: { from, to } },
      ...(signal === undefined ? {} : { signal }),
    }),
  );
  return { data: result.data.per_instance, meta: result.meta };
}

export function useInstanceCollection(appId: number | null, from: string, to: string) {
  return useQuery({
    queryKey: adminInstanceKeys.collection(appId, from, to),
    queryFn: ({ signal }) => instanceCollection(appId ?? 0, from, to, signal),
    enabled: appId !== null,
    staleTime: 10_000,
  });
}

/** Sort keys of the collection ranking (05 §2.11-8). */
export const COLLECTION_SORT_KEYS = ['drops', 'lag_s', 'skew_ms', 'excluded'] as const;
export type CollectionSortKey = (typeof COLLECTION_SORT_KEYS)[number];

/**
 * The ranking: the largest value first (for the clock offset its absolute value), instances
 * without a value (null) last in name order, never ranked as 0.
 */
export function rankCollection(
  rows: readonly InstanceCollection[],
  key: CollectionSortKey,
): InstanceCollection[] {
  const measure = (row: InstanceCollection): number | null => {
    const value = row[key];
    if (value === null) {
      return null;
    }
    return key === 'skew_ms' ? Math.abs(value) : value;
  };
  return [...rows].sort((a, b) => {
    const va = measure(a);
    const vb = measure(b);
    if (va === null && vb === null) {
      return a.name.localeCompare(b.name);
    }
    if (va === null) {
      return 1;
    }
    if (vb === null) {
      return -1;
    }
    return vb - va || a.name.localeCompare(b.name);
  });
}

/**
 * The configuration hash most instances of the list share (the application's majority); null
 * when no instance reported one. A tie keeps the hash seen first.
 */
export function majorityConfigHash(instances: readonly Instance[]): string | null {
  const counts = new Map<string, number>();
  let best: string | null = null;
  let bestCount = 0;
  for (const instance of instances) {
    const hash = instance.config_hash;
    if (hash === null || hash === '') {
      continue;
    }
    const count = (counts.get(hash) ?? 0) + 1;
    counts.set(hash, count);
    if (count > bestCount) {
      best = hash;
      bestCount = count;
    }
  }
  return best;
}

/**
 * True when the instance's configuration hash differs from the application's majority: the
 * server's `config_drift` when it says so, else the comparison with the listed instances.
 */
export function hasConfigDrift(instance: Instance, majority: string | null): boolean {
  if (instance.config_drift !== null) {
    return instance.config_drift;
  }
  return majority !== null && instance.config_hash !== null && instance.config_hash !== majority;
}
