// SPDX-License-Identifier: Apache-2.0
//
// Seed (b2): a real collection outage of the traffic app (WO-22, docs/05 §2.1-10, D-18, ADR-0010
// C-205 (2)). The seeded agent reaches the server through e2e/stack/seed/grpcProxy.ts; after the
// seed traffic is queryable the setup freezes the proxy, so the server hears nothing from the
// agent while the agent process keeps running. The setup watches the instance through the REST API
// until the server's state machine marks it STALE (12 §4.2: no heartbeat for `stale-after`, a
// transition event with reason session_lost), then thaws the proxy: the same process (the same
// instance row) is heard again and the instance is UP. The outage of the API (meta.outages of
// GET /apps/{app}/xlog, built from those state events, api/events/Outages) is checked and recorded
// in the seed manifest, so the XLog check of 05 §2.1-10 compares the shaded band with it.
//
// It never waits a fixed time: the cut lasts until the API reports STALE (with the E2E stack's
// 20 s stale-after and 5 s heartbeat, ADR-0010 C-216 (1), that is about 15 to 25 s after the cut;
// with the server defaults of 60 s and 30 s it would be 30 to 70 s).
import { isoSeconds } from './traffic.ts';

/** The part of ApiSession the outage seed uses. */
export interface OutageApi {
  query(
    path: string,
    query?: Readonly<Record<string, string | number | boolean | readonly string[] | undefined>>,
  ): Promise<{ data: unknown; meta: Record<string, unknown> }>;
}

/** The part of GrpcProxy the outage seed uses. */
export interface OutageSwitch {
  freeze(): void;
  thaw(): void;
}

/** The recorded outage (seed manifest `outage`, e2e/stack/manifest.ts). Times are host clock ISO. */
export interface SeedOutage {
  readonly app_id: number;
  readonly instance_id: number;
  readonly instance_name: string;
  /** The proxy was frozen. */
  readonly cut_at: string;
  /** The API first reported the instance STALE (or DOWN). */
  readonly stale_seen_at: string;
  /** The proxy was thawed. */
  readonly thawed_at: string;
  /** The API first reported the instance UP (or WARN) again. */
  readonly up_seen_at: string;
  /** The API's outage of the instance (meta.outages): server times of the state events. */
  readonly outage_from: string;
  readonly outage_to: string;
  readonly reason: 'session_lost';
}

export interface OutageOptions {
  readonly api: OutageApi;
  readonly appId: number;
  readonly instanceName: string;
  readonly proxy: OutageSwitch;
  readonly log: (line: string) => void;
  readonly now?: () => number;
  readonly sleep?: (ms: number) => Promise<void>;
  /** Longest wait for STALE after the cut (default 150 s). */
  readonly staleTimeoutMs?: number;
  /** Longest wait for UP after the thaw (default 90 s). */
  readonly upTimeoutMs?: number;
  readonly pollMs?: number;
  /** Allowed difference between the host clock and the server's event times (default 5 s). */
  readonly clockToleranceMs?: number;
}

interface ApiInstance {
  instance_id: number;
  name: string;
  state: string;
}

/** meta.outages[] of 14 §4.5/§4.15: {from, to, reason}, no instance (filter with instance[]). */
interface ApiOutage {
  from: string;
  to?: string | null;
  reason: string;
}

const DOWN_STATES = new Set(['STALE', 'DOWN']);
const LIVE_STATES = new Set(['UP', 'WARN']);

/** The full milliseconds ISO-8601 instant. */
function iso(ms: number): string {
  return new Date(ms).toISOString();
}

