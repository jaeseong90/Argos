// SPDX-License-Identifier: Apache-2.0
//
// Seed (e): a real FIRING alert of the alert engine (WO-25, docs/05 §2.3-5 and §2.10-4, ADR-0010
// C-212 (2)). After the traffic seed, the operator creates one rule on the primary generator app
// through the real API (POST /alerts/rules, docs/14 §4.13): error rate of HTTP over a 1 hour window
// (beyond the engine's 15 minute memory, so the ClickHouse provider reads the generator data the
// stack loaded a few minutes earlier), `for` 0 s, minimum samples 1, no warm-up grace, warning at
// ">= 0" (any traffic in the window fires it, whatever the generator's error rate), no channel (no
// notification leaves the stack). The setup then polls GET /alerts/events?state=FIRING for the rule
// (the engine evaluates every 10 s) and records the event in the seed manifest, so the dashboard
// and overview checks compare the firing panels with the API and open that alert.
//
// It also records the rules of the default templates on the primary app (GET /alerts/rules?app=):
// the server applies the recommended rules to an application it has not applied them to yet
// (apps.recommended_rules_applied, F-S24), also to the applications the generator loader inserted,
// so their presence proves the recommended apply end to end.
//
// It never waits a fixed time: each wait polls the API until the answer is there or the timeout
// (90 s by default) is over.

/** The part of ApiSession the alert seed uses. */
export interface AlertSeedApi {
  request(
    method: string,
    path: string,
    options?: { readonly body?: unknown; readonly expect?: readonly number[] },
  ): Promise<{ readonly status: number; readonly body: unknown }>;
  query(
    path: string,
    query?: Readonly<Record<string, string | number | boolean | readonly string[] | undefined>>,
  ): Promise<{ data: unknown; meta: Record<string, unknown> }>;
}

/** The recorded alert (seed manifest `alert`, e2e/stack/manifest.ts ManifestAlert). */
export interface SeedAlert {
  readonly app_id: number;
  readonly env: string;
  readonly rule_id: string;
  readonly rule_name: string;
  readonly event_id: string;
  readonly severity: 'warn' | 'critical';
  readonly fired_at: string;
  readonly template_rules: readonly {
    readonly rule_id: string;
    readonly template_key: string;
    readonly name: string;
  }[];
}

export interface AlertSeedOptions {
  readonly api: AlertSeedApi;
  readonly appId: number;
  readonly env: string;
  readonly log: (line: string) => void;
  readonly now?: () => number;
  readonly sleep?: (ms: number) => Promise<void>;
  /** Longest wait for the FIRING event (default 90 s). */
  readonly firingTimeoutMs?: number;
  /** Longest wait for the template rules of the app (default 90 s). */
  readonly templatesTimeoutMs?: number;
  readonly pollMs?: number;
}

/** Name of the seed rule (an entered string: the panels render it as text). */
export const SEED_RULE_NAME = 'E2E seed error rate';

/** The seed rule (docs/14 §4.13 rule schema) for the app and environment. */
export function seedRuleBody(appId: number, env: string): Record<string, unknown> {
  return {
    name: SEED_RULE_NAME,
    enabled: true,
    target: { app_id: appId, env, type: 'app', ids: [] },
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
  };
}

interface ApiEvent {
  event_id?: unknown;
  ts?: unknown;
  state?: unknown;
  severity?: unknown;
  rule?: { id?: unknown; name?: unknown } | null;
}

interface ApiRule {
  rule_id?: unknown;
  id?: unknown;
  name?: unknown;
  template_key?: unknown;
}

function object(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
}

/** The rule id of a POST /alerts/rules answer (`{data: rule}` or the rule itself). */
export function createdRuleId(body: unknown): string {
  const envelope = object(body);
  const rule = object(envelope?.data) ?? envelope;
  const id = rule?.rule_id ?? rule?.id;
  if (typeof id !== 'string' && typeof id !== 'number') {
    throw new Error('POST /alerts/rules: the answer names no rule_id');
  }
  return String(id);
}

