// SPDX-License-Identifier: Apache-2.0
//
// Server calls of the statistics screens (docs/14 §4.8, docs/15 §9.7, 05 §2.5, WO-36) through the
// generated client: the four lists (services, SQL, external hosts, error groups) and their details,
// plus the events of a range for the chart markers (docs/14 §4.12). No hand-written requests
// (03 §3.3); errors come back as ApiError (docs/14 §2.6).
//
// Rules of every list request: the range is absolute (ISO-8601 UTC), the kind is the URL kind, the
// sort is sent only when the endpoint accepts it (otherwise the server default applies, never a
// 400), and `compare_prev=true` asks the server for the change against the previous range of the
// same length (the UI never computes a change itself). 64-bit keys (service_id, sql_id, db_id,
// host_id, group_id, version_id) stay unsigned decimal strings (ADR-0010 C-175). The CSV of a list
// is the server export with the same parameters (DSN-15-06): the client never builds a CSV.
import { api, unwrap, type Enveloped, type EnvelopeOf } from './client';
import type { SaveFileEnvironment } from './exportCsv';
import type { components } from './generated/schema';

export type ServiceStats = components['schemas']['ServiceStats'];
export type ServiceDetail = components['schemas']['ServiceDetail'];
export type SqlStats = components['schemas']['SqlStats'];
export type SqlDetail = components['schemas']['SqlDetail'];
export type ExternalStats = components['schemas']['ExternalStats'];
export type ExternalDetail = components['schemas']['ExternalDetail'];
export type ErrorGroup = components['schemas']['ErrorGroup'];
export type ErrorGroupDetail = components['schemas']['ErrorGroupDetail'];
export type StatsBreakdown = components['schemas']['StatsBreakdown'];
export type StatsDelta = components['schemas']['StatsDelta'];
export type StatsSeries = components['schemas']['StatsSeries'];
export type RelatedTransaction = components['schemas']['RelatedTransaction'];
export type RelatedTransactions = components['schemas']['RelatedTransactions'];
export type DbTarget = components['schemas']['DbTarget'];
export type RawWindow = components['schemas']['RawWindow'];
export type AppEvent = components['schemas']['Event'];

/** Transaction kinds of the query (docs/14 §2.3; HTTP is the server default). */
export type StatsKind = 'HTTP' | 'BATCH' | 'MESSAGE' | 'ALL';

/** The four statistics lists. */
export type StatsEndpoint = 'services' | 'sql' | 'external' | 'errors';

/**
 * Sort fields each list accepts (docs/14 §4.8; `p95_ms` of SQL and external hosts is the WO-36
 * additive parameter, T-API-011a). The first one is the server default (descending).
 */
export const STATS_SORTS = {
  services: ['sum_ms', 'count', 'avg_ms', 'p95_ms', 'p99_ms', 'max_ms', 'apdex', 'error_rate'],
  sql: ['sum_ms', 'count', 'avg_ms', 'p95_ms', 'max_ms', 'errors'],
  external: ['sum_ms', 'count', 'avg_ms', 'p95_ms', 'max_ms', 'errors', 'timeouts'],
  errors: ['count', 'first_seen', 'last_seen'],
} as const satisfies Readonly<Record<StatsEndpoint, readonly string[]>>;

export type StatsSortField<E extends StatsEndpoint> = (typeof STATS_SORTS)[E][number];

/** The server's default sort of a list (docs/14 §4.8). */
export function defaultSort(endpoint: StatsEndpoint): string {
  return `${STATS_SORTS[endpoint][0]}:desc`;
}

/** True when `field` is a sort field of the list. */
export function isSortField(endpoint: StatsEndpoint, field: string): boolean {
  return (STATS_SORTS[endpoint] as readonly string[]).includes(field);
}

const SORT_FORM = /^([a-z_][a-z0-9_.]{0,63}):(asc|desc)$/;

/**
 * The API sort of a URL sort (`field:asc|desc`, the CommonQ form; `-p95_ms` is already read as
 * `p95_ms:desc`): the URL value when the list accepts its field, else undefined (the server's
 * default applies; a sort the list does not know would be a 400).
 */
