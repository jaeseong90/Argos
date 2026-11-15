// SPDX-License-Identifier: Apache-2.0
//
// ui/e2e/.stack/run.json: the state of the running E2E stack (run id, owner process, URLs,
// container names). It holds no secret. The setup writes it, the specs and fixtures read the URLs
// from it, the teardown and the next run's leftover check read the run id.
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { dirname } from 'node:path';

import { z } from 'zod';

import { RUN_FILE } from './paths.ts';

export const RunState = z.object({
  run_id: z.string().regex(/^[a-z0-9-]{4,64}$/),
  created_at: z.string(),
  /** Process that started the stack (a leftover of a dead owner may be removed by the next run). */
  owner_pid: z.number().int(),
  /** True when the stack was reused (ARGOS_E2E_STACK_URL): never torn down by this process. */
  reused: z.boolean(),
  /** The public URL = the URL the browser uses (argos.web.public-url). */
  stack_url: z.url(),
  /** The management port as seen from the host (health, Prometheus), or null when unknown. */
  management_url: z.url().nullable(),
  grpc_port: z.number().int().nullable(),
  clickhouse_url: z.url().nullable(),
  postgres_jdbc_url: z.string().nullable(),
  containers: z
    .object({
      network: z.string(),
      postgres: z.string(),
      clickhouse: z.string(),
      server: z.string(),
    })
    .nullable(),
  /** argos_csp_violations_total when the setup finished (T-SEC-46 run-level check), or null. */
  csp_violations_at_start: z.number().nullable(),
  /** Seconds the setup phases took (report: E2E duration, 18 §2.1). */
  timings: z.record(z.string(), z.number()),
});
export type RunState = z.infer<typeof RunState>;

/** owner_pid of a stack started by `node e2e/stack/cli.ts up`: kept until `down`, never a leftover. */
export const DETACHED_OWNER = -1;

export function writeRunState(state: RunState, file: string = RUN_FILE): void {
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, `${JSON.stringify(RunState.parse(state), null, 2)}\n`, 'utf8');
}

export function readRunState(file: string = RUN_FILE): RunState | null {
  if (!existsSync(file)) {
    return null;
  }
  return RunState.parse(JSON.parse(readFileSync(file, 'utf8')));
}

export function requireRunState(file: string = RUN_FILE): RunState {
  const state = readRunState(file);
  if (state === null) {
    throw new Error(
      `${file} does not exist: the stack is not running (the stack-setup project starts it; corepack pnpm e2e:stack)`,
    );
  }
  return state;
}

export function removeRunState(file: string = RUN_FILE): void {
  rmSync(file, { force: true });
}

/** Whether the process `pid` is alive (signal 0). */
export function processAlive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    // EPERM: it exists but belongs to someone else.
    return (error as NodeJS.ErrnoException).code === 'EPERM';
  }
}

/** A new run id: UTC time + 4 random hex digits, e.g. 20261114-101500-a1b2 (names argos-e2e-<id>-*). */
export function newRunId(now: Date = new Date()): string {
  const pad = (n: number): string => String(n).padStart(2, '0');
  const stamp = `${String(now.getUTCFullYear())}${pad(now.getUTCMonth() + 1)}${pad(now.getUTCDate())}-${pad(now.getUTCHours())}${pad(now.getUTCMinutes())}${pad(now.getUTCSeconds())}`;
  const random = Math.floor(Math.random() * 0x10000)
    .toString(16)
    .padStart(4, '0');
  return `${stamp}-${random}`;
}
