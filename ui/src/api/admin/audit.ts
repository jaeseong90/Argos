// SPDX-License-Identifier: Apache-2.0
//
// Audit log of `/admin/audit` (docs/14 §4.14, docs/16 §9.6, §11, 15 §9.10, WO-39): records of a
// range (at most 366 days) with exact actor, action, target type and target id filters, newest
// first, in cursor pages of up to 500 rows; and the server's CSV of the same filters
// (`/admin/audit/export`, at most 10,000 rows, 10 exports a minute: 429 with Retry-After). The UI
// never builds the CSV (DSN-15-06).
import { api, unwrap, type Enveloped, type EnvelopeOf } from '../client';
import { exportCsv, type SaveFileEnvironment } from '../exportCsv';
import type { components } from '../generated/schema';

export type AuditRecord = components['schemas']['AuditRecord'];
export type AuditActor = components['schemas']['AuditActor'];
export type AuditTarget = components['schemas']['AuditTarget'];

/** Rows of a page (docs/14 §4.14: default 100, at most 500). */
export const AUDIT_PAGE_SIZE = 500;
/** Longest filter value (the URL keys are capped at 256 characters, plan §1.2). */
export const AUDIT_FILTER_MAX = 256;

/** The question of an audit request: absolute range (epoch ms) and the exact-match filters. */
export interface AuditFilter {
  readonly from: number;
  readonly to: number;
  readonly actor?: string | undefined;
  readonly action?: string | undefined;
  readonly targetType?: string | undefined;
  readonly targetId?: string | undefined;
}

interface AuditQuery {
  from: string;
  to: string;
  actor?: string;
  action?: string;
  target_type?: string;
  target_id?: string;
}

/** The API query of a filter (empty filters left out). */
export function auditQuery(filter: AuditFilter): AuditQuery {
  const query: AuditQuery = {
    from: new Date(filter.from).toISOString(),
    to: new Date(filter.to).toISOString(),
  };
  const put = (key: 'actor' | 'action' | 'target_type' | 'target_id', value?: string): void => {
    const trimmed = value?.trim() ?? '';
    if (trimmed !== '') {
      query[key] = trimmed;
    }
  };
  put('actor', filter.actor);
  put('action', filter.action);
  put('target_type', filter.targetType);
  put('target_id', filter.targetId);
  return query;
}

export const auditKeys = {
  all: () => ['admin', 'audit'] as const,
  list: (filter: AuditFilter) => ['admin', 'audit', 'list', auditQuery(filter)] as const,
};

/** `GET /admin/audit`: one page (cursor null for the first). */
export async function listAudit(
  filter: AuditFilter,
  cursor: string | null,
  signal?: AbortSignal,
): Promise<Enveloped<AuditRecord[]>> {
  const query: AuditQuery & { limit: number; cursor?: string } = {
    ...auditQuery(filter),
    limit: AUDIT_PAGE_SIZE,
  };
  if (cursor !== null) {
    query.cursor = cursor;
  }
  return unwrap<EnvelopeOf<'/api/v1/admin/audit', 'get'>>(
    api().client.GET('/api/v1/admin/audit', {
      params: { query },
      ...(signal === undefined ? {} : { signal }),
    }),
  );
}

/** Saves the server's CSV of the same filters; returns the file name. */
export async function exportAudit(
  filter: AuditFilter,
  env: SaveFileEnvironment = {},
): Promise<string> {
  // The operation has no path parameters; CsvRequest still names a `path` member (typed never for
  // a path-less operation), so none is given.
  return exportCsv(
    'get',
    '/api/v1/admin/audit/export',
    { path: undefined as never, query: auditQuery(filter) },
    env,
  );
}
