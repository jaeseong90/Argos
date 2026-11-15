// SPDX-License-Identifier: Apache-2.0
//
// ui/e2e/.stack/session.json: the stack the current Playwright run talks to (written by the
// stack-setup project, read by the fixtures and the stack-teardown project). It is separate from
// run.json (owned by the process that started the containers) so that a run that reuses a stack
// (ARGOS_E2E_STACK_URL, e.g. inside the Linux Playwright container of e2e:docker, which mounts ui/)
// never changes the state the stack's owner tears down with. No secret is in it.
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';

import { z } from 'zod';

import { STACK_DIR } from './paths.ts';

export const SESSION_FILE = join(STACK_DIR, 'session.json');

export const StackSession = z.object({
  run_id: z.string(),
  reused: z.boolean(),
  stack_url: z.url(),
  management_url: z.url().nullable(),
  csp_violations_at_start: z.number().nullable(),
  /** Why the CSP counter could not be read (annotation csp-counter-not-available), or null. */
  csp_counter_problem: z.string().nullable(),
  started_at: z.string(),
  setup_seconds: z.number(),
  timings: z.record(z.string(), z.number()),
});
export type StackSession = z.infer<typeof StackSession>;

export function writeSession(session: StackSession, file: string = SESSION_FILE): void {
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, `${JSON.stringify(StackSession.parse(session), null, 2)}\n`, 'utf8');
}

export function readSession(file: string = SESSION_FILE): StackSession {
  if (!existsSync(file)) {
    throw new Error(
      `${file} does not exist: the stack-setup project writes it before the stack specs run (corepack pnpm e2e:stack runs both)`,
    );
  }
  return StackSession.parse(JSON.parse(readFileSync(file, 'utf8')));
}
