// SPDX-License-Identifier: Apache-2.0
//
// The statistics API wrappers (docs/14 §4.8, WO-36): every list asks with the absolute range, the
// URL kind, `compare_prev=true`, Top N and the sort only when the list accepts it (never a 400 for
// a sort another list knows); 64-bit keys travel as decimal strings (C-175); the CSV export sends
// exactly the JSON request's parameters with `format=csv` (T-UI-004, DSN-15-06).
// Requirements (test/README.md §5): { type: 'req', description: 'F-U16' }, { type: 'req', description: 'F-U05' }
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { envelope, installApiStub, uninstallApiStub, type ApiStub } from '../../test/apiStub';
import { BIG_ID, FROM, FROM_ISO, TO, TO_ISO } from '../features/stats/testing/fixtures';

import {
  apiSort,
  defaultSort,
  effectiveSort,
  exportStatsCsv,
  getServiceStats,
  getSqlStats,
  listAppEvents,
  listErrorGroups,
  listExternalStats,
  listServiceStats,
  listSqlStats,
  serviceStatsQuery,
  sqlStatsQuery,
} from './stats';

const RANGE = { from: FROM, to: TO };

let stub: ApiStub;

beforeEach(() => {
  stub = installApiStub();
});

afterEach(() => {
  uninstallApiStub();
  vi.restoreAllMocks();
});

function query(route: string): Record<string, string> {
  const call = stub.calls(route).at(-1);
  return Object.fromEntries(call?.query.entries() ?? []);
}

describe('T-UI-004 statistics list requests (docs/14 §4.8)', () => {
  it('T-UI-004 maps the URL sort to the API sort only for the fields a list accepts', () => {
    expect(apiSort('services', 'p95_ms:desc')).toBe('p95_ms:desc');
    expect(apiSort('sql', 'p95_ms:desc')).toBe('p95_ms:desc');
    expect(apiSort('external', 'timeouts:desc')).toBe('timeouts:desc');
    // error_rate is a service sort, not a SQL one; errors is a SQL sort, not a service one.
    expect(apiSort('sql', 'error_rate:desc')).toBeUndefined();
    expect(apiSort('services', 'errors:desc')).toBeUndefined();
    expect(apiSort('errors', 'first_seen:asc')).toBe('first_seen:asc');
    expect(apiSort('services', undefined)).toBeUndefined();
    expect(apiSort('services', 'p95_ms')).toBeUndefined();
    expect(defaultSort('services')).toBe('sum_ms:desc');
    expect(defaultSort('errors')).toBe('count:desc');
    expect(effectiveSort('sql', 'apdex:desc')).toBe('sum_ms:desc');
  });

  it('T-UI-004 sends range, kind, sort, limit, split and compare_prev for the services list', async () => {
    stub.on('GET /api/v1/apps/3/services/stats', () => envelope([]));
    await listServiceStats({
      appId: 3,
      range: RANGE,
      kind: 'BATCH',
      sort: 'p95_ms:desc',
      limit: 50,
      splitVersion: true,
    });
    expect(query('GET /api/v1/apps/3/services/stats')).toEqual({
      from: FROM_ISO,
      to: TO_ISO,
      kind: 'BATCH',
      sort: 'p95_ms:desc',
      limit: '50',
      split_version: 'true',
      compare_prev: 'true',
    });
  });

  it('T-UI-004 leaves out the HTTP default kind, an unknown sort and split=false', () => {
    expect(
      serviceStatsQuery({
        appId: 3,
        range: RANGE,
        kind: 'HTTP',
        sort: 'fetch_rows:desc',
        limit: 100,
        splitVersion: false,
      }),
    ).toEqual({ from: FROM_ISO, to: TO_ISO, limit: 100, compare_prev: true });
  });

  it('T-UI-004 sends one JDBC target as db_id, a decimal string beyond 2^53 (C-175)', async () => {
    stub.on('GET /api/v1/apps/3/sql/stats', () => envelope([]));
    await listSqlStats({
      appId: 3,
      range: RANGE,
      kind: 'ALL',
      sort: 'errors:desc',
      limit: 10,
      dbId: BIG_ID,
    });
    expect(query('GET /api/v1/apps/3/sql/stats')).toEqual({
      from: FROM_ISO,
      to: TO_ISO,
      kind: 'ALL',
      sort: 'errors:desc',
      limit: '10',
      db_id: BIG_ID,
      compare_prev: 'true',
    });
    expect(
      sqlStatsQuery({ appId: 3, range: RANGE, kind: 'HTTP', limit: 100, dbId: null }),
    ).not.toHaveProperty('db_id');
  });

  it('T-UI-004 asks the external and error lists with their own parameters', async () => {
    stub.on('GET /api/v1/apps/3/external/stats', () => envelope([]));
    stub.on('GET /api/v1/apps/3/errors/groups', () => envelope([]));
    await listExternalStats({
      appId: 3,
      range: RANGE,
      kind: 'HTTP',
      sort: 'timeouts:desc',
      limit: 500,
    });
    await listErrorGroups({
      appId: 3,
      range: RANGE,
      kind: 'HTTP',
      sort: 'p95_ms:desc',
      limit: 100,
      newOnly: true,
    });
    expect(query('GET /api/v1/apps/3/external/stats')).toEqual({
      from: FROM_ISO,
      to: TO_ISO,
      sort: 'timeouts:desc',
      limit: '500',
      compare_prev: 'true',
    });
    // p95_ms is not an error-group sort: the server default applies.
    expect(query('GET /api/v1/apps/3/errors/groups')).toEqual({
      from: FROM_ISO,
      to: TO_ISO,
      limit: '100',
      new_only: 'true',
      compare_prev: 'true',
    });
  });

  it('T-UI-004 keeps detail keys as strings in the path and sends no list parameters', async () => {
    stub.on(`GET /api/v1/apps/3/services/${BIG_ID}`, () => envelope({ service_id: BIG_ID }));
    stub.on('GET /api/v1/apps/3/sql/21', () => envelope({ sql_id: '21' }));
    stub.on('GET /api/v1/apps/3/events', () => envelope([]));
    const service = await getServiceStats({
      appId: 3,
      range: RANGE,
      kind: 'MESSAGE',
      serviceId: BIG_ID,
    });
    await getSqlStats({ appId: 3, range: RANGE, kind: 'HTTP', sqlId: '21' });
    await listAppEvents({ appId: 3, range: RANGE });
    expect(service.data.service_id).toBe(BIG_ID);
    expect(query(`GET /api/v1/apps/3/services/${BIG_ID}`)).toEqual({
      from: FROM_ISO,
      to: TO_ISO,
      kind: 'MESSAGE',
    });
    expect(query('GET /api/v1/apps/3/sql/21')).toEqual({ from: FROM_ISO, to: TO_ISO });
    expect(query('GET /api/v1/apps/3/events')).toEqual({ from: FROM_ISO, to: TO_ISO });
  });
});

