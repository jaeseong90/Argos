// SPDX-License-Identifier: Apache-2.0
//
// Realtime on the route target of the XLog performance spec (XLOG_PERF_TARGET=route, scenario
// realtime; review fe-perf-4, ADR-0010 C-203 "정식 캔버스에 합성 주입", C-205 (3)): the production
// XLog screen in live mode on the WO-30 stack, its `xlog` topic played by the docs/14 §5 stub
// (page.routeWebSocket with the E2E suite's protocol state, e2e/fixtures/wsProtocol.ts), because the
// WebSocket server is WO-22. The screen loads the last 5 minutes through the real REST API, filtered
// to a service key without transactions (LIVE_SERVICE, checked by the spec through the API), so that
// load is empty and every point on the chart is a push. The feed then backfills the 5-minute window
// (batches whose server time is their newest point, so none is a D-15 late point) and streams
// `rate` points per second in 100 ms batches (docs/14 §5.2 push, docs/15 §7.5).
import {
  expect,
  type Browser,
  type BrowserContext,
  type Page,
  type WebSocketRoute,
} from '@playwright/test';

import { WS_URL } from '../../e2e/fixtures/ws';
import { WsConnectionState, xlogPush, type XlogPoint } from '../../e2e/fixtures/wsProtocol';

import type { RouteTarget } from './routePage';
import { VIEWPORT } from './xlogPage';

/** A service key no seeded transaction has (the E2E live checks use the same key). */
export const LIVE_SERVICE = '1';
/** Name of the pushed service in the batches' dictionaries. */
const LIVE_SERVICE_NAME = 'GET /perf/live';
/** The realtime window of the screen (docs/15 §7.5). */
export const LIVE_WINDOW_MS = 5 * 60_000;
/** Most points of one backfill batch (the client accepts up to 50,000 rows per push). */
const BACKFILL_BATCH_POINTS = 20_000;
/** The span of one backfill batch stays under the 60 s of D-15 (late points are not shown live). */
const BACKFILL_BATCH_SPAN_MS = 50_000;

export interface LiveFeed {
  /** Resolves when the screen subscribed the `xlog` topic. */
  waitForSubscription(timeoutMs?: number): Promise<void>;
  /** Sends one push of `points` to every `xlog` subscription, with server time `at`. */
  push(points: readonly XlogPoint[], at: number): number;
  /** Client frames that broke docs/14 §5.2. */
  violations(): string[];
}

/** Installs the stub on `page` (before it navigates). */
export async function installLiveFeed(page: Page): Promise<LiveFeed> {
  let current: { route: WebSocketRoute; state: WsConnectionState } | null = null;
  const violations: string[] = [];
  await page.routeWebSocket(WS_URL, (route) => {
    const state = new WsConnectionState();
    const connection = { route, state };
    current = connection;
    route.onMessage((message) => {
      for (const frame of state.receive(message)) {
        route.send(frame);
      }
      violations.push(...state.violations.splice(0));
    });
    route.onClose(() => {
      if (current === connection) {
        current = null;
      }
    });
  });
  return {
    async waitForSubscription(timeoutMs = 30_000) {
      const deadline = Date.now() + timeoutMs;
      while ((current?.state.of('xlog').length ?? 0) === 0) {
        if (Date.now() > deadline) {
          throw new Error('the XLog screen did not subscribe the xlog topic');
        }
        await page.waitForTimeout(50);
      }
    },
    push(points, at) {
      const connection = current;
      if (connection === null) {
        return 0;
      }
      let sent = 0;
      for (const subscription of connection.state.of('xlog')) {
        connection.route.send(
          xlogPush(subscription, points, { [LIVE_SERVICE]: LIVE_SERVICE_NAME }, new Date(at)),
        );
        sent += 1;
      }
      return sent;
    },
    violations: () => [...violations],
  };
}

