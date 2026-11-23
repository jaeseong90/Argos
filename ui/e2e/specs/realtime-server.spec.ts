// SPDX-License-Identifier: Apache-2.0
//
// The realtime screens against the REAL WebSocket server of WO-22 (wsMode 'server' of
// e2e/fixtures/stack.ts: every socket of the page is forwarded to the stack's /ws and both
// directions are recorded; the stub specs of realtime.spec.ts keep proving the client rules).
// ADR-0010 C-167 (3): the real-server scenarios are WO-22's acceptance.
//   T-SV-020e  requests to a running shop-boot3 with the agent reach the XLog live screen through
//              real xlog pushes (tx ids in the recorded frames = the stored rows), and the
//              instance is UP from the server's state machine (REST and the counter topic)
//   T-SEC-44c  the page's session revoked from outside the page (logout through the API): the
//              server ends the socket (reauth frame or close 4401) within 1 s, the page goes to
//              /login?next=<current path> and opens no new connection
// T-SV-020e starts its own shop-boot3 with the agent jar of the stack (same app, token and
// instance name as the seed, so no new instance row) on a host JVM; inside the Playwright image of
// e2e:docker there is none, and it is skipped there (the server half T-SV-020 is RealtimeE2eIT).
import type { ChildProcess } from 'node:child_process';
import { existsSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

import type { Page, TestInfo } from '@playwright/test';

import { parseXLogPush } from '../../src/features/xlog/live/pushData';
import { expect, storageStateOf, test } from '../fixtures/stack';
import { expectShell } from '../fixtures/ui';
import type { RecordedServerFrame, WsStub } from '../fixtures/ws';
import { apiPoints } from '../fixtures/xlogPage';
import type { ApiSession } from '../stack/api';
import { jarsDir, logsDir, secretsDir } from '../stack/paths';
import { readRunState } from '../stack/runState';
import { javaExecutable } from '../stack/seed/gradle';
import { startProcess, stopProcess, TRAFFIC_APP, TRAFFIC_INSTANCE } from '../stack/seed/traffic';
import { freePort, httpGet, sleep, waitFor } from '../stack/wait';

test.use({ wsMode: 'server' });

const WS = { type: 'req', description: 'SEC-44' };
const MINUTE = 60_000;
/** Requests of T-SV-020e after the warm-up. */
const REQUESTS = 5;
/** Inside the pinned Playwright image of e2e:docker (e2e/stack/cli.ts sets it): no JVM. */
const IN_CONTAINER = (process.env.ARGOS_E2E_LINUX_IMAGE ?? '') !== '';

interface ApiInstance {
  instance_id: number;
  name: string;
  state: string;
}

function iso(ms: number): string {
  return new Date(ms).toISOString();
}

/** A shop-boot3 with the agent jar of the stack, as the seed runs it (e2e/stack/seed/traffic.ts). */
interface RunningShop {
  readonly url: string;
  readonly child: ChildProcess;
}

/** Why the shop cannot start here, or null. */
function shopProblem(runId: string): string | null {
  if (IN_CONTAINER) {
    return 'no JVM in the Playwright image of e2e:docker to run shop-boot3 with the agent (the server half T-SV-020 is RealtimeE2eIT)';
  }
  const state = readRunState();
  if (state?.run_id !== runId || state.grpc_port === null) {
    return `ui/e2e/.stack/run.json does not describe the stack of run ${runId} with a gRPC port`;
  }
  for (const file of [
    join(jarsDir(runId), 'argos-agent.jar'),
    join(jarsDir(runId), 'shop-boot3.jar'),
    join(secretsDir(runId), 'agent.token'),
  ]) {
    if (!existsSync(file)) {
      return `${file} is missing (written by the stack setup)`;
    }
  }
  return null;
}

async function startShop(runId: string): Promise<RunningShop> {
  const state = readRunState();
  if (state?.grpc_port == null) {
    throw new Error('no gRPC port in run.json');
  }
  const logs = logsDir(runId);
  mkdirSync(logs, { recursive: true });
  const port = await freePort();
  const url = `http://127.0.0.1:${String(port)}`;
  const child = startProcess(
    javaExecutable(),
    [
      `-javaagent:${join(jarsDir(runId), 'argos-agent.jar')}`,
      `-Dargos.server.address=127.0.0.1:${String(state.grpc_port)}`,
      '-Dargos.server.tls=false',
      `-Dargos.server.token-file=${join(secretsDir(runId), 'agent.token')}`,
      `-Dargos.app.name=${TRAFFIC_APP.name}`,
      `-Dargos.app.env=${TRAFFIC_APP.env}`,
      `-Dargos.app.instance=${TRAFFIC_INSTANCE}`,
      '-jar',
      join(jarsDir(runId), 'shop-boot3.jar'),
      `--server.port=${String(port)}`,
      '--server.address=127.0.0.1',
    ],
    {},
    join(logs, 'realtime-server-shop-boot3.log'),
  );
  try {
    await waitFor(
      async () => {
        if (child.exitCode !== null) {
          throw new Error(`shop-boot3 exited with ${String(child.exitCode)} (log: ${logs})`);
        }
        return (await httpGet(`${url}/api/version`)).status === 200 ? true : undefined;
      },
      { timeoutMs: 180_000, intervalMs: 1000, what: `shop-boot3 at ${url}` },
    );
  } catch (error) {
    await stopProcess(child);
    throw error;
  }
  return { url, child };
}

async function getOk(url: string): Promise<void> {
  const response = await httpGet(url, {}, 10_000);
  if (response.status !== 200) {
    throw new Error(`GET ${url} answered ${String(response.status)}`);
  }
}

/** The server's xlog pushes of subscription `id` as point rows. */
function pushedRows(
  ws: WsStub,
  id: string,
): { tx: string; ts: number; service: string; at: number }[] {
  return ws.serverFrames().flatMap((f: RecordedServerFrame) => {
    const frame = f.frame;
    if (frame?.op !== 'push' || frame.topic !== 'xlog' || frame.id !== id) {
      return [];
    }
    const columns = parseXLogPush(frame.data);
    if (columns === null) {
      return [];
    }
    return columns.tx_id.map((tx, i) => ({
      tx,
      ts: columns.ts[i] ?? 0,
      service: columns.service_id[i] ?? '',
      at: f.at,
    }));
  });
}

/** Opens a second socket in the page and subscribes `counter` of `appId` (id probe-counter). */
async function probeCounter(page: Page, appId: number): Promise<void> {
  await page.evaluate((app) => {
    const socket = new WebSocket(
      `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`,
    );
    socket.onopen = () => {
      socket.send(
        JSON.stringify({ op: 'subscribe', id: 'probe-counter', topic: 'counter', params: { app } }),
      );
    };
    (window as unknown as Record<string, unknown>).__argosProbeSocket = socket;
  }, appId);
}

async function closeProbe(page: Page): Promise<void> {
  await page.evaluate(() => {
    const socket = (window as unknown as Record<string, unknown>).__argosProbeSocket;
    if (socket instanceof WebSocket) {
      socket.close(1000);
    }
  });
}

/** The service id of the shop's GET /api/orders/{id} from the warm-up request (REST). */
async function warmUpService(
  api: ApiSession,
  appId: number,
  instanceId: number,
  shop: RunningShop,
): Promise<string> {
  const warmAt = Date.now();
  await getOk(`${shop.url}/api/orders/1`);
  return waitFor(
    async () => {
      const { data } = await apiPoints(api, appId, {
        from: iso(warmAt - 2000),
        to: iso(Date.now() + MINUTE),
        kind: 'HTTP',
        limit: 1000,
      });
      for (let i = 0; i < data.tx_id.length; i += 1) {
        const service = data.service_id[i] ?? '';
        const name = data.dictionary.service[service] ?? '';
        if (
          data.instance_id[i] === instanceId &&
          (data.ts[i] ?? 0) >= warmAt - 1000 &&
          name.includes('/api/orders')
        ) {
          return service;
        }
      }
      return undefined;
    },
    { timeoutMs: 60_000, intervalMs: 1000, what: 'the warm-up transaction in the XLog API' },
  );
}

function annotate(testInfo: TestInfo, type: string, description: string): void {
  testInfo.annotations.push({ type, description });
}

test(
  'T-SV-020e XLog live shows the transactions the real server pushes; the instance is UP from the state machine',
  {
    annotation: [
      { type: 'req', description: 'N-15' },
      { type: 'req', description: 'F-U02' },
      { type: 'req', description: 'F-S28' },
    ],
  },
  async ({ page, ws, seed, session, apiAs }, testInfo) => {
    const problem = shopProblem(session.run_id);
    test.skip(problem !== null, problem ?? '');
    test.setTimeout(6 * MINUTE);
    const api = await apiAs('viewer');
    const appId = seed.traffic.app_id;
    const shop = await startShop(session.run_id);
    try {
      // The server's state machine reports the agent's instance UP (12 §4.2, C-160 lifted).
      const instance = await waitFor(
        async () => {
          const rows = (await api.query<ApiInstance[]>(`/apps/${String(appId)}/instances`)).data;
          const row = rows.find((r) => r.name === TRAFFIC_INSTANCE);
          return row?.state === 'UP' ? row : undefined;
        },
        { timeoutMs: 120_000, intervalMs: 1000, what: `instance ${TRAFFIC_INSTANCE} UP` },
      );
      // New transactions land after the manifest's traffic window, whose totals stay fixed.
      const windowEnd = Date.parse(seed.traffic.range.end);
      if (Date.now() <= windowEnd) {
        await sleep(windowEnd - Date.now() + 1000);
      }
      // The seed traffic calls /seed/** only, so on a fresh stack the warm-up is the only point of
      // this service (a reused stack may hold those of earlier runs: the page count is the base).
      const service = await warmUpService(api, appId, instance.instance_id, shop);
      const before = await apiPoints(api, appId, {
        from: iso(Date.now() - 5 * MINUTE),
        to: iso(Date.now() + MINUTE),
        kind: 'HTTP',
        limit: 1,
        service: [service],
      });
      annotate(testInfo, 'service', `${service}: ${String(before.meta.total)} point(s) before`);

      await page.goto(`/apps/${String(appId)}/xlog?from=now-5m&live=1&f.service=${service}`);
      const subscription = await ws.waitForSubscription('xlog', 30_000);
      expect(subscription.params).toMatchObject({ app: appId, service: [service] });
      const root = page.getByTestId('xlog-page');
      await expect(root).toHaveAttribute('data-state', 'ready', { timeout: 30_000 });
      await expect(root).toHaveAttribute('data-point-count', String(before.meta.total ?? -1));
      const base = Number(await root.getAttribute('data-point-count'));
      await probeCounter(page, appId);

      const sendStart = Date.now();
      let lastResponse = sendStart;
      for (let i = 0; i < REQUESTS; i += 1) {
        await getOk(`${shop.url}/api/orders/${String(i + 2)}`);
        lastResponse = Date.now();
      }
      // Real xlog pushes carry exactly the new transactions of the service.
      const ours = (): Map<string, number> =>
        new Map(
          pushedRows(ws, subscription.id)
            .filter((r) => r.service === service && r.ts >= sendStart - 1000)
            .map((r) => [r.tx, r.at]),
        );
      await expect
        .poll(() => ours().size, { timeout: 20_000, message: 'pushed transactions' })
        .toBe(REQUESTS);
      const pushed = ours();
      const arrivals = [...pushed.values()];
      annotate(
        testInfo,
        'T-SV-020e-latency',
        `last response -> last push ${String(Math.max(...arrivals) - lastResponse)} ms, first request -> first push ${String(Math.min(...arrivals) - sendStart)} ms (shared machine: INVALID reference; the 1 s budget from receipt is RealtimeE2eIT)`,
      );
      // The screen shows them (the points before plus the pushed ones).
      await expect(root).toHaveAttribute('data-point-count', String(base + REQUESTS), {
        timeout: 10_000,
      });
      // The pushed tx ids are the stored rows of the service since the first request.
      await expect
        .poll(
          async () => {
            const stored = await apiPoints(api, appId, {
              from: iso(sendStart - 1000),
              to: iso(Date.now() + MINUTE),
              kind: 'HTTP',
              limit: 1000,
              service: [service],
            });
            return [...stored.data.tx_id].sort();
          },
          { timeout: 30_000, message: 'stored tx ids = pushed tx ids' },
        )
        .toEqual([...pushed.keys()].sort());
      // The counter topic reports the instance UP (counter.per_instance[].state, 14 §5.2).
      const counter = await ws.waitForServerFrame(
        (f) => {
          const frame = f.frame;
          if (frame?.op !== 'push' || frame.topic !== 'counter' || frame.id !== 'probe-counter') {
            return false;
          }
          const rows = (frame.data as { per_instance?: { instance_id?: number; state?: string }[] })
            .per_instance;
          return (rows ?? []).some((r) => r.instance_id === instance.instance_id);
        },
        20_000,
        `a counter push of app ${String(appId)} naming instance ${String(instance.instance_id)}`,
      );
      const row = (
        counter.frame?.op === 'push'
          ? ((counter.frame.data as { per_instance?: { instance_id?: number; state?: string }[] })
              .per_instance ?? [])
          : []
      ).find((r) => r.instance_id === instance.instance_id);
      expect(row?.state, 'counter.per_instance[].state of the live instance').toBe('UP');
      await closeProbe(page);
    } finally {
      await stopProcess(shop.child);
    }
  },
);

test(
  'T-SEC-44c a session revoked outside the page ends its socket within 1 s -> /login?next, no reconnect',
  { tag: '@security', annotation: WS },
  async ({ page, context, ws, seed, apiAs }, testInfo) => {
    // The page runs on a session of its own that the test can revoke from outside the page.
    const api = await apiAs('viewer');
    await context.clearCookies();
    await context.addCookies(storageStateOf(api).cookies);
    await page.goto(`/apps/${String(seed.primary_app_id)}/xlog?from=now-5m&live=1`);
    await expectShell(page);
    await ws.waitForSubscription('xlog', 30_000);
    const path = new URL(page.url()).pathname;
    const before = ws.connectionCount();
    expect(before, 'connections of the live XLog before the revocation').toBe(1);
    const index = before - 1;

    const sent = Date.now();
    await api.logout();
    const revoked = Date.now();
    const closed = await ws.waitForClose(index, 5_000);
    const reauth = ws
      .serverFrames()
      .find((f) => f.connection === index && f.frame?.op === 'reauth');
    const serverClose = closed.by === 'server' && closed.code === 4401 ? closed.at : null;
    const ended = Math.min(
      reauth?.at ?? Number.POSITIVE_INFINITY,
      serverClose ?? Number.POSITIVE_INFINITY,
    );
    annotate(
      testInfo,
      'T-SEC-44c',
      `logout request ${String(revoked - sent)} ms; reauth ${reauth === undefined ? 'none' : `${String(reauth.at - revoked)} ms`} after it; close ${String(closed.code)} by the ${closed.by} ${String(closed.at - revoked)} ms after it`,
    );
    expect(
      Number.isFinite(ended),
      `the server ends the socket (reauth frame or close 4401); close ${String(closed.code)} by the ${closed.by}`,
    ).toBe(true);
    expect(ended - revoked, 'ms from the logout answer to reauth / close 4401').toBeLessThanOrEqual(
      1000,
    );
    await expect(page).toHaveURL(/\/login\?next=/, { timeout: 10_000 });
    expect((new URL(page.url()).searchParams.get('next') ?? '').startsWith(path)).toBe(true);
    // Back-off would retry after 1 s +-20 % (docs/15 §10): 5 s cover it.
    const end = Date.now() + 5_000;
    while (Date.now() < end) {
      expect(ws.connectionCount(), 'connections opened after the revocation').toBe(before);
      await page.waitForTimeout(100);
    }
  },
);
