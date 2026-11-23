// SPDX-License-Identifier: Apache-2.0
//
// The route target of the XLog performance spec (XLOG_PERF_TARGET=route, WO-33): the production
// XLog screen /apps/<app>/xlog served by the server of the WO-30 E2E stack (its boot jar's UI under
// the per-request nonce CSP, the real points API on ClickHouse with the seed data). The stack is
// started or reused by perf/lib/globalSetup.ts (e2e/stack/setup.ts, ARGOS_E2E_STACK_URL); a
// viewer signs in through the real API; the window comes from the seed manifest (absolute range,
// never a fixed date, ADR-0010 C-170). The page records frames only with the browser preference
// `argos.ui.xlogPerf` = '1' (src/features/xlog/data/perfHook.ts), which the context sets before
// the page loads.
import { expect, type Browser, type BrowserContext, type Page } from '@playwright/test';

import { apiAs, storageStateOf } from '../../e2e/fixtures/stack';
import { apiPoints } from '../../e2e/fixtures/xlogPage';
import type { ApiSession } from '../../e2e/stack/api';
import {
  manifestApp,
  manifestWindow,
  readManifest,
  type ManifestWindow,
} from '../../e2e/stack/manifest';
import { readSession } from '../../e2e/stack/session';

import type { ColorSetting, YSetting } from './settings';
import { VIEWPORT } from './xlogPage';

/** Smallest window the route target measures as "100k points" (docs/15 §14 N-14). */
export const ROUTE_MIN_POINTS = 100_000;
/**
 * The unsampled route window (XLOG_PERF_ROUTE_UNSAMPLED, review fe-perf-5): the longest start of the
 * manifest window with at most 100,000 points (the points API sends every one, no sampling) must
 * hold at least this many to count as "about 100k".
 */
export const ROUTE_UNSAMPLED_MIN_POINTS = 90_000;

export interface RouteTarget {
  readonly stackUrl: string;
  readonly window: ManifestWindow;
  readonly storageState: ReturnType<typeof storageStateOf>;
  /** The first seeded instance of the window's app (the realtime feed's points, routeLive.ts). */
  readonly instanceId: number;
}

/**
 * The stack of this run, a signed-in viewer and the manifest window `windowName`; with `unsampled`
 * its longest start that the points API answers unsampled (largestUnsampledWindow).
 */
export async function routeTarget(windowName: string, unsampled = false): Promise<RouteTarget> {
  const session = readSession();
  const seed = readManifest();
  const window = manifestWindow(seed, windowName);
  const api = await apiAs(session, seed, 'viewer');
  return {
    stackUrl: session.stack_url,
    window: unsampled ? await largestUnsampledWindow(api, window) : window,
    storageState: storageStateOf(api),
    instanceId: manifestApp(seed, window.app_id).instances[0]?.instance_id ?? 1,
  };
}

/**
 * The longest window [window.from, t] (whole seconds) with at most `maxPoints` points of the
 * window's kind (meta.total of the points API, asked with limit 1), found by bisection; the window
 * itself when it holds no more. Its total is the API's for those exact bounds.
 */
export async function largestUnsampledWindow(
  api: ApiSession,
  window: ManifestWindow,
  maxPoints: number = ROUTE_MIN_POINTS,
): Promise<ManifestWindow> {
  if (window.total <= maxPoints) {
    return window;
  }
  const kind = window.kind.toUpperCase();
  const count = async (to: number): Promise<number> => {
    const { meta } = await apiPoints(api, window.app_id, {
      from: window.from,
      to: new Date(to).toISOString(),
      kind,
      limit: 1,
    });
    if (typeof meta.total !== 'number') {
      throw new Error('the points API sent no meta.total');
    }
    return meta.total;
  };
  let lo = Date.parse(window.from);
  let hi = Date.parse(window.to);
  let best: { to: number; total: number } | null = null;
  for (let i = 0; i < 40 && hi - lo > 1000; i += 1) {
    const mid = lo + Math.max(1000, Math.floor((hi - lo) / 2000) * 1000);
    if (mid >= hi) {
      break;
    }
    const total = await count(mid);
    if (total <= maxPoints) {
      best = { to: mid, total };
      lo = mid;
    } else {
      hi = mid;
    }
  }
  if (best === null) {
    throw new Error(
      `no start of the window ${window.name} holds at most ${String(maxPoints)} points`,
    );
  }
  return {
    ...window,
    name: `${window.name}-unsampled`,
    to: new Date(best.to).toISOString(),
    total: best.total,
  };
}

/** Path and canonical query (state/urlState.ts XLogQ) of the window with a drawing configuration. */
export function routePath(
  window: Pick<ManifestWindow, 'app_id' | 'kind' | 'from' | 'to'>,
  options: { readonly color: ColorSetting; readonly y: YSetting },
): string {
  const pairs = [
    `from=${new Date(window.from).toISOString()}`,
    `to=${new Date(window.to).toISOString()}`,
  ];
  const kind = window.kind.toUpperCase();
  if (kind !== 'HTTP') {
    pairs.push(`kind=${kind}`);
  }
  if (options.color !== 'status') {
    pairs.push(`color=${options.color}`);
  }
  if (options.y === 'log') {
    pairs.push('y=log');
  }
  return `/apps/${String(window.app_id)}/xlog?${pairs.join('&')}`;
}

export interface OpenedRoute {
  readonly context: BrowserContext;
  readonly page: Page;
}

/**
 * Opens the XLog screen in a fresh context (viewport 1366×768, the scenario's devicePixelRatio,
 * en-US, UTC, the viewer's session) and waits until the loaded points are drawn (data-ready and
 * window.__xlogPerf.ready).
 */
export async function openRoute(
  browser: Browser,
  target: RouteTarget,
  options: {
    readonly dpr: number;
    readonly color: ColorSetting;
    readonly y: YSetting;
  },
): Promise<OpenedRoute> {
  const context = await browser.newContext({
    viewport: VIEWPORT,
    deviceScaleFactor: options.dpr,
    locale: 'en-US',
    timezoneId: 'UTC',
    storageState: target.storageState,
  });
  await context.addInitScript(() => {
    try {
      window.localStorage.setItem('argos.ui.xlogPerf', '1');
    } catch {
      // Storage blocked: the page then records nothing and the spec reports it.
    }
  });
  const page = await context.newPage();
  await page.goto(`${target.stackUrl}${routePath(target.window, options)}`);
  await expect(page.getByTestId('xlog-page')).toHaveAttribute('data-ready', 'true', {
    timeout: 120_000,
  });
  await page.waitForFunction(() => window.__xlogPerf?.ready === true, undefined, {
    timeout: 60_000,
  });
  return { context, page };
}
