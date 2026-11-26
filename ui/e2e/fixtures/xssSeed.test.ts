// SPDX-License-Identifier: Apache-2.0
//
// Tooling test of the XSS seed sweep (WO-46, T-SEC-62; e2e/fixtures/xssSeed.ts): the sweep plan
// covers every shell route of e2e/specs/routes.ts and nothing else (a route added without a plan
// entry fails here and in the spec, routes.test.ts style), the location list is the union of
// docs/18 §4.3 and docs/16 §9.4, and the pure helpers build what the spec opens and runs.
import { describe, expect, it } from 'vitest';

import { SHELL_ROUTES } from '../specs/routes';

import {
  INJECTED_SELECTOR,
  payloadStrings,
  planCoverage,
  sweepPath,
  XSS_LOCATIONS,
  XSS_ROUTE_PLAN,
  xssShopJvmArgs,
  xssSmokeArgs,
  type RouteParams,
} from './xssSeed';

const PARAMS: RouteParams = {
  appId: 11,
  txId: '238412882780461056',
  txTs: '2026-11-25T22:43:50.376Z',
  instanceId: 51,
  serviceId: '18048090433217914466',
  sqlId: '2584631303582080396',
  hostId: '3544584415226672071',
  groupId: '378907820698805404',
  traceId: '6b0764a7c015591bda1bdfcfdba59bd7',
  traceTs: '2026-11-25T22:10:00.000Z',
  ruleId: '6c3c34e1-1e9d-4215-a216-05e91ec51b5f',
  from: '2026-11-25T22:42:49Z',
  to: '2026-11-25T22:45:09Z',
};

describe('XSS sweep plan', () => {
  it('covers every shell route of routes.ts and nothing else', () => {
    expect(planCoverage(SHELL_ROUTES.map((r) => r.pattern))).toEqual({ unplanned: [], stale: [] });
  });

  it('reports a route added without a plan entry and an entry of no route', () => {
    const routes = [...SHELL_ROUTES.map((r) => r.pattern), '/apps/:appId/new-screen'];
    const plan = { ...XSS_ROUTE_PLAN, '/gone': { scope: 'once' as const } };
    expect(planCoverage(routes, plan)).toEqual({
      unplanned: ['/apps/:appId/new-screen'],
      stale: ['/gone'],
    });
  });

  it('visits the app routes for every swept application and the others once', () => {
    for (const [pattern, visit] of Object.entries(XSS_ROUTE_PLAN)) {
      expect(visit.scope, pattern).toBe(pattern.startsWith('/apps/:appId/') ? 'per-app' : 'once');
    }
    expect(XSS_ROUTE_PLAN['/apps/:appId/transactions/:txId']?.tabs).toEqual([
      'timeline',
      'tree',
      'sql',
      'ext',
      'error',
      'stack',
      'headers',
    ]);
    expect(XSS_ROUTE_PLAN['/apps/:appId/instances/:instanceId']?.tabs).toEqual([
      'metrics',
      'env',
      'commands',
      'dumps',
    ]);
  });
});

describe('sweepPath', () => {
  it('fills every parameter and adds the range, the ts hint and the tab', () => {
    expect(sweepPath('/apps/:appId/transactions/:txId', PARAMS, 'headers')).toBe(
      '/apps/11/transactions/238412882780461056?from=2026-11-25T22%3A42%3A49Z&to=2026-11-25T22%3A45%3A09Z&ts=2026-11-25T22%3A43%3A50.376Z&tab=headers',
    );
    expect(sweepPath('/traces/:traceId', PARAMS)).toBe(
      '/traces/6b0764a7c015591bda1bdfcfdba59bd7?ts=2026-11-25T22%3A10%3A00.000Z',
    );
    expect(sweepPath('/alerts/rules/:ruleId', PARAMS)).toBe(
      '/alerts/rules/6c3c34e1-1e9d-4215-a216-05e91ec51b5f',
    );
    expect(sweepPath('/admin/audit', PARAMS)).toBe('/admin/audit');
  });

  it('opens every route of the plan with its parameters', () => {
    for (const pattern of Object.keys(XSS_ROUTE_PLAN)) {
      expect(sweepPath(pattern, PARAMS), pattern).not.toContain(':');
    }
  });

  it('refuses a parameter without a value', () => {
    expect(() => sweepPath('/apps/:appId/stats/sql/:sqlId', { ...PARAMS, sqlId: '' })).toThrow(
      /:sqlId/,
    );
  });
});