export function apiSort(endpoint: StatsEndpoint, urlSort: string | undefined): string | undefined {
  if (urlSort === undefined) {
    return undefined;
  }
  const match = SORT_FORM.exec(urlSort);
  return match !== null && isSortField(endpoint, match[1] ?? '') ? urlSort : undefined;
}

/** The sort a list shows as selected: the accepted URL sort, else the server default. */
export function effectiveSort(endpoint: StatsEndpoint, urlSort: string | undefined): string {
  return apiSort(endpoint, urlSort) ?? defaultSort(endpoint);
}

/** An absolute range (epoch ms). */
export interface StatsRange {
  readonly from: number;
  readonly to: number;
}

/** Parameters of every list request. */
export interface StatsListRequest {
  readonly appId: number;
  readonly range: StatsRange;
  readonly kind: StatsKind;
  /** The URL sort (`field:asc|desc`); sent only when the list accepts it (apiSort). */
  readonly sort?: string | undefined;
  /** Top N (docs/14 §4.8: at most 500, default 100). */
  readonly limit: number;
}

export interface ServiceStatsRequest extends StatsListRequest {
  /** One row per service and application version (`split_version=true`). */
  readonly splitVersion: boolean;
}

export interface SqlStatsRequest extends StatsListRequest {
  /** One JDBC target (`db_id`, a decimal string), or null for every target. */
  readonly dbId: string | null;
}

export interface ErrorGroupsRequest extends StatsListRequest {
  /** Only groups first seen inside the range (`new_only=true`). */
  readonly newOnly: boolean;
}

/** Parameters of a detail request (`step` left to the server). */
export interface StatsDetailRequest {
  readonly appId: number;
  readonly range: StatsRange;
  readonly kind: StatsKind;
}

function iso(ms: number): string {
  return new Date(ms).toISOString();
}

interface BaseQuery {
  from: string;
  to: string;
  kind?: string;
}

function baseQuery(range: StatsRange, kind: StatsKind): BaseQuery {
  const query: BaseQuery = { from: iso(range.from), to: iso(range.to) };
  if (kind !== 'HTTP') {
    query.kind = kind;
  }
  return query;
}

interface ListQuery extends BaseQuery {
  sort?: string;
  limit?: number;
  compare_prev?: boolean;
}

function listQuery(endpoint: StatsEndpoint, request: StatsListRequest): ListQuery {
  const query: ListQuery = baseQuery(request.range, request.kind);
  const sort = apiSort(endpoint, request.sort);
  if (sort !== undefined) {
    query.sort = sort;
  }
  query.limit = request.limit;
  return query;
}

export interface ServiceStatsQuery extends ListQuery {
  split_version?: boolean;
}

/** The query of `GET /apps/{appId}/services/stats` as the generated client types it. */
export function serviceStatsQuery(request: ServiceStatsRequest): ServiceStatsQuery {
  const query: ServiceStatsQuery = { ...listQuery('services', request), compare_prev: true };
  if (request.splitVersion) {
    query.split_version = true;
  }
  return query;
}

export interface SqlStatsQuery extends ListQuery {
  db_id?: string;
}

/** The query of `GET /apps/{appId}/sql/stats`. */
export function sqlStatsQuery(request: SqlStatsRequest): SqlStatsQuery {
  const query: SqlStatsQuery = { ...listQuery('sql', request), compare_prev: true };
  if (request.dbId !== null) {
    query.db_id = request.dbId;
  }
  return query;
}

/** The query of `GET /apps/{appId}/external/stats`. */
export function externalStatsQuery(request: StatsListRequest): ListQuery {
  return { ...listQuery('external', request), compare_prev: true };
}

export interface ErrorGroupsQuery extends ListQuery {
  new_only?: boolean;
}

/**
 * The query of `GET /apps/{appId}/errors/groups`. `compare_prev` adds `delta.count` (the WO-36
 * additive parameter of the error list, T-API-011b).
 */
export function errorGroupsQuery(request: ErrorGroupsRequest): ErrorGroupsQuery {
  const query: ErrorGroupsQuery = { ...listQuery('errors', request), compare_prev: true };
  if (request.newOnly) {
    query.new_only = true;
  }
  return query;
}