/** Cuts the collection path of `instanceName` until it is STALE, then restores it (file comment). */
export async function runOutage(options: OutageOptions): Promise<SeedOutage> {
  const now = options.now ?? Date.now;
  const sleep =
    options.sleep ?? ((ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)));
  const pollMs = options.pollMs ?? 2000;
  const tolerance = options.clockToleranceMs ?? 5000;
  const { api, appId, instanceName, proxy, log } = options;
  const instance = async (): Promise<ApiInstance> => {
    const rows = (await api.query(`/apps/${String(appId)}/instances`)).data as ApiInstance[];
    const row = rows.find((r) => r.name === instanceName);
    if (row === undefined) {
      throw new Error(`instance ${instanceName} of app ${String(appId)} is not in the API`);
    }
    return row;
  };
  const waitState = async (states: ReadonlySet<string>, timeoutMs: number, what: string) => {
    const deadline = now() + timeoutMs;
    for (;;) {
      const row = await instance();
      if (states.has(row.state)) {
        return now();
      }
      if (now() >= deadline) {
        throw new Error(
          `outage seed: instance ${instanceName} not ${what} within ${String(timeoutMs / 1000)} s (state ${row.state})`,
        );
      }
      await sleep(pollMs);
    }
  };

  const before = await instance();
  if (!LIVE_STATES.has(before.state)) {
    throw new Error(
      `outage seed: instance ${instanceName} is ${before.state} before the cut (expected UP or WARN)`,
    );
  }
  const cutAt = now();
  proxy.freeze();
  let frozen = true;
  log(
    `collection outage: gRPC proxy frozen, waiting for instance ${String(before.instance_id)} to be STALE`,
  );
  try {
    const staleSeen = await waitState(DOWN_STATES, options.staleTimeoutMs ?? 150_000, 'STALE');
    proxy.thaw();
    frozen = false;
    const thawedAt = now();
    log(
      `collection outage: STALE ${((staleSeen - cutAt) / 1000).toFixed(1)} s after the cut, proxy thawed`,
    );
    const upSeen = await waitState(LIVE_STATES, options.upTimeoutMs ?? 90_000, 'UP again');
    log(`collection outage: UP ${((upSeen - thawedAt) / 1000).toFixed(1)} s after the thaw`);
    const from = isoSeconds(cutAt - 60_000);
    const to = isoSeconds(upSeen + 61_000);
    const response = await api.query(`/apps/${String(appId)}/xlog`, {
      from,
      to,
      kind: 'ALL',
      limit: 1,
      instance: [String(before.instance_id)],
    });
    // With instance[] the API lists that instance's outages and the server's (server_down, no
    // instance): the instance's are the others.
    const outages = ((response.meta.outages ?? []) as ApiOutage[]).filter(
      (o) => o.reason !== 'server_down',
    );
    if (outages.length !== 1) {
      throw new Error(
        `outage seed: GET /apps/${String(appId)}/xlog?from=${from}&to=${to} lists ${String(outages.length)} outages of instance ${String(before.instance_id)} (expected 1): ${JSON.stringify(response.meta.outages)}`,
      );
    }
    const outage = outages[0] as ApiOutage;
    const outageFrom = Date.parse(outage.from);
    const outageTo = outage.to == null ? Number.NaN : Date.parse(outage.to);
    const problems: string[] = [];
    if (outage.reason !== 'session_lost') {
      problems.push(`reason ${outage.reason} (expected session_lost)`);
    }
    if (!(outageFrom >= cutAt - tolerance && outageFrom <= staleSeen + tolerance)) {
      problems.push(
        `from ${outage.from} outside [cut ${iso(cutAt)}, STALE seen ${iso(staleSeen)}]`,
      );
    }
    if (!(outageTo >= thawedAt - tolerance && outageTo <= upSeen + tolerance)) {
      problems.push(
        `to ${String(outage.to)} outside [thaw ${iso(thawedAt)}, UP seen ${iso(upSeen)}]`,
      );
    }
    if (problems.length > 0) {
      throw new Error(`outage seed: the API outage does not match the cut: ${problems.join('; ')}`);
    }
    return {
      app_id: appId,
      instance_id: before.instance_id,
      instance_name: instanceName,
      cut_at: iso(cutAt),
      stale_seen_at: iso(staleSeen),
      thawed_at: iso(thawedAt),
      up_seen_at: iso(upSeen),
      outage_from: new Date(outageFrom).toISOString(),
      outage_to: new Date(outageTo).toISOString(),
      reason: 'session_lost',
    };
  } finally {
    if (frozen) {
      proxy.thaw();
    }
  }
}
