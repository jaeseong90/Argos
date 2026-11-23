// SPDX-License-Identifier: Apache-2.0
//
// Test fixtures of the active transactions screen (docs/14 §4.9 schema). Test-only module.
import type { ActiveInstance, ActiveItem, ActiveTransactions } from '../../../api/active';
import { untrusted } from '../../../api/untrusted';

export const SNAPSHOT_AT = '2026-11-23T10:00:00.000Z';

export function activeInstance(
  instanceId: number,
  name: string | null,
  overrides: Partial<ActiveInstance> = {},
): ActiveInstance {
  return {
    instance_id: instanceId,
    name: name === null ? null : untrusted(name),
    state: 'UP',
    snapshot_at: SNAPSHOT_AT,
    count: 0,
    max_elapsed_ms: null,
    buckets: [0, 0, 0, 0],
    truncated: false,
    ...overrides,
  };
}

export function activeItem(
  instanceId: number,
  txId: string,
  elapsedMs: number,
  overrides: Partial<ActiveItem> = {},
): ActiveItem {
  return {
    instance_id: instanceId,
    tx_id: txId,
    service: untrusted(`/api/service-${txId}`),
    service_id: `9${txId}`,
    elapsed_ms: elapsedMs,
    state: 'METHOD',
    state_detail: null,
    thread: untrusted(`http-nio-8080-exec-${txId}`),
    virtual_thread: false,
    user_id: untrusted(`user-${txId}`),
    user_id_mode: 'plain',
    remote_ip: '10.0.0.1',
    ip_mode: 'full',
    kind: 'HTTP',
    started_at: new Date(Date.parse(SNAPSHOT_AT) - elapsedMs).toISOString(),
    ...overrides,
  };
}

/** Buckets (1/3/8 s) and max of an instance computed from its items, like the server. */
export function withServerBuckets(instance: ActiveInstance, items: readonly ActiveItem[]) {
  const own = items.filter((item) => item.instance_id === instance.instance_id);
  const buckets = [0, 0, 0, 0];
  let max: number | null = null;
  for (const item of own) {
    const e = item.elapsed_ms;
    const band = e < 1000 ? 0 : e < 3000 ? 1 : e < 8000 ? 2 : 3;
    buckets[band] = (buckets[band] ?? 0) + 1;
    max = max === null ? e : Math.max(max, e);
  }
  return {
    ...instance,
    count: instance.count > 0 ? instance.count : own.length,
    buckets,
    max_elapsed_ms: max,
  };
}

/** A snapshot whose per-instance buckets match its items (server rules). */
export function activeSnapshot(
  instances: readonly ActiveInstance[],
  items: readonly ActiveItem[],
  snapshotAt: string | null = SNAPSHOT_AT,
): ActiveTransactions {
  return {
    snapshot_at: snapshotAt,
    per_instance: instances.map((instance) => withServerBuckets(instance, items)),
    items: [...items].sort((a, b) => b.elapsed_ms - a.elapsed_ms),
  };
}

/**
 * The standard scenario: two instances, every band and the five states, both kinds, user id and IP
 * collected on instance 1 and not collected on instance 2, one virtual thread.
 */
export function scenario(): ActiveTransactions {
  const items: ActiveItem[] = [
    activeItem(1, '101', 12_500, {
      state: 'SQL',
      state_detail: untrusted('SELECT * FROM orders WHERE id = ?'),
      service: untrusted('/api/orders'),
      service_id: '501',
    }),
    activeItem(1, '102', 5_000, {
      state: 'HTTP',
      state_detail: untrusted('pay.example.com'),
      service: untrusted('/api/pay'),
      service_id: '502',
    }),
    activeItem(1, '103', 2_000, {
      state: 'DBC',
      state_detail: untrusted('jdbc:postgresql://db:5432/shop'),
      service: untrusted('/api/orders'),
      service_id: '501',
      virtual_thread: true,
    }),
    activeItem(1, '104', 300, { state: 'METHOD', kind: 'BATCH', service: untrusted('nightly') }),
    activeItem(2, '201', 9_000, {
      state: 'ASYNC',
      service: untrusted('/api/pay'),
      service_id: '502',
      user_id: null,
      user_id_mode: 'off',
      remote_ip: null,
      ip_mode: 'off',
    }),
    activeItem(2, '202', 700, {
      state: 'METHOD',
      user_id: null,
      user_id_mode: 'plain',
      remote_ip: null,
      ip_mode: 'full',
    }),
  ];
  return activeSnapshot(
    [activeInstance(1, 'order-1'), activeInstance(2, 'order-2', { state: 'WARN' })],
    items,
  );
}
