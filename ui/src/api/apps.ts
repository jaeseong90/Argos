// SPDX-License-Identifier: Apache-2.0
//
// Applications and the overview alerts used by the shell (docs/14 §4.2, §4.3): the app selector
// and the firing-alerts badge, the application packages of the stack views (ADR-0010 C-207 (3)),
// and the setup wizard's first application with its agent token (docs/14 §4.14, C-168 (2)).
// Screens add their own call modules next to this one.
import { useQuery } from '@tanstack/react-query';

import { api, unwrap, type Enveloped, type EnvelopeOf } from './client';
import type { components } from './generated/schema';
import { queryKeys } from './queryKeys';

export type App = components['schemas']['App'];
export type IssuedAgentToken = components['schemas']['IssuedTokenResponse'];

export interface AppListFilter {
  readonly env?: string | undefined;
  readonly group?: string | undefined;
  readonly q?: string | undefined;
}

/** Applications by name, at most 500 (meta.truncated beyond, docs/14 §4.3). */
export async function listApps(filter: AppListFilter = {}): Promise<Enveloped<App[]>> {
  const query: AppListFilter = {};
  if (filter.env !== undefined) Object.assign(query, { env: filter.env });
  if (filter.group !== undefined) Object.assign(query, { group: filter.group });
  if (filter.q !== undefined) Object.assign(query, { q: filter.q });
  return unwrap<EnvelopeOf<'/api/v1/apps', 'get'>>(
    api().client.GET('/api/v1/apps', { params: { query } }),
  );
}

/** One application (404 not_found for an unknown id). */
export async function getApp(appId: number): Promise<App> {
  return (
    await unwrap<EnvelopeOf<'/api/v1/apps/{appId}', 'get'>>(
      api().client.GET('/api/v1/apps/{appId}', { params: { path: { appId } } }),
    )
  ).data;
}

const NO_PACKAGES: readonly string[] = [];

/**
 * The application's own Java packages (`app_packages` of GET /apps/{appId}, ADR-0010 C-207 (3)):
 * the stack views put frames of these packages in bold. The same query (key and freshness) as the
 * application layout's, so it reads the cached answer. Empty while loading, on an error and when
 * an administrator set none: the client never guesses them.
 */
export function useAppPackages(appId: number | null): readonly string[] {
  const query = useQuery({
    queryKey: queryKeys.apps.detail(appId ?? 0),
    queryFn: () => getApp(appId ?? 0),
    enabled: appId !== null,
    staleTime: 60_000,
    retry: false,
  });
  return query.data?.app_packages ?? NO_PACKAGES;
}

export interface CreateAppInput {
  readonly name: string;
  readonly env: string;
}

/**
 * Registers an application (admin, 201; 409 already_exists for a taken name and environment,
 * docs/14 §4.3, ADR-0010 C-153). Apdex T and the registration policy keep the server defaults.
 */
export async function createApp(input: CreateAppInput): Promise<App> {
  return (
    await unwrap<EnvelopeOf<'/api/v1/apps', 'post'>>(
      api().client.POST('/api/v1/apps', { body: { name: input.name, env: input.env } }),
    )
  ).data;
}

/**
 * Issues an agent token for an application (admin, 201, docs/14 §4.14, 16 §2.1) with the server's
 * default expiry. The answer carries the token once (no-store): callers keep it in component state
 * only, never in the query cache, browser storage or the URL.
 */
export async function issueAgentToken(appId: number): Promise<IssuedAgentToken> {
  return (
    await unwrap<EnvelopeOf<'/api/v1/admin/apps/{appId}/tokens', 'post'>>(
      api().client.POST('/api/v1/admin/apps/{appId}/tokens', { params: { path: { appId } } }),
    )
  ).data;
}

/**
 * FIRING alert events of every application (docs/14 §4.13 schema; an empty list until the alert
 * engine exists, WO-25). The shell only counts them.
 */
export async function countFiringAlerts(env?: string): Promise<number> {
  const query: { state: string; env?: string } = { state: 'FIRING' };
  if (env !== undefined) {
    query.env = env;
  }
  const result = await unwrap<EnvelopeOf<'/api/v1/overview/alerts', 'get'>>(
    api().client.GET('/api/v1/overview/alerts', { params: { query } }),
  );
  return result.data.length;
}
