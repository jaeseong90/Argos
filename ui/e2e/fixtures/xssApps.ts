// SPDX-License-Identifier: Apache-2.0
//
// The applications of the XSS seed sweep (WO-46, T-SEC-62; e2e/fixtures/xssSeed.ts): the seed
// traffic of the stack keeps the default capture (query string, headers, user ids off), so most XSS
// locations of docs/18 §4.3 never reach its data. The sweep therefore seeds two applications of
// its own once per stack run, through the real agent and the real collector:
//   e2e-xss-<suffix>        shop-boot3 with xssShopJvmArgs() (X-Seed header, user id plain, query
//                           string full, every profile kept) and ext-stub, every seed.xss.* entry of
//                           the shop catalog called once (smoke runner)
//   e2e-xss-batch-<suffix>  shop-batch (./gradlew :sample-apps:shop-batch:sampleAppJar), every
//                           seed.xss.batch.* entry (the payload as a Spring Batch job name)
// When the data is queryable the processes stop and their instances are archived (ADR-0010 C-241
// (7): no stopped instance stays FIRING); the data stays in the stack for the screens. The record
// ui/e2e/.stack/<run>/xss-seed/xss-seed.json lets a restarted worker reuse it (removed with the run).
// Inside the Playwright image of e2e:docker there is no JVM: xssAppsProblem names the reason.
import type { ChildProcess } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

import {
  apiInstances,
  createApp,
  issueToken,
  runSuffix,
  writeTokenFile,
} from '../required/adminChecks';
import type { ApiSession } from '../stack/api';
import { run } from '../stack/docker';
import { shopProblem, startShop } from '../stack/liveShop';
import { jarsDir, logsDir, REPO_ROOT, runDir } from '../stack/paths';
import { retireInstances } from '../stack/retire';
import { javaExecutable } from '../stack/seed/gradle';
import { isoSeconds, startProcess, stopProcess } from '../stack/seed/traffic';
import { freePort, waitFor } from '../stack/wait';

import { xssShopJvmArgs, xssSmokeArgs } from './xssSeed';

/** The shop-batch jar (not part of the stack's five jars; batch.spec.ts needs it too). */
export const SHOP_BATCH_JAR = join(
  REPO_ROOT,
  'sample-apps',
  'shop-batch',
  'build',
  'sample-app',
  'shop-batch.jar',
);

/** One seeded application of the sweep. */
export interface XssApp {
  readonly app_id: number;
  readonly name: string;
  readonly env: string;
  readonly instance_id: number;
  /** [first seed call - 1 min, last ingested point + 1 min). */
  readonly from: string;
  readonly to: string;
  /** Seed calls the smoke runner made (all answered as the catalog expects). */
  readonly seed_calls: number;
}

export interface XssApps {
  readonly run_id: string;
  readonly shop: XssApp;
  /** The batch application, or null with `batch_problem` (the shop-batch jar is not built). */
  readonly batch: XssApp | null;
  readonly batch_problem: string | null;
}

function recordFile(runId: string): string {
  return join(runDir(runId), 'xss-seed', 'xss-seed.json');
}

/** Why the sweep's applications cannot be seeded here (no JVM in e2e:docker), or null. */
export function xssAppsProblem(runId: string): string | null {
  return shopProblem(runId);
}

let pending: { readonly runId: string; readonly apps: Promise<XssApps> } | null = null;

/** The sweep's applications of the stack run (seeded on first use, then read from the record). */
export function ensureXssApps(
  runId: string,
  admin: () => Promise<ApiSession>,
  log: (line: string) => void,
): Promise<XssApps> {
  if (pending?.runId === runId) {
    return pending.apps;
  }
  const apps = (async () => {
    const file = recordFile(runId);
    if (existsSync(file)) {
      const record = JSON.parse(readFileSync(file, 'utf8')) as XssApps;
      if (record.run_id === runId) {
        log(`XSS seed apps of run ${runId} reused from ${file}`);
        return record;
      }
    }
    const api = await admin();
    const suffix = runSuffix(runId);
    const shop = await seedApp(runId, api, `e2e-xss-${suffix}`, 'shop', log);
    let batch: XssApp | null = null;
    let batchProblem: string | null = null;
    if (existsSync(SHOP_BATCH_JAR)) {
      batch = await seedApp(runId, api, `e2e-xss-batch-${suffix}`, 'batch', log);
    } else {
      batchProblem = `${SHOP_BATCH_JAR} is missing: ./gradlew :sample-apps:shop-batch:sampleAppJar`;
    }
    const record: XssApps = { run_id: runId, shop, batch, batch_problem: batchProblem };
    mkdirSync(join(runDir(runId), 'xss-seed'), { recursive: true });
    writeFileSync(file, `${JSON.stringify(record, null, 2)}\n`, 'utf8');
    return record;
  })();
  const entry = { runId, apps };
  pending = entry;
  apps.catch(() => {
    if (pending === entry) {
      pending = null;
    }
  });
  return apps;
}