/** Deterministic points: unique tx ids from `firstTx`, response times of a realistic spread. */
export function livePoints(
  count: number,
  firstTx: number,
  tsOf: (i: number) => number,
  instanceId: number,
): XlogPoint[] {
  const points: XlogPoint[] = [];
  let x = (firstTx * 2_654_435_761) >>> 0 || 1;
  for (let i = 0; i < count; i += 1) {
    x ^= x << 13;
    x >>>= 0;
    x ^= x >>> 17;
    x ^= x << 5;
    x >>>= 0;
    const u = x / 0x1_0000_0000;
    // Mostly 20-400 ms, a tail up to a few seconds; about 1 % errors, slow above 1 s.
    const elapsed = Math.round(20 * Math.exp(u * 5.3));
    points.push({
      tx_id: String(BigInt(firstTx + i) + 9_000_000_000_000_000_000n),
      ts: tsOf(i),
      elapsed_ms: elapsed,
      status: x % 97 === 0 ? 2 : elapsed >= 1000 ? 1 : 0,
      instance_id: instanceId,
      service_id: LIVE_SERVICE,
      kind: 1,
    });
  }
  return points;
}

/**
 * Fills the 5-minute window ending now with `count` points, oldest batch first; each batch spans
 * less than 60 s and carries its newest point's time as the server time (no D-15 late point).
 * Returns the next free tx id.
 */
export function backfill(
  feed: LiveFeed,
  count: number,
  instanceId: number,
  firstTx: number,
): number {
  const now = Date.now();
  const start = now - LIVE_WINDOW_MS + 5_000;
  const span = now - 1_000 - start;
  const batches = Math.max(
    Math.ceil(count / BACKFILL_BATCH_POINTS),
    Math.ceil(span / BACKFILL_BATCH_SPAN_MS),
  );
  const perBatch = Math.ceil(count / batches);
  let tx = firstTx;
  for (let b = 0; b < batches; b += 1) {
    const n = Math.min(perBatch, count - b * perBatch);
    if (n <= 0) {
      break;
    }
    const from = start + (span * b) / batches;
    const to = start + (span * (b + 1)) / batches;
    const points = livePoints(n, tx, (i) => Math.round(from + ((to - from) * i) / n), instanceId);
    tx += n;
    feed.push(points, Math.round(to));
  }
  return tx;
}

/** Streams `rate` points per second in 100 ms batches until stopped (server time = now). */
export function stream(
  feed: LiveFeed,
  rate: number,
  instanceId: number,
  firstTx: number,
): { stop: () => void; pushed: () => number } {
  let tx = firstTx;
  let pushed = 0;
  const perBatch = Math.max(1, Math.round(rate / 10));
  const timer = setInterval(() => {
    const now = Date.now();
    const points = livePoints(
      perBatch,
      tx,
      (i) => now - 90 + Math.round((90 * i) / perBatch),
      instanceId,
    );
    tx += perBatch;
    if (feed.push(points, now) > 0) {
      pushed += perBatch;
    }
  }, 100);
  return {
    stop: () => {
      clearInterval(timer);
    },
    pushed: () => pushed,
  };
}

export interface OpenedLiveRoute {
  readonly context: BrowserContext;
  readonly page: Page;
  readonly feed: LiveFeed;
  readonly path: string;
}

/**
 * Opens the XLog screen in live mode (`from=now-5m&live=1`, the window's app and kind, the service
 * filter LIVE_SERVICE) in a fresh context like perf/lib/routePage.ts openRoute (viewport 1366×768,
 * the scenario's devicePixelRatio, en-US, UTC, the viewer's session, the perf preference), with the
 * feed installed; waits until the REST load is done and the topic is subscribed.
 */
export async function openRouteLive(
  browser: Browser,
  target: RouteTarget,
  options: { readonly dpr: number },
): Promise<OpenedLiveRoute> {
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
  const feed = await installLiveFeed(page);
  const kind = target.window.kind.toUpperCase();
  const path = `/apps/${String(target.window.app_id)}/xlog?from=now-5m&live=1${kind === 'HTTP' ? '' : `&kind=${kind}`}&f.service=${LIVE_SERVICE}`;
  await page.goto(`${target.stackUrl}${path}`);
  const root = page.getByTestId('xlog-page');
  await expect(root).toHaveAttribute('data-live', 'true');
  await expect(root).toHaveAttribute('data-state', 'ready', { timeout: 60_000 });
  await feed.waitForSubscription();
  return { context, page, feed, path };
}
