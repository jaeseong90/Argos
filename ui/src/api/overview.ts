// SPDX-License-Identifier: Apache-2.0
//
// Server calls of the overview (docs/14 §4.2, docs/15 §9.1, WO-31) through the generated client:
// every application with its status, last-5-minute values and one-hour sparklines plus the server
// summary (`GET /overview`, cached 5 s on the server), and the active (FIRING and ACKED) alerts of
// every application (`GET /overview/alerts`). The hooks poll every 10 s (DSN-15-14) and stop while the tab is hidden
// (TanStack Query does not run interval refetches in the background). No hand-written requests
// (03 §3.3); errors come back as ApiError (docs/14 §2.6).
import { useQuery, type UseQueryResult } from '@tanstack/react-query';

import type { Kind } from '../state/urlState';

import { api, unwrap, type Enveloped, type EnvelopeOf } from './client';
import type { components } from './generated/schema';

type OverviewEnvelope = EnvelopeOf<'/api/v1/overview', 'get'>;
type OverviewAlertsEnvelope = EnvelopeOf<'/api/v1/overview/alerts', 'get'>;

export type Overview = components['schemas']['Overview'];
export type OverviewApp = components['schemas']['OverviewApp'];
export type OverviewSparkline = components['schemas']['OverviewSparkline'];
export type ServerHealth = components['schemas']['ServerHealth'];

/** REST refresh of the overview (docs/15 §9.1 "10초 폴링"; the server caches 5 s). */
export const OVERVIEW_REFRESH_MS = 10_000;

/** Parameters of `GET /overview` (the API takes no time range: last 5 minutes and 1 h trend). */
export interface OverviewRequest {
  readonly env?: string | undefined;
  readonly group?: string | undefined;
  readonly kind: Kind;
}

interface OverviewQuery {
  env?: string;
  group?: string;
  kind?: string;
}

/** The query of a request as the generated client types it (HTTP, the default, left out). */
export function overviewQuery(request: OverviewRequest): OverviewQuery {
  const query: OverviewQuery = {};
  if (request.env !== undefined) {
    query.env = request.env;
  }
  if (request.group !== undefined) {
    query.group = request.group;
  }
  if (request.kind !== 'HTTP') {
    query.kind = request.kind;
  }
  return query;
}

/** `GET /overview`: at most 500 applications (meta.truncated beyond). */
export async function getOverview(
  request: OverviewRequest,
  signal?: AbortSignal,
): Promise<Enveloped<Overview>> {
  return unwrap<OverviewEnvelope>(
    api().client.GET('/api/v1/overview', {
      params: { query: overviewQuery(request) },
      ...(signal === undefined ? {} : { signal }),
    }),
  );
}

/**
 * `GET /overview/alerts`: the active alerts of every application, FIRING and ACKED (docs/14 §4.2
 * and §4.13 event schema, at most 1,000; WO-25). No state filter, so the list is the one
 * `alerts_firing_total` counts. The items stay unknown here: the panel validates them.
 */
export async function listFiringAlerts(
  env: string | undefined,
  signal?: AbortSignal,
): Promise<readonly unknown[]> {
  const query: { env?: string } = {};
  if (env !== undefined) {
    query.env = env;
  }
  const result = await unwrap<OverviewAlertsEnvelope>(
    api().client.GET('/api/v1/overview/alerts', {
      params: { query },
      ...(signal === undefined ? {} : { signal }),
    }),
  );
  return result.data;
}

/** Cache key of an overview question (same question, same entry). */
export function overviewKey(request: OverviewRequest) {
  return [
    'overview',
    { env: request.env ?? null, group: request.group ?? null, kind: request.kind },
  ] as const;
}

/**
 * The overview of a question, refetched every 10 s while the tab is visible. The previous answer
 * stays on screen while a changed question loads (no blank table between two filters).
 */
export function useOverviewQuery(request: OverviewRequest): UseQueryResult<Enveloped<Overview>> {
  return useQuery({
    queryKey: overviewKey(request),
    queryFn: ({ signal }) => getOverview(request, signal),
    refetchInterval: OVERVIEW_REFRESH_MS,
    refetchIntervalInBackground: false,
    placeholderData: (previous) => previous,
  });
}

/**
 * The active (FIRING and ACKED) alerts of every application, only while the server evaluates
 * alerts (`alerts_firing_total` not null; `enabled` false otherwise, so nothing is requested).
 */
export function useFiringAlertsQuery(
  env: string | undefined,
  enabled: boolean,
): UseQueryResult<readonly unknown[]> {
  return useQuery({
    queryKey: ['overview', 'alerts', 'active', env ?? null] as const,
    queryFn: ({ signal }) => listFiringAlerts(env, signal),
    enabled,
    refetchInterval: OVERVIEW_REFRESH_MS,
    refetchIntervalInBackground: false,
  });
}