/** Starts the app with the agent, calls its XSS seeds, waits until they are queryable, stops it. */
async function seedApp(
  runId: string,
  api: ApiSession,
  name: string,
  catalog: 'shop' | 'batch',
  log: (line: string) => void,
): Promise<XssApp> {
  const env = 'e2e';
  const app = await createApp(api, { name, env });
  const issued = await issueToken(api, app.app_id);
  const tokenFile = writeTokenFile(runId, `${name}.token`, issued.token);
  const java = javaExecutable();
  let extStub: ChildProcess | null = null;
  let shop: ChildProcess | null = null;
  let instanceId: number | null = null;
  try {
    const processEnv: Record<string, string> = {};
    if (catalog === 'shop') {
      const extPort = await freePort();
      extStub = startProcess(
        java,
        [
          '-jar',
          join(jarsDir(runId), 'ext-stub.jar'),
          '--port',
          String(extPort),
          '--bind',
          '127.0.0.1',
        ],
        {},
        join(logsDir(runId), `${name}-ext-stub.log`),
      );
      processEnv.SAMPLE_EXT_URL = `http://127.0.0.1:${String(extPort)}`;
    }
    const instanceName = `${name}-1`;
    const started = await startShop(runId, {
      instance: instanceName,
      logFile: `${name}.log`,
      app: { name, env, tokenFile },
      jvmArgs: xssShopJvmArgs(),
      env: processEnv,
      ...(catalog === 'batch' ? { jar: SHOP_BATCH_JAR } : {}),
    });
    shop = started.child;
    instanceId = await waitFor(
      async () => {
        const row = (await apiInstances(api, app.app_id)).find(
          (i) => String(i.name) === instanceName,
        );
        return row !== undefined &&
          row.connected_at !== null &&
          (row.state === 'UP' || row.state === 'WARN')
          ? row.instance_id
          : undefined;
      },
      { timeoutMs: 120_000, intervalMs: 1000, what: `${instanceName} with a live session` },
    );
    const from = Date.now() - 60_000;
    const reportDir = join(runDir(runId), 'xss-seed', catalog);
    const smoke = await run(
      java,
      xssSmokeArgs(join(jarsDir(runId), 'smoke.jar'), catalog, started.url, reportDir),
      { allowFailure: true, timeoutMs: 5 * 60_000 },
    );
    if (smoke.code !== 0) {
      throw new Error(
        `XSS seeds of ${name} (smoke runner, catalog ${catalog}) exited with ${String(smoke.code)}: ${smoke.stdout.slice(-1500)}`,
      );
    }
    const report = JSON.parse(readFileSync(join(reportDir, 'smoke.json'), 'utf8')) as {
      summary?: { passed?: number };
    };
    const calls = report.summary?.passed ?? 0;
    // Queryable: the transactions of the seed services (/seed/...) hold every seed call and stay
    // the same twice. Only those: shop-batch's scheduled and Quartz jobs keep adding transactions.
    let last = -1;
    let stable = 0;
    await waitFor(
      async () => {
        const services = await api.data<{ service: string | null; count: number }[]>(
          `/apps/${String(app.app_id)}/services/stats`,
          {
            from: isoSeconds(from),
            to: isoSeconds(Date.now() + 60_000),
            kind: 'ALL',
            limit: 500,
          },
        );
        const current = services
          .filter((s) => s.service?.includes(' /seed/') === true)
          .reduce((n, s) => n + s.count, 0);
        stable = current === last && current >= calls ? stable + 1 : 0;
        last = current;
        return stable >= 2 ? current : undefined;
      },
      { timeoutMs: 180_000, intervalMs: 3000, what: `the XSS seed calls of ${name} in the API` },
    );
    log(`XSS seed app ${name} (${String(app.app_id)}): ${String(calls)} seed calls queryable`);
    const to = Date.now() + 60_000;
    await stopProcess(shop);
    shop = null;
    await retireInstances(api, [instanceId]);
    const retired = instanceId;
    instanceId = null;
    return {
      app_id: app.app_id,
      name,
      env,
      instance_id: retired,
      from: isoSeconds(from),
      to: isoSeconds(to),
      seed_calls: calls,
    };
  } finally {
    await stopProcess(shop);
    await stopProcess(extStub);
    if (instanceId !== null) {
      // A failed seeding archives its instance too (no stopped instance stays FIRING, C-241 (7)).
      await retireInstances(api, [instanceId]).catch((error: unknown) => {
        log(`archiving ${name}-1 after the failure: ${String(error)}`);
      });
    }
  }
}