function withSignal(signal: AbortSignal | undefined): { signal?: AbortSignal } {
  return signal === undefined ? {} : { signal };
}

type ServicesEnvelope = EnvelopeOf<'/api/v1/apps/{appId}/services/stats', 'get'>;
type ServiceEnvelope = EnvelopeOf<'/api/v1/apps/{appId}/services/{serviceId}', 'get'>;
type SqlListEnvelope = EnvelopeOf<'/api/v1/apps/{appId}/sql/stats', 'get'>;
type SqlEnvelope = EnvelopeOf<'/api/v1/apps/{appId}/sql/{sqlId}', 'get'>;
type ExternalListEnvelope = EnvelopeOf<'/api/v1/apps/{appId}/external/stats', 'get'>;
type ExternalEnvelope = EnvelopeOf<'/api/v1/apps/{appId}/external/{hostId}', 'get'>;
type ErrorListEnvelope = EnvelopeOf<'/api/v1/apps/{appId}/errors/groups', 'get'>;
type ErrorEnvelope = EnvelopeOf<'/api/v1/apps/{appId}/errors/groups/{groupId}', 'get'>;
type EventsEnvelope = EnvelopeOf<'/api/v1/apps/{appId}/events', 'get'>;

/** `GET /apps/{appId}/services/stats` (docs/14 §4.8). */
export async function listServiceStats(
  request: ServiceStatsRequest,
  signal?: AbortSignal,
): Promise<Enveloped<ServiceStats[]>> {
  return unwrap<ServicesEnvelope>(
    api().client.GET('/api/v1/apps/{appId}/services/stats', {
      params: { path: { appId: request.appId }, query: serviceStatsQuery(request) },
      ...withSignal(signal),
    }),
  );
}

/** `GET /apps/{appId}/services/{serviceId}`: series, versions, instances and the cross view. */
export async function getServiceStats(
  request: StatsDetailRequest & { readonly serviceId: string },
  signal?: AbortSignal,
): Promise<Enveloped<ServiceDetail>> {
  return unwrap<ServiceEnvelope>(
    api().client.GET('/api/v1/apps/{appId}/services/{serviceId}', {
      params: {
        path: { appId: request.appId, serviceId: request.serviceId },
        query: baseQuery(request.range, request.kind),
      },
      ...withSignal(signal),
    }),
  );
}

/** `GET /apps/{appId}/sql/stats` (complete counts, percentiles estimated, C-14). */
export async function listSqlStats(
  request: SqlStatsRequest,
  signal?: AbortSignal,
): Promise<Enveloped<SqlStats[]>> {
  return unwrap<SqlListEnvelope>(
    api().client.GET('/api/v1/apps/{appId}/sql/stats', {
      params: { path: { appId: request.appId }, query: sqlStatsQuery(request) },
      ...withSignal(signal),
    }),
  );
}

/** `GET /apps/{appId}/sql/{sqlId}`: text, series, services, instances, targets, related tx. */
export async function getSqlStats(
  request: StatsDetailRequest & { readonly sqlId: string },
  signal?: AbortSignal,
): Promise<Enveloped<SqlDetail>> {
  return unwrap<SqlEnvelope>(
    api().client.GET('/api/v1/apps/{appId}/sql/{sqlId}', {
      params: {
        path: { appId: request.appId, sqlId: request.sqlId },
        query: baseQuery(request.range, request.kind),
      },
      ...withSignal(signal),
    }),
  );
}

/** `GET /apps/{appId}/external/stats` (per host, percentiles estimated, C-14). */
export async function listExternalStats(
  request: StatsListRequest,
  signal?: AbortSignal,
): Promise<Enveloped<ExternalStats[]>> {
  return unwrap<ExternalListEnvelope>(
    api().client.GET('/api/v1/apps/{appId}/external/stats', {
      params: { path: { appId: request.appId }, query: externalStatsQuery(request) },
      ...withSignal(signal),
    }),
  );
}

