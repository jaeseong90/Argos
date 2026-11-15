// SPDX-License-Identifier: Apache-2.0
//
// Applications and the overview alerts used by the shell (docs/14 §4.2, §4.3): the app selector
// and the firing-alerts badge. Screens add their own call modules next to this one.
import { api, unwrap, type Enveloped, type EnvelopeOf } from './client';
import type { components } from './generated/schema';

export type App = components['schemas']['App'];

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