describe('XSS locations', () => {
  it('are the union of docs/18 §4.3 and docs/16 §9.4, SQL normalized by design', () => {
    expect(XSS_LOCATIONS.map((l) => l.id)).toEqual([
      'service_path',
      'query_string',
      'header',
      'user_id',
      'sql',
      'exception_message',
      'thread_name',
      'external_path',
      'batch_job_name',
      'deploy_event_title',
    ]);
    expect(XSS_LOCATIONS.filter((l) => l.stored === 'normalized').map((l) => l.id)).toEqual([
      'sql',
    ]);
  });

  it('payloadStrings finds every string with the marker and its path', () => {
    const detail = {
      url: '/seed/xss/%3Cimg%20src%3Dx%20onerror%3D%22window.__xss%3D1%22%3EARGOS_SEED',
      headers: { 'x-seed': '<img src=x onerror="window.__xss=1">ARGOS_SEED', accept: '*/*' },
      profile: { steps: [{ name: 'plain' }, { name: '"><svg/onload=window.__xss=1>.seedStep' }] },
      count: 3,
      empty: null,
    };
    expect(payloadStrings(detail)).toEqual([
      { path: '$.url', value: detail.url },
      { path: '$.headers.x-seed', value: detail.headers['x-seed'] },
      { path: '$.profile.steps[1].name', value: '"><svg/onload=window.__xss=1>.seedStep' },
    ]);
    expect(payloadStrings({ sql: 'SELECT COUNT(*) FROM customers WHERE email = ?' })).toEqual([]);
  });

  it('the injected selector names handlers, frames and javascript: links but no plain element', () => {
    expect(INJECTED_SELECTOR).toContain('[onerror]');
    expect(INJECTED_SELECTOR).toContain('[onload]');
    expect(INJECTED_SELECTOR).toContain('a[href^="javascript:" i]');
    expect(INJECTED_SELECTOR).toContain('script:not([nonce])');
    expect(INJECTED_SELECTOR.split(', ')).not.toContain('svg');
  });
});

describe('the sweep apps', () => {
  it('capture every XSS location the agent can keep and keep every profile', () => {
    expect(xssShopJvmArgs()).toEqual([
      '-Dargos.capture.http-headers=X-Seed',
      '-Dargos.capture.user-id.mode=plain',
      '-Dargos.capture.user-id.source=header:X-User-Id',
      '-Dargos.capture.query-string=full',
      '-Dargos.capture.sql-parameters=full',
      '-Dargos.sampling.profile.slow-threshold-ms=0',
    ]);
  });

  it('call the XSS seeds of each catalog once through the smoke runner', () => {
    const shop = xssSmokeArgs('smoke.jar', 'shop', 'http://127.0.0.1:1', 'out');
    expect(shop.slice(shop.indexOf('--catalog'))).toEqual([
      '--catalog',
      'shop',
      '--only',
      'seed.xss.',
      '--repeat',
      '1',
      '--report-dir',
      'out',
    ]);
    const batch = xssSmokeArgs('smoke.jar', 'batch', 'http://127.0.0.1:1', 'out');
    expect(batch[batch.indexOf('--only') + 1]).toBe('seed.xss.batch.');
    expect(batch.slice(0, 2)).toEqual(['-jar', 'smoke.jar']);
  });
});