/** `GET /apps/{appId}/external/{hostId}`: series, calling services, related transactions. */
export async function getExternalStats(
  request: StatsDetailRequest & { readonly hostId: string },
  signal?: AbortSignal,
): Promise<Enveloped<ExternalDetail>> {
  return unwrap<ExternalEnvelope>(
    api().client.GET('/api/v1/apps/{appId}/external/{hostId}', {
      params: {
        path: { appId: request.appId, hostId: request.hostId },
        query: baseQuery(request.range, request.kind),
      },
      ...withSignal(signal),
    }),
  );
}

/** `GET /apps/{appId}/errors/groups` (count, first and last seen, is_new, top services). */
export async function listErrorGroups(
  request: ErrorGroupsRequest,
  signal?: AbortSignal,
): Promise<Enveloped<ErrorGroup[]>> {
  return unwrap<ErrorListEnvelope>(
    api().client.GET('/api/v1/apps/{appId}/errors/groups', {
      params: { path: { appId: request.appId }, query: errorGroupsQuery(request) },
      ...withSignal(signal),
    }),
  );
}

/** `GET /apps/{appId}/errors/groups/{groupId}`: sample stack, distributions, recent 10. */
export async function getErrorGroup(
  request: StatsDetailRequest & { readonly groupId: string },
  signal?: AbortSignal,
): Promise<Enveloped<ErrorGroupDetail>> {
  return unwrap<ErrorEnvelope>(
    api().client.GET('/api/v1/apps/{appId}/errors/groups/{groupId}', {
      params: {
        path: { appId: request.appId, groupId: request.groupId },
        query: baseQuery(request.range, request.kind),
      },
      ...withSignal(signal),
    }),
  );
}

/** `GET /apps/{appId}/events` of a range (newest first, at most 1,000; docs/14 §4.12). */
export async function listAppEvents(
  request: { readonly appId: number; readonly range: StatsRange },
  signal?: AbortSignal,
): Promise<Enveloped<AppEvent[]>> {
  return unwrap<EventsEnvelope>(
    api().client.GET('/api/v1/apps/{appId}/events', {
      params: {
        path: { appId: request.appId },
        query: { from: iso(request.range.from), to: iso(request.range.to) },
      },
      ...withSignal(signal),
    }),
  );
}

/** A list request with the endpoint it belongs to (the CSV export of that list). */
export type StatsCsvRequest =
  | { readonly endpoint: 'services'; readonly request: ServiceStatsRequest }
  | { readonly endpoint: 'sql'; readonly request: SqlStatsRequest }
  | { readonly endpoint: 'external'; readonly request: StatsListRequest }
  | { readonly endpoint: 'errors'; readonly request: ErrorGroupsRequest };

/**
 * Server CSV export of a list with exactly the parameters of its JSON request (`format=csv`,
 * audited EXPORT_CSV, formula guard and 10,000-row cap on the server; DSN-15-06, T-UI-004).
 * Returns the saved file name.
 */
export async function exportStatsCsv(
  csv: StatsCsvRequest,
  env: SaveFileEnvironment = {},
): Promise<string> {
  // Loaded on click: the download helper stays out of the screen chunk (ADR-0010 C-209).
  const { exportCsv } = await import('./exportCsv');
  switch (csv.endpoint) {
    case 'services':
      return exportCsv(
        'get',
        '/api/v1/apps/{appId}/services/stats',
        { path: { appId: csv.request.appId }, query: serviceStatsQuery(csv.request) },
        env,
      );
    case 'sql':
      return exportCsv(
        'get',
        '/api/v1/apps/{appId}/sql/stats',
        { path: { appId: csv.request.appId }, query: sqlStatsQuery(csv.request) },
        env,
      );
    case 'external':
      return exportCsv(
        'get',
        '/api/v1/apps/{appId}/external/stats',
        { path: { appId: csv.request.appId }, query: externalStatsQuery(csv.request) },
        env,
      );
    case 'errors':
      return exportCsv(
        'get',
        '/api/v1/apps/{appId}/errors/groups',
        { path: { appId: csv.request.appId }, query: errorGroupsQuery(csv.request) },
        env,
      );
  }
}
