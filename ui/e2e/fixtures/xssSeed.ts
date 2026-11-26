// SPDX-License-Identifier: Apache-2.0
//
// The XSS seed sweep of WO-46 (T-SEC-62, docs/06 SEC-62, docs/16 §9.4~9.5, docs/18 §4.3 and §13):
// the pure parts of e2e/specs/xss-seed.spec.ts, kept apart so that the tooling tests
// (xssSeed.test.ts) check them without a stack.
//
// - XSS_LOCATIONS: every place docs/18 §4.3 and docs/16 §9.4 put an XSS payload into collected
//   data, with what the stack stores of it (verbatim, or not at all by design) and where the spec
//   finds it in the API.
// - XSS_ROUTE_PLAN: one entry per shell route of e2e/specs/routes.ts with what the sweep does there
//   (once, or for every swept application; the tabs it opens). A route added to routes.ts without
//   an entry here fails xssSeed.test.ts and the spec's coverage test (routes.test.ts style).
// - The seed apps of the sweep (e2e/fixtures/xssApps.ts): shop-boot3 with every capture that keeps
//   a payload (X-Seed header, user id plain from X-User-Id, query string full, bind parameters full,
//   every profile kept), the XSS seed endpoints called once (smoke runner --only seed.xss.), and
//   shop-batch for the batch job name (--catalog batch --only seed.xss.batch.).

/** Marker of every XSS seed payload: running one sets window.__xss (docs/16 §9.4). */
export const XSS_MARKER = '__xss';

/**
 * Elements or attributes that exist only when collected text was parsed as markup: event handler
 * attributes, frames with srcdoc, javascript: links. The application itself never renders any of
 * them (its scripts carry the CSP nonce and React attaches handlers as properties).
 */
export const INJECTED_SELECTOR = [
  '[onerror]',
  '[onload]',
  '[onclick]',
  '[onmouseover]',
  'iframe',
  'object',
  'embed',
  'a[href^="javascript:" i]',
  'script:not([nonce])',
].join(', ');

/** How the stack keeps a payload of a location. */
export type Stored =
  /** The API answers the payload (percent-encoded in a URL) and a screen shows it as text. */
  | 'verbatim'
  /**
   * The payload never reaches the API by design: SQL text is normalized (the literal becomes `?`)
   * and bind values of a full-mode instance are not part of the transaction detail here. The spec
   * checks that the stored SQL holds no payload instead (SEC-35 normalization).
   */
  | 'normalized';

export interface XssLocation {
  /** Stable id (test titles, annotations). */
  readonly id: string;
  /** The design clause that names the location. */
  readonly clause: string;
  /** The seed catalog entries that plant it (sample-apps/common ShopCatalog / BatchCatalog). */
  readonly seed: string;
  readonly stored: Stored;
  /** Where the spec reads it from the API. */
  readonly source: string;
}

/** Every XSS location of docs/18 §4.3 and docs/16 §9.4 (the union, ADR-0004 C-45). */
export const XSS_LOCATIONS: readonly XssLocation[] = [
  {
    id: 'service_path',
    clause: '18 §4.3 service path segment /seed/xss/<payload>; 16 §9.4 service path',
    seed: 'seed.xss.path.N',
    stored: 'verbatim',
    source: 'transaction detail url (GET /seed/xss/**)',
  },
  {
    id: 'query_string',
    clause: '18 §4.3 query string (whitelist or full mode)',
    seed: 'seed.xss.query.N',
    stored: 'verbatim',
    source: 'transaction detail url (GET /seed/xss-query, capture.query-string=full)',
  },
  {
    id: 'header',
    clause: '18 §4.3 header value; 16 §9.4 request header X-Seed',
    seed: 'seed.xss.header.N',
    stored: 'verbatim',
    source: 'transaction detail headers.x-seed (capture.http-headers=X-Seed)',
  },
  {
    id: 'user_id',
    clause: '18 §4.3 user id; 16 §9.4 user_id header',
    seed: 'seed.xss.user.N',
    stored: 'verbatim',
    source: 'transaction detail user_id (capture.user-id.mode=plain, header:X-User-Id)',
  },
  {
    id: 'sql',
    clause: '18 §4.3 SQL literal and bind; 16 §9.4 SQL literal',
    seed: 'seed.xss.sql.N',
    stored: 'normalized',
    source: 'SQL steps and SQL statistics of GET /seed/xss-sql (normalized text only)',
  },
  {
    id: 'exception_message',
    clause: '18 §4.3 exception message; 16 §9.4 exception message',
    seed: 'seed.xss.error.N',
    stored: 'verbatim',
    source: 'error group message_pattern (GET /apps/{appId}/errors/groups)',
  },
  {
    id: 'thread_name',
    clause: '18 §4.3 thread name; 16 §9.4 Thread.currentThread().setName',
    seed: 'command feed thread dump (e2e/fixtures/commandFeed.ts xss_thread)',
    stored: 'verbatim',
    source: 'thread dump result threads[].name (GET /commands/{id}/result)',
  },
  {
    id: 'external_path',
    clause: '18 §4.3 external call path (ext-stub)',
    seed: 'seed.xss.ext.N',
    stored: 'verbatim',
    source: 'transaction detail external step url (GET /seed/xss-ext, profile kept)',
  },
  {
    id: 'batch_job_name',
    clause: '18 §4.3 batch job name',
    seed: 'seed.xss.batch.N (shop-batch)',
    stored: 'verbatim',
    source: 'transaction detail step name of POST /seed/xss-batch (profile kept)',
  },
  {
    id: 'deploy_event_title',
    clause: '16 §9.4 deploy event title',
    seed: 'e2e/stack/seed/traffic.ts SEED_EVENT_XSS_TITLE',
    stored: 'verbatim',
    source: 'GET /apps/{primary}/events title',
  },
];

