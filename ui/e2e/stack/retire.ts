// SPDX-License-Identifier: Apache-2.0
//
// Retires the agent instances a spec started for itself (ADR-0010 C-241 (7)): the feeds (e2e-active,
// e2e-cmd) and the live shops with their own instance names (e2e-shop-active-31, e2e-shop-async-vt,
// e2e-shop-dumps-30, wo17-cmd, wo18-batch). Once their process ends, a spec archives their
// instances through the real API (POST /instances/{id}/archive, operator), so the server never
// keeps a stopped spec instance as DOWN: its instance_down alert does not fire, or resolves as
// target_gone at the next alert housekeeping (C-236 (2), at most 60 s). Without this, a stopped
// spec instance stays FIRING and fills the firing panels that later specs (T-E2E-alerts-9, -10,
// T-E2E-overview-12) read in an order-dependent way. The panel rule itself is unchanged.
//
// A reconnect lists an archived instance again (14 §4.3), so a later spec that starts the same feed
// or shop name gets its instance back. The seed's own instance (TRAFFIC_INSTANCE, e2e-shop-1) is
// never retired: other specs read it.
import { ApiError, type ApiSession } from './api';
import { sleep } from './wait';

/** How long a stopped instance may still hold its session (409 invalid_state) before giving up. */
export const RETIRE_TIMEOUT_MS = 60_000;

/** What happened to one instance. */
export type RetireOutcome = 'archived' | 'gone';

/** How an archive answer is read: done, retry (session still open) or a failure. */
export function archiveAnswer(status: number): RetireOutcome | 'retry' | 'error' {
  if (status === 204) {
    return 'archived';
  }
  if (status === 404) {
    // Deleted already (or never stored): nothing can fire for it.
    return 'gone';
  }
  if (status === 409) {
    // invalid_state: the server still sees the session (it ends with the process).
    return 'retry';
  }
  return 'error';
}

/** How long to wait and how often to ask again. */
export interface RetireOptions {
  readonly timeoutMs?: number;
  readonly retryMs?: number;
}

/** The part of ApiSession used here (a unit test passes a stub). */
export type ArchiveApi = Pick<ApiSession, 'request'>;

/**
 * Archives every instance of `instanceIds`; waits while one is still connected (409). Throws when
 * an instance cannot be archived within the timeout, so a cleanup failure is visible.
 */
export async function retireInstances(
  api: ArchiveApi,
  instanceIds: readonly number[],
  options: RetireOptions = {},
): Promise<Map<number, RetireOutcome>> {
  const outcomes = new Map<number, RetireOutcome>();
  const deadline = Date.now() + (options.timeoutMs ?? RETIRE_TIMEOUT_MS);
  for (const id of new Set(instanceIds)) {
    for (;;) {
      const response = await api.request('POST', `/instances/${String(id)}/archive`, {
        expect: [204, 404, 409],
      });
      const answer = archiveAnswer(response.status);
      if (answer === 'archived' || answer === 'gone') {
        outcomes.set(id, answer);
        break;
      }
      if (answer === 'error' || Date.now() >= deadline) {
        throw new ApiError(
          'POST',
          `/instances/${String(id)}/archive`,
          response.status,
          response.body,
        );
      }
      await sleep(options.retryMs ?? 1000);
    }
  }
  return outcomes;
}

/** An instance row of GET /apps/{id}/instances (the fields used here). */
interface InstanceRow {
  readonly instance_id: number;
  readonly name: string | null;
}

/**
 * Archives the instances of `appId` named `names` (the live shops: their ids are looked up by
 * name). Names the list does not show (never registered, archived already) are skipped.
 */
export async function retireInstancesNamed(
  api: ApiSession,
  appId: number,
  names: readonly string[],
  options: RetireOptions = {},
): Promise<Map<number, RetireOutcome>> {
  const rows = await api.data<InstanceRow[]>(`/apps/${String(appId)}/instances`);
  const ids = rows
    .filter((r) => r.name !== null && names.includes(r.name))
    .map((r) => r.instance_id);
  return retireInstances(api, ids, options);
}
