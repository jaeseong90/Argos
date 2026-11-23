// SPDX-License-Identifier: Apache-2.0
//
// Running transactions of an application (docs/14 §4.9 `GET /apps/{appId}/active`, docs/14 §5
// WS `active` topic, docs/15 §9.6, WO-35). The REST call goes through the generated client; the
// `data` of an `active` push (the same schema, every 5 s, latest only) is validated here with zod
// and its collected strings are marked untrusted (SEC-60). The screen always asks for every kind
// (`kind=ALL`): the per-instance counts and buckets are app-wide anyway, and the kind filter of the
// list is applied in the browser (docs/15 §10 "스냅샷 교체", plan assumption 1).
import { useQuery, type UseQueryResult } from '@tanstack/react-query';

import { z } from '../app/zod';

import { api, unwrap, type Enveloped, type EnvelopeOf } from './client';
import type { components } from './generated/schema';
import { untrusted } from './untrusted';

type ActiveEnvelope = EnvelopeOf<'/api/v1/apps/{appId}/active', 'get'>;

export type ActiveTransactions = components['schemas']['ActiveTransactions'];
export type ActiveInstance = components['schemas']['ActiveInstance'];
export type ActiveItem = components['schemas']['ActiveTransaction'];

/** Snapshot period of the agents and of the `active` topic (docs/14 §5.3). */
export const ACTIVE_REFRESH_MS = 5_000;
/** Items the server sends at most (docs/14 §4.9). */
export const ACTIVE_MAX_ITEMS = 2_000;
/** Instances accepted from one push (an application has far fewer live sessions). */
export const ACTIVE_MAX_INSTANCES = 1_000;

/** States of a running transaction (docs/11 ActiveState; ASYNC is the fifth, IN_ASYNC). */
export const ACTIVE_STATES = ['SQL', 'HTTP', 'DBC', 'METHOD', 'ASYNC'] as const;
export type ActiveState = (typeof ACTIVE_STATES)[number];

/** `GET /apps/{appId}/active?kind=ALL`: the latest snapshot of every live session. */
export async function getActive(
  appId: number,
  signal?: AbortSignal,
): Promise<Enveloped<ActiveTransactions>> {
  return unwrap<ActiveEnvelope>(
    api().client.GET('/api/v1/apps/{appId}/active', {
      params: { path: { appId }, query: { kind: 'ALL' } },
      ...(signal === undefined ? {} : { signal }),
    }),
  );
}

/** Cache key of the active snapshot of an application. */
export function activeKey(appId: number | null) {
  return ['active', { appId }] as const;
}

/**
 * Refetch interval of the REST snapshot: none while the WS topic delivers, every 5 s when the
 * screen falls back to polling (30 s without a server message, docs/15 §10).
 */
export function activeRefetchInterval(polling: boolean): number | false {
  return polling ? ACTIVE_REFRESH_MS : false;
}

/**
 * The REST snapshot of an application. It loads once (the WS topic replaces it afterwards) and is
 * refetched every 5 s only while `polling`; TanStack Query does not run interval refetches while the
 * tab is hidden (refetchIntervalInBackground false).
 */
export function useActiveQuery(
  appId: number | null,
  polling: boolean,
): UseQueryResult<Enveloped<ActiveTransactions>> {
  return useQuery({
    queryKey: activeKey(appId),
    queryFn: ({ signal }) => {
      if (appId === null) {
        throw new Error('no application');
      }
      return getActive(appId, signal);
    },
    enabled: appId !== null,
    refetchInterval: activeRefetchInterval(polling),
    refetchIntervalInBackground: false,
    refetchOnWindowFocus: false,
    staleTime: Infinity,
  });
}

// ---------------------------------------------------------------------------------------------
// WS `active` payload (docs/14 §5.2: the schema of GET /apps/{appId}/active)
// ---------------------------------------------------------------------------------------------

/** A nullable field that may also be left out; both mean null. */
function nullish<T extends z.ZodType>(schema: T) {
  return schema
    .nullable()
    .optional()
    .transform((value) => value ?? null);
}

/** A collected string (render through SafeText only). */
function collected(max: number) {
  return nullish(z.string().max(max).transform(untrusted));
}

const Instant = z.string().max(40);
const Id64 = z.string().regex(/^[0-9]{1,20}$/);
const Code = z.string().max(16);

const ActiveInstanceData = z.object({
  instance_id: z.number().int(),
  name: collected(1024),
  state: Code,
  snapshot_at: nullish(Instant),
  count: z.number().int().nonnegative(),
  max_elapsed_ms: nullish(z.number().nonnegative()),
  buckets: z.array(z.number().int().nonnegative()).length(4),
  truncated: z.boolean(),
});

const ActiveItemData = z.object({
  instance_id: z.number().int(),
  tx_id: Id64,
  service: collected(4096),
  service_id: nullish(Id64),
  elapsed_ms: z.number().nonnegative(),
  state: Code,
  // The SQL is cut at 2 KB by the agent; the limit leaves room for multi-byte text.
  state_detail: collected(8192),
  thread: collected(1024),
  virtual_thread: z.boolean().optional().default(false),
  user_id: collected(1024),
  user_id_mode: nullish(Code),
  remote_ip: nullish(z.string().max(64)),
  ip_mode: nullish(Code),
  kind: Code,
  started_at: Instant,
});

const ActivePushData = z.object({
  snapshot_at: nullish(Instant),
  per_instance: z.array(ActiveInstanceData).max(ACTIVE_MAX_INSTANCES),
  items: z.array(ActiveItemData).max(ACTIVE_MAX_ITEMS),
});

/**
 * The snapshot of an `active` push, or null when the payload does not validate (the frame is then
 * dropped as a whole, never partly applied).
 */
export function parseActivePush(data: unknown): ActiveTransactions | null {
  const parsed = ActivePushData.safeParse(data);
  if (!parsed.success) {
    return null;
  }
  const snapshot: ActiveTransactions = parsed.data;
  return snapshot;
}