/** Where the sweep visits a route: once, or for every swept application (primary, traffic, xss). */
export type RouteScope = 'once' | 'per-app';

export interface RouteVisitPlan {
  readonly scope: RouteScope;
  /** Tabs opened one after the other with ?tab=<name> (none: the route alone). */
  readonly tabs?: readonly string[];
}

/** The transaction detail tabs (src/features/transaction, docs/15 §8). */
export const TRANSACTION_TABS = ['timeline', 'tree', 'sql', 'ext', 'error', 'stack', 'headers'];

/** The instance detail tabs (src/features/instances/InstanceTabs.tsx INSTANCE_TABS). */
export const INSTANCE_TABS = ['metrics', 'env', 'commands', 'dumps'];

/** What the sweep does on every shell route (e2e/specs/routes.ts SHELL_ROUTES). */
export const XSS_ROUTE_PLAN: Readonly<Record<string, RouteVisitPlan>> = {
  '/login': { scope: 'once' },
  '/setup': { scope: 'once' },
  '/account': { scope: 'once' },
  '/overview': { scope: 'once' },
  '/alerts': { scope: 'once' },
  '/alerts/rules': { scope: 'once' },
  '/alerts/rules/new': { scope: 'once' },
  '/alerts/rules/:ruleId': { scope: 'once' },
  '/alerts/channels': { scope: 'once' },
  '/admin/apps': { scope: 'once' },
  '/admin/pending': { scope: 'once' },
  '/admin/instances': { scope: 'once' },
  '/admin/tokens': { scope: 'once' },
  '/admin/config': { scope: 'once' },
  '/admin/users': { scope: 'once' },
  '/admin/audit': { scope: 'once' },
  '/admin/privacy': { scope: 'once' },
  '/admin/licenses': { scope: 'once' },
  '/admin/about': { scope: 'once' },
  '/search': { scope: 'once' },
  '/traces/:traceId': { scope: 'once' },
  '/apps/:appId/dashboard': { scope: 'per-app' },
  '/apps/:appId/xlog': { scope: 'per-app' },
  '/apps/:appId/transactions/:txId': { scope: 'per-app', tabs: TRANSACTION_TABS },
  '/apps/:appId/active': { scope: 'per-app' },
  '/apps/:appId/stats/services': { scope: 'per-app' },
  '/apps/:appId/stats/services/:serviceId': { scope: 'per-app' },
  '/apps/:appId/stats/sql': { scope: 'per-app' },
  '/apps/:appId/stats/sql/:sqlId': { scope: 'per-app' },
  '/apps/:appId/stats/external': { scope: 'per-app' },
  '/apps/:appId/stats/external/:hostId': { scope: 'per-app' },
  '/apps/:appId/stats/errors': { scope: 'per-app' },
  '/apps/:appId/stats/errors/:groupId': { scope: 'per-app' },
  '/apps/:appId/instances': { scope: 'per-app' },
  '/apps/:appId/instances/:instanceId': { scope: 'per-app', tabs: INSTANCE_TABS },
  '/apps/:appId/map': { scope: 'per-app' },
};

