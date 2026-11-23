// SPDX-License-Identifier: Apache-2.0
//
// The alert seed (e2e/stack/seed/alerts.ts, WO-25) creates the error rate rule through the API as
// docs/14 §4.13 shapes it, polls GET /alerts/events until the engine reports the rule FIRING (never
// a fixed wait), reads the template rules of the app, and fails with the reason when the engine
// never fires.
import { describe, expect, it } from 'vitest';

import {
  createdRuleId,
  firingEventOf,
  SEED_RULE_NAME,
  seedAlert,
  seedRuleBody,
  templateRulesOf,
  type AlertSeedApi,
} from './alerts';

const T0 = Date.parse('2030-01-01T00:00:00.000Z');
const RULE = '6f1e0000-0000-4000-8000-000000000001';
const EVENT = '6f1e0000-0000-4000-8000-0000000000e1';

interface Fake {
  readonly api: AlertSeedApi;
  readonly posts: unknown[];
  readonly queries: { path: string; query: unknown }[];
  readonly now: () => number;
  readonly sleep: (ms: number) => Promise<void>;
}

/** A fake server: the rule fires `fireAfterMs` after its creation; templates appear after `templatesAfterMs`. */
function fake(fireAfterMs: number | null, templatesAfterMs: number): Fake {
  let clock = T0;
  let createdAt: number | null = null;
  const posts: unknown[] = [];
  const queries: { path: string; query: unknown }[] = [];
  const api: AlertSeedApi = {
    request(method, path, options) {
      expect(method).toBe('POST');
      expect(path).toBe('/alerts/rules');
      posts.push(options?.body);
      createdAt = clock;
      return Promise.resolve({ status: 201, body: { data: { rule_id: RULE, name: 'x' } } });
    },
    query(path, query) {
      queries.push({ path, query });
      const since = createdAt === null ? -1 : clock - createdAt;
      if (path === '/alerts/events') {
        const firing = fireAfterMs !== null && since >= fireAfterMs;
        return Promise.resolve({
          data: firing
            ? [
                {
                  event_id: 'other',
                  ts: '2030-01-01T00:00:01Z',
                  state: 'FIRING',
                  severity: 'warn',
                  rule: { id: 'another-rule', name: 'p95' },
                },
                {
                  event_id: EVENT,
                  ts: '2030-01-01T00:00:20Z',
                  state: 'FIRING',
                  severity: 'warn',
                  rule: { id: RULE, name: SEED_RULE_NAME },
                },
              ]
            : [],
          meta: {},
        });
      }
      return Promise.resolve({
        data: [
          { rule_id: RULE, name: SEED_RULE_NAME, template_key: null },
          ...(since >= templatesAfterMs
            ? [{ rule_id: 'r-err', name: 'Error rate', template_key: 'error_rate' }]
            : []),
        ],
        meta: {},
      });
    },
  };
  return {
    api,
    posts,
    queries,
    now: () => clock,
    sleep: (ms) => {
      clock += ms;
      return Promise.resolve();
    },
  };
}

describe('alert seed (WO-25)', () => {
  it('creates the 1 h error rate rule (ClickHouse window, for 0 s, min samples 1, no grace, no channel)', () => {
    expect(seedRuleBody(3, 'prod')).toEqual({
      name: SEED_RULE_NAME,
      enabled: true,
      target: { app_id: 3, env: 'prod', type: 'app', ids: [] },
      kind: 'HTTP',
      metric: 'error_rate',
      window: '1h',
      for: '0s',
      evaluation_unit: 'app',
      condition: { operator: '>=', warn: 0 },
      min_samples: 1,
      no_data: 'ignore',
      warmup_grace: '0s',
      channels: { warn: [], critical: [] },
      message_template: '{{app}}/{{env}} error rate {{value}} >= {{threshold}} {{link}}',
    });
  });

  it('polls GET /alerts/events until the rule fires and records the event and the template rules', async () => {
    const f = fake(24_000, 30_000);
    const lines: string[] = [];
    const alert = await seedAlert({
      api: f.api,
      appId: 3,
      env: 'prod',
      log: (line) => lines.push(line),
      now: f.now,
      sleep: f.sleep,
    });
    expect(alert).toEqual({
      app_id: 3,
      env: 'prod',
      rule_id: RULE,
      rule_name: SEED_RULE_NAME,
      event_id: EVENT,
      severity: 'warn',
      fired_at: '2030-01-01T00:00:20Z',
      template_rules: [{ rule_id: 'r-err', template_key: 'error_rate', name: 'Error rate' }],
    });
    expect(f.posts).toHaveLength(1);
    const events = f.queries.filter((q) => q.path === '/alerts/events');
    // Polled every 2 s until 24 s after the creation: 13 questions, each for the rule's FIRING events.
    expect(events).toHaveLength(13);
    expect(events[0]?.query).toMatchObject({ app: 3, state: 'FIRING', rule: RULE });
    expect(f.queries.filter((q) => q.path === '/alerts/rules').length).toBeGreaterThan(1);
    expect(lines.join('\n')).toMatch(/FIRING \(warn\) after 24 s/);
  });

  it('fails with the reason when the engine never fires the rule', async () => {
    const f = fake(null, 0);
    await expect(
      seedAlert({
        api: f.api,
        appId: 3,
        env: 'prod',
        log: () => undefined,
        now: f.now,
        sleep: f.sleep,
        firingTimeoutMs: 10_000,
      }),
    ).rejects.toThrow(/no FIRING event of rule .* within 10 s/);
  });

  it('records no template rules when the server applied none (the manifest names it)', async () => {
    const f = fake(0, Number.MAX_SAFE_INTEGER);
    const alert = await seedAlert({
      api: f.api,
      appId: 3,
      env: 'prod',
      log: () => undefined,
      now: f.now,
      sleep: f.sleep,
      templatesTimeoutMs: 6_000,
    });
    expect(alert.template_rules).toEqual([]);
  });

  it('reads the answers leniently and only what the contract names', () => {
    expect(createdRuleId({ data: { rule_id: 'a' } })).toBe('a');
    expect(createdRuleId({ rule_id: 7 })).toBe('7');
    expect(() => createdRuleId({ data: {} })).toThrow(/rule_id/);
    expect(firingEventOf(null, RULE)).toBeNull();
    expect(
      firingEventOf(
        [{ event_id: EVENT, ts: 'x', state: 'ACKED', severity: 'warn', rule: { id: RULE } }],
        RULE,
      ),
    ).toBeNull();
    expect(templateRulesOf([{ id: 'r', name: 'n', template_key: 'p95_latency' }])).toEqual([
      { rule_id: 'r', template_key: 'p95_latency', name: 'n' },
    ]);
    expect(templateRulesOf({})).toEqual([]);
  });
});
