// SPDX-License-Identifier: Apache-2.0
//
// Server calls of the application dashboard (docs/14 §4.4, docs/15 §9.2, WO-32) through the
// generated client: the dashboard of a range (tiles, delta, series, baseline, events, outages,
// instances, collection, firing) and the instance comparison (JSON, or the server CSV export,
// DSN-15-06). No hand-written requests (03 §3.3); errors come back as ApiError (docs/14 §2.6).
import { api, unwrap, type Enveloped, type EnvelopeOf } from './client';
import type { SaveFileEnvironment } from './exportCsv';
import type { components } from './generated/schema';
import { untrustedEntries, type Untrusted } from './untrusted';

type DashboardEnvelope = EnvelopeOf<'/api/v1/apps/{appId}/dashboard', 'get'>;
type CompareEnvelope = EnvelopeOf<'/api/v1/apps/{appId}/instances/compare', 'get'>;
type CollectionHealthEnvelope = EnvelopeOf<'/api/v1/apps/{appId}/collection-health', 'get'>;

export type Dashboard = components['schemas']['Dashboard'];
export type DashboardInstance = components['schemas']['DashboardInstance'];
export type DashboardCollection = components['schemas']['DashboardCollection'];
export type InstanceComparison = components['schemas']['InstanceComparison'];
export type DashboardEvent = components['schemas']['Event'];
export type DashboardOutage = components['schemas']['Outage'];

/** Instance filter of the dashboard: at most 100 ids (docs/14 dashboard_get `instance`). */
export const DASHBOARD_INSTANCE_MAX = 100;

/** Transaction kinds of the query (docs/14 §2.3; HTTP is the server default). */
export type DashboardKind = 'HTTP' | 'BATCH' | 'MESSAGE' | 'ALL';

/** Parameters of `GET /apps/{appId}/dashboard` with an absolute range (epoch ms). */
export interface DashboardRequest {
  readonly appId: number;
  readonly from: number;
  readonly to: number;
  readonly kind: DashboardKind;
  /** Instance ids (f.instance), at most DASHBOARD_INSTANCE_MAX; empty = every instance. */
  readonly instances: readonly number[];
  /**
   * Instance ids left out (f.instance=!id, `exclude_instance`, ADR-0010 C-212 (1)), at most
   * DASHBOARD_INSTANCE_MAX, never also in `instances` (the server answers 400 for that).
   */
  readonly excluded: readonly number[];
  /** Comparison baseline; the server's delta basis is 7d only with baseline=7d. */
  readonly baseline: '1d' | '7d' | null;
}

interface DashboardQuery {
  from: string;
  to: string;
  kind?: string;
  baseline?: string;
  instance?: number[];
  exclude_instance?: number[];
}

/** The query of a request as the generated client types it (defaults left out). */
export function dashboardQuery(request: DashboardRequest): DashboardQuery {
  const query: DashboardQuery = {
    from: new Date(request.from).toISOString(),
    to: new Date(request.to).toISOString(),
  };
  if (request.kind !== 'HTTP') {
    query.kind = request.kind;
  }
  if (request.baseline !== null) {
    query.baseline = request.baseline;
  }
  if (request.instances.length > 0) {
    query.instance = request.instances.slice(0, DASHBOARD_INSTANCE_MAX);
  }
  if (request.excluded.length > 0) {
    query.exclude_instance = request.excluded.slice(0, DASHBOARD_INSTANCE_MAX);
  }
  return query;
}

/** `GET /apps/{appId}/dashboard` (range ≤ 1 year, ≤ 720 buckets; docs/14 §4.4). */
export async function getDashboard(
  request: DashboardRequest,
  signal?: AbortSignal,
): Promise<Enveloped<Dashboard>> {
  return unwrap<DashboardEnvelope>(
    api().client.GET('/api/v1/apps/{appId}/dashboard', {
      params: { path: { appId: request.appId }, query: dashboardQuery(request) },
      ...(signal === undefined ? {} : { signal }),
    }),
  );
}

/** Parameters of `GET /apps/{appId}/instances/compare` (epoch ms). */
export interface CompareRequest {
  readonly appId: number;
  readonly from: number;
  readonly to: number;
  readonly kind: DashboardKind;
}

function compareQuery(request: CompareRequest): { from: string; to: string; kind?: string } {
  const query: { from: string; to: string; kind?: string } = {
    from: new Date(request.from).toISOString(),
    to: new Date(request.to).toISOString(),
  };
  if (request.kind !== 'HTTP') {
    query.kind = request.kind;
  }
  return query;
}

/**
 * Per-instance comparison of a range with the server's outlier flags (ADR-0010 C-177: the UI
 * never recomputes them). Every instance of the application (≤ 10,000 rows), so sorting on the
 * client is allowed (docs/15 §6.3 "클라이언트 정렬은 응답이 전체일 때만").
 */
export async function compareInstances(
  request: CompareRequest,
  signal?: AbortSignal,
): Promise<Enveloped<InstanceComparison[]>> {
  return unwrap<CompareEnvelope>(
    api().client.GET('/api/v1/apps/{appId}/instances/compare', {
      params: { path: { appId: request.appId }, query: compareQuery(request) },
      ...(signal === undefined ? {} : { signal }),
    }),
  );
}

/** Excluded requests of a range by reason (D-19, docs/14 collection_health_get `excluded`). */
export interface ExcludedReasons {
  /** Reason codes as sent (collected strings: render through SafeText) with their counts. */
  readonly byReason: readonly (readonly [reason: Untrusted, count: number])[];
}

/**
 * The excluded-request breakdown of `GET /apps/{appId}/collection-health` for the dashboard's
 * collection panel (the tooltip of "N requests excluded", 05 §2.0-8). Reasons are sorted by count,
 * largest first.
 */
export async function excludedReasons(
  request: Omit<CompareRequest, 'kind'>,
  signal?: AbortSignal,
): Promise<ExcludedReasons> {
  const result = await unwrap<CollectionHealthEnvelope>(
    api().client.GET('/api/v1/apps/{appId}/collection-health', {
      params: {
        path: { appId: request.appId },
        query: {
          from: new Date(request.from).toISOString(),
          to: new Date(request.to).toISOString(),
        },
      },
      ...(signal === undefined ? {} : { signal }),
    }),
  );
  const byReason = untrustedEntries(result.data.excluded.by_reason)
    .filter(([, count]) => typeof count === 'number' && Number.isFinite(count))
    .sort((a, b) => b[1] - a[1] || (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0));
  return { byReason };
}

/**
 * Server CSV export of the comparison (`format=csv`, audited EXPORT_CSV, formula guard on the
 * server; DSN-15-06). Returns the saved file name.
 */
export async function exportCompareCsv(
  request: CompareRequest,
  env: SaveFileEnvironment = {},
): Promise<string> {
  // Loaded on click: the download helper stays out of the initial path (ADR-0010 C-209).
  const { exportCsv } = await import('./exportCsv');
  return exportCsv(
    'get',
    '/api/v1/apps/{appId}/instances/compare',
    { path: { appId: request.appId }, query: compareQuery(request) },
    env,
  );
}