/** The FIRING event of `ruleId` in a GET /alerts/events answer, or null. */
export function firingEventOf(
  events: unknown,
  ruleId: string,
): { event_id: string; ts: string; severity: 'warn' | 'critical' } | null {
  if (!Array.isArray(events)) {
    return null;
  }
  for (const item of events as ApiEvent[]) {
    const rule = object(item.rule);
    const ofRule = rule !== null && String(rule.id) === ruleId;
    const severity = String(item.severity).toLowerCase();
    if (
      ofRule &&
      item.state === 'FIRING' &&
      (typeof item.event_id === 'string' || typeof item.event_id === 'number') &&
      typeof item.ts === 'string' &&
      (severity === 'warn' || severity === 'critical')
    ) {
      return { event_id: String(item.event_id), ts: item.ts, severity };
    }
  }
  return null;
}

/** The rules of a GET /alerts/rules answer made from a default template. */
export function templateRulesOf(rules: unknown): SeedAlert['template_rules'] {
  if (!Array.isArray(rules)) {
    return [];
  }
  const out: { rule_id: string; template_key: string; name: string }[] = [];
  for (const rule of rules as ApiRule[]) {
    const id = rule.rule_id ?? rule.id;
    if (
      typeof rule.template_key === 'string' &&
      rule.template_key !== '' &&
      (typeof id === 'string' || typeof id === 'number')
    ) {
      out.push({
        rule_id: String(id),
        template_key: rule.template_key,
        name: typeof rule.name === 'string' ? rule.name : '',
      });
    }
  }
  return out;
}

/** Creates the seed rule, waits for its FIRING event and reads the template rules (file comment). */
export async function seedAlert(options: AlertSeedOptions): Promise<SeedAlert> {
  const now = options.now ?? Date.now;
  const sleep =
    options.sleep ?? ((ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)));
  const pollMs = options.pollMs ?? 2000;
  const { api, appId, env, log } = options;

  const created = await api.request('POST', '/alerts/rules', {
    body: seedRuleBody(appId, env),
    expect: [200, 201],
  });
  const ruleId = createdRuleId(created.body);
  log(`alert seed: rule ${ruleId} "${SEED_RULE_NAME}" on app ${String(appId)} (${env})`);

  const begin = now();
  const firingDeadline = begin + (options.firingTimeoutMs ?? 90_000);
  let event: ReturnType<typeof firingEventOf>;
  for (;;) {
    const at = now();
    const answer = await api.query('/alerts/events', {
      from: new Date(at - 60 * 60_000).toISOString(),
      to: new Date(at + 5_000).toISOString(),
      app: appId,
      state: 'FIRING',
      rule: ruleId,
    });
    event = firingEventOf(answer.data, ruleId);
    if (event !== null) {
      break;
    }
    if (now() >= firingDeadline) {
      throw new Error(
        `alert seed: no FIRING event of rule ${ruleId} in GET /alerts/events within ${String(
          (options.firingTimeoutMs ?? 90_000) / 1000,
        )} s (the engine evaluates every 10 s; does the primary app have HTTP data in the last hour?)`,
      );
    }
    await sleep(pollMs);
  }
  log(
    `alert seed: event ${event.event_id} FIRING (${event.severity}) after ${String(
      Math.round((now() - begin) / 1000),
    )} s`,
  );

  const templatesDeadline = now() + (options.templatesTimeoutMs ?? 90_000);
  let templates: SeedAlert['template_rules'];
  for (;;) {
    const answer = await api.query('/alerts/rules', { app: appId });
    templates = templateRulesOf(answer.data);
    if (templates.length > 0 || now() >= templatesDeadline) {
      break;
    }
    await sleep(pollMs);
  }
  log(
    `alert seed: ${String(templates.length)} template rule(s) on app ${String(appId)}: ${templates
      .map((r) => r.template_key)
      .join(', ')}`,
  );

  return {
    app_id: appId,
    env,
    rule_id: ruleId,
    rule_name: SEED_RULE_NAME,
    event_id: event.event_id,
    severity: event.severity,
    fired_at: event.ts,
    template_rules: templates,
  };
}