/** Route patterns of `routes` that the plan does not cover, and plan entries of no route. */
export function planCoverage(
  routes: readonly string[],
  plan: Readonly<Record<string, RouteVisitPlan>> = XSS_ROUTE_PLAN,
): { readonly unplanned: string[]; readonly stale: string[] } {
  const planned = new Set(Object.keys(plan));
  const known = new Set(routes);
  return {
    unplanned: routes.filter((r) => !planned.has(r)).sort(),
    stale: [...planned].filter((p) => !known.has(p)).sort(),
  };
}

/** Values of the route parameters of one swept application (64-bit ids stay strings). */
export interface RouteParams {
  readonly appId: number;
  readonly txId: string;
  /** The ts hint of the transaction (ISO-8601). */
  readonly txTs: string;
  readonly instanceId: number;
  readonly serviceId: string;
  readonly sqlId: string;
  readonly hostId: string;
  readonly groupId: string;
  readonly traceId: string;
  readonly traceTs: string;
  readonly ruleId: string;
  /** The absolute range of the application's data. */
  readonly from: string;
  readonly to: string;
}

/**
 * The URL the sweep opens for `pattern` (and `tab`): the parameters filled, the application's
 * range on app routes, the ts hint on the transaction and trace routes.
 */
export function sweepPath(pattern: string, p: RouteParams, tab?: string): string {
  const values: Record<string, string> = {
    appId: String(p.appId),
    txId: p.txId,
    instanceId: String(p.instanceId),
    serviceId: p.serviceId,
    sqlId: p.sqlId,
    hostId: p.hostId,
    groupId: p.groupId,
    traceId: p.traceId,
    ruleId: p.ruleId,
  };
  const path = pattern.replace(/:([A-Za-z]+)/g, (_, name: string) => {
    const value = values[name];
    if (value === undefined || value === '') {
      throw new Error(`no value for route parameter :${name} of ${pattern}`);
    }
    return encodeURIComponent(value);
  });
  const query = new URLSearchParams();
  if (pattern.startsWith('/apps/:appId/')) {
    query.set('from', p.from);
    query.set('to', p.to);
  }
  if (pattern === '/apps/:appId/transactions/:txId') {
    query.set('ts', p.txTs);
  }
  if (pattern === '/traces/:traceId') {
    query.set('ts', p.traceTs);
  }
  if (tab !== undefined) {
    query.set('tab', tab);
  }
  const text = query.toString();
  return text === '' ? path : `${path}?${text}`;
}

/** One string of an API answer with an XSS payload and its JSON path. */
export interface PayloadHit {
  readonly path: string;
  readonly value: string;
}

/** Every string of `value` (any JSON) that holds the XSS marker, with its path. */
export function payloadStrings(value: unknown, path = '$'): PayloadHit[] {
  if (typeof value === 'string') {
    return value.includes(XSS_MARKER) ? [{ path, value }] : [];
  }
  if (Array.isArray(value)) {
    return value.flatMap((item, i) => payloadStrings(item, `${path}[${String(i)}]`));
  }
  if (value !== null && typeof value === 'object') {
    return Object.entries(value as Record<string, unknown>).flatMap(([key, item]) =>
      payloadStrings(item, `${path}.${key}`),
    );
  }
  return [];
}

/**
 * JVM system properties of the sweep's shop-boot3 (agent keys of docs/agent-config.md): every
 * capture that keeps an XSS payload of a seed request, and every profile kept (slow threshold 0) so
 * that the external call and batch steps are in the transaction details.
 */
export function xssShopJvmArgs(): string[] {
  return [
    '-Dargos.capture.http-headers=X-Seed',
    '-Dargos.capture.user-id.mode=plain',
    '-Dargos.capture.user-id.source=header:X-User-Id',
    '-Dargos.capture.query-string=full',
    '-Dargos.capture.sql-parameters=full',
    '-Dargos.sampling.profile.slow-threshold-ms=0',
  ];
}

/** Arguments of the smoke runner (sample-apps/smoke) for the XSS seeds of one catalog. */
export function xssSmokeArgs(
  smokeJar: string,
  catalog: 'shop' | 'batch',
  url: string,
  reportDir: string,
): string[] {
  return [
    '-jar',
    smokeJar,
    '--app',
    `xss-seed-${catalog}`,
    '--variant',
    'xss-seed',
    '--kind',
    'url',
    '--url',
    url,
    '--catalog',
    catalog,
    '--only',
    catalog === 'shop' ? 'seed.xss.' : 'seed.xss.batch.',
    '--repeat',
    '1',
    '--report-dir',
    reportDir,
  ];
}