describe('T-UI-004 statistics CSV is the server export of the same request (DSN-15-06)', () => {
  it('T-UI-004 sends the JSON parameters plus format=csv and the time zone, and saves the file', async () => {
    stub.on(
      'GET /api/v1/apps/3/sql/stats',
      () =>
        new Response('﻿sql_id,count\r\n21,5000\r\n', {
          status: 200,
          headers: {
            'Content-Type': 'text/csv; charset=utf-8',
            'Content-Disposition': 'attachment; filename="argos-sql-stats.csv"',
          },
        }),
    );
    const request = {
      appId: 3,
      range: RANGE,
      kind: 'BATCH' as const,
      sort: 'count:desc',
      limit: 50,
      dbId: '31',
    };
    const clicks: string[] = [];
    const saved = { document, setTimeout: () => undefined };
    const createObjectURL = (): string => 'blob:argos-test';
    Object.defineProperty(URL, 'createObjectURL', { value: createObjectURL, configurable: true });
    Object.defineProperty(URL, 'revokeObjectURL', { value: () => undefined, configurable: true });
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function recordClick(
      this: HTMLAnchorElement,
    ) {
      clicks.push(this.download);
    });
    const fileName = await exportStatsCsv({ endpoint: 'sql', request }, saved);
    expect(fileName).toBe('argos-sql-stats.csv');
    expect(clicks).toEqual(['argos-sql-stats.csv']);
    const csv = query('GET /api/v1/apps/3/sql/stats');
    const { format, tz, ...rest } = csv;
    expect(format).toBe('csv');
    expect(tz).toBe(Intl.DateTimeFormat().resolvedOptions().timeZone);
    expect(rest).toEqual(
      Object.fromEntries(
        Object.entries(sqlStatsQuery(request)).map(([key, value]) => [key, String(value)]),
      ),
    );
    expect(stub.calls('GET /api/v1/apps/3/sql/stats')[0]?.headers.get('Accept')).toBe('text/csv');
  });
});
