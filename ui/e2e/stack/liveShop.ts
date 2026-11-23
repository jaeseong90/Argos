// SPDX-License-Identifier: Apache-2.0
//
// shop-boot3 with the agent jar of the stack, started from a spec while the stack runs (the seed
// and T-SV-020e start it the same way): T-E2E-099 (overview live refresh) and T-E2E-active-31
// (running transactions reported by the real agent, WO-16). The shop reports as the seed's
// application (TRAFFIC_APP) under the instance name the caller gives, so that specs running in
// parallel workers do not replace each other's session. Inside the Playwright image of e2e:docker
// there is no JVM: shopProblem names the reason and the caller skips or falls back.
import type { ChildProcess } from 'node:child_process';
import { existsSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

import { jarsDir, logsDir, secretsDir } from './paths';
import { readRunState } from './runState';
import { javaExecutable } from './seed/gradle';
import { startProcess, stopProcess, TRAFFIC_APP } from './seed/traffic';
import { freePort, httpGet, waitFor } from './wait';

/** True inside the Playwright image of e2e:docker (no JVM there). */
const IN_CONTAINER = (process.env.ARGOS_E2E_LINUX_IMAGE ?? '') !== '';

export interface RunningShop {
  readonly url: string;
  readonly child: ChildProcess;
}

export interface ShopOptions {
  /** Instance name the agent reports (`argos.app.instance`). */
  readonly instance: string;
  /** File name of the shop's log under the run's logs directory. */
  readonly logFile: string;
}

/** Why the shop cannot start here, or null. */
export function shopProblem(runId: string): string | null {
  if (IN_CONTAINER) {
    return 'no JVM in the Playwright image of e2e:docker to run shop-boot3 with the agent';
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

/** shop-boot3 with the agent jar of the stack, as the seed and T-SV-020e run it. */
export async function startShop(runId: string, options: ShopOptions): Promise<RunningShop> {
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
      `-Dargos.app.instance=${options.instance}`,
      '-jar',
      join(jarsDir(runId), 'shop-boot3.jar'),
      `--server.port=${String(port)}`,
      '--server.address=127.0.0.1',
    ],
    {},
    join(logs, options.logFile),
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
