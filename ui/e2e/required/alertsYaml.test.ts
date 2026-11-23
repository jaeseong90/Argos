// SPDX-License-Identifier: Apache-2.0
//
// required/alerts.yaml is valid and covers the docs/05 §2.4 and §2.9 checklists (docs/04 WO-38
// "05 §2.4·§2.9 전부") in order, owned by the running WO-38, with checks that exist in
// required/alertsChecks.ts and route variables of manifestVariables(); its test ids start at 30 so
// they never collide with the T-E2E-alerts-<n> titles of the specs; deep link 4 of common.yaml is
// WO-38's alert marker check. Also the pure helpers of alertsChecks.ts (the value text oracle per
// rule unit, the history range, URLs).
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import { formatPercent } from '../../src/utils/format';
import { t } from '../fixtures/i18n';
import type { ManifestAlert, SeedManifest } from '../stack/manifest';

import {
  ALERT_COLUMNS,
  ALERTS_CHECKS,
  alertNumberText,
  alertsPath,
  alertsWindow,
  alertValueText,
  camelKey,
  ceilMinute,
  errorDetails,
  floorMinute,
  isoSeconds,
  linkKey,
  pathAndQuery,
  RULE_FIELDS,
  squashText,
} from './alertsChecks';
import { CHECKS } from './checks';
import {
  fillTemplate,
  parseRequiredFile,
  RUNNING_OWNERS,
  templateVariables,
  testTitle,
} from './schema';
import { manifestVariables } from './variables';
import { XLOG_CHECKS } from './xlogChecks';

const DIR = dirname(fileURLToPath(import.meta.url));
const FILE = join(DIR, 'alerts.yaml');
const SPECS = join(DIR, '..', 'specs');

/** As much of the seed manifest as manifestVariables() reads. */
const SEED = {
  generator: { range: { start: '2030-01-01T00:00:00Z', end: '2030-01-01T00:43:00Z' } },
  apps: [{ app_id: 3, name: 'order-api', env: 'prod', instances: [{ instance_id: 12 }] }],
  primary_app_id: 3,
  secondary_app_id: 4,
  traffic: { app_id: 11 },
  windows: [],
  tx: {},
} as unknown as SeedManifest;

function checksOf(item: { assert: readonly object[] }): string[] {
  return item.assert.flatMap((a) => ('check' in a ? [(a as { check: string }).check] : []));
}

describe('required/alerts.yaml (docs/05 §2.4, §2.9)', () => {
  const spec = parseRequiredFile(readFileSync(FILE, 'utf8'), FILE);

  it('lists 2.4-1 .. 2.4-5 and 2.9-1 .. 2.9-6 in checklist order, owned by the running WO-38', () => {
    expect(spec.screen).toBe('alerts');
    expect(spec.source).toBe('docs/05 §2.4, §2.9');
    expect(spec.items.map((item) => item.id)).toEqual([
      '2.4-1',
      '2.4-2',
      '2.4-3',
      '2.4-4',
      '2.4-5',
      '2.9-1',
      '2.9-2',
      '2.9-3',
      '2.9-4',
      '2.9-5',
      '2.9-6',
    ]);
    expect(RUNNING_OWNERS.has('WO-38')).toBe(true);
    for (const item of spec.items) {
      expect(item.owner, item.id).toBe('WO-38');
      expect(item.req, item.id).toContain('F-U09');
      expect(item.route, item.id).toBeDefined();
    }
  });

  it('numbers its tests from 30, apart from every T-E2E-alerts-<n> title of the specs', () => {
    expect(spec.first_number).toBe(30);
    const titles = spec.items.map((_, index) => testTitle(spec, index));
    expect(titles[0]).toBe(`T-E2E-alerts-30 2.4-1 ${spec.items[0]?.title ?? ''}`);
    expect(titles.at(-1)).toMatch(/^T-E2E-alerts-40 2\.9-6 /);
    const yamlIds = new Set(titles.map((title) => title.split(' ')[0]));
    const specIds = new Set<string>();
    for (const file of readdirSync(SPECS).filter((f) => f.endsWith('.ts'))) {
      // Test titles only: a string that starts with the id (comments may name the yaml's range).
      for (const match of readFileSync(join(SPECS, file), 'utf8').matchAll(
        /['`](T-E2E-alerts-\d+) /g,
      )) {
        specIds.add(match[1] ?? '');
      }
    }
    expect(specIds.size, 'the specs name alert tests').toBeGreaterThan(3);
    expect(
      [...specIds].filter((id) => yamlIds.has(id)),
      'shared ids',
    ).toEqual([]);
    // Files without first_number keep 1.
    const stats = parseRequiredFile(
      readFileSync(join(DIR, 'stats.yaml'), 'utf8'),
      join(DIR, 'stats.yaml'),
    );
    expect(testTitle(stats, 0)).toMatch(/^T-E2E-stats-1 /);
  });

  it('runs a registered check of alertsChecks.ts per item', () => {
    const expected: Record<string, string> = {
      '2.4-1': 'alertsRuleEditorFields',
      '2.4-2': 'alertsRulePreview',
      '2.4-3': 'alertsTemplateDiff',
      '2.4-4': 'alertsRuleCloneDisableHistory',
      '2.4-5': 'alertsChannelsValidation',
      '2.9-1': 'alertsPinnedPanel',
      '2.9-2': 'alertsEventColumns',
      '2.9-3': 'alertsFilters',
      '2.9-4': 'alertsRowActions',
      '2.9-5': 'alertsStats',
      '2.9-6': 'alertsAllAppsFromOverview',
    };
    for (const item of spec.items) {
      expect(checksOf(item), item.id).toEqual([expected[item.id]]);
      const check = expected[item.id] ?? '';
      expect(Object.keys(ALERTS_CHECKS)).toContain(check);
      expect(CHECKS[check], `${check} is registered in checks.ts`).toBe(ALERTS_CHECKS[check]);
    }
    expect(Object.keys(ALERTS_CHECKS).sort()).toEqual(Object.values(expected).sort());
  });

  it('opens the rule and channel screens with the role that may edit them', () => {
    const role = Object.fromEntries(spec.items.map((item) => [item.id, item.role]));
    for (const id of ['2.4-1', '2.4-2', '2.4-3', '2.4-4', '2.9-4']) {
      expect(role[id], id).toBe('operator');
    }
    // Channels: only an administrator edits them (operator read-only, specs T-E2E-alerts-7).
    expect(role['2.4-5']).toBe('admin');
    for (const id of ['2.9-1', '2.9-2', '2.9-3', '2.9-5', '2.9-6']) {
      expect(role[id], id).toBe('viewer');
    }
  });

  it('fills every route from the manifest', () => {
    const vars = manifestVariables(SEED);
    for (const item of spec.items) {
      const route = item.route ?? '';
      for (const name of templateVariables(route)) {
        expect(vars, `${item.id} variable ${name}`).toHaveProperty([name]);
      }
      expect(fillTemplate(route, vars)).not.toContain('{');
    }
    expect(spec.items.map((item) => item.route?.split('?')[0])).toEqual([
      '/alerts/rules/new',
      '/alerts/rules/new',
      '/alerts/rules',
      '/alerts/rules',
      '/alerts/channels',
      '/alerts',
      '/alerts',
      '/alerts',
      '/alerts',
      '/alerts',
      '/overview',
    ]);
  });
});

describe('common.yaml deep link 4 (ADR-0010 C-226 (8))', () => {
  const common = parseRequiredFile(
    readFileSync(join(DIR, 'common.yaml'), 'utf8'),
    join(DIR, 'common.yaml'),
  );

  it('is WO-38 alert marker check on the XLog screen', () => {
    const item = common.items.find((i) => i.title.startsWith('deep link 4'));
    expect(item?.owner).toBe('WO-38');
    expect(item?.coverage).toContain('/alerts/events');
    expect(checksOf(item ?? { assert: [] })).toEqual(['xlogAlertMarker']);
    expect(CHECKS.xlogAlertMarker).toBe(XLOG_CHECKS.xlogAlertMarker);
    // No longer the plain event marker check of 2.1-10 (it runs it, then the alert part).
    expect(XLOG_CHECKS.xlogAlertMarker).not.toBe(XLOG_CHECKS.xlogEventMarkers);
  });
});

describe('alert value text oracle (rule.unit, ADR-0010 C-227 (4))', () => {
  it('formats each unit as the screens show it in English', () => {
    expect(alertNumberText(0.0523, 'ratio')).toBe(formatPercent(0.0523, 'en'));
    expect(alertNumberText(0.0523, 'ratio')).toMatch(/^5\.2\d?%$/);
    expect(alertNumberText(1250, 'ms')).toMatch(/^1\.25\s?s$/);
    expect(alertNumberText(90, 'seconds')).toMatch(/1\s?m/);
    expect(alertNumberText(12.5, 'per_second')).toMatch(/12\.5/);
    expect(alertNumberText(0.875, 'score')).toBe('0.88');
    expect(alertNumberText(1234, 'count')).toMatch(/1,234|1\.2\s?K/i);
    expect(alertNumberText(3, null)).toBe('3');
  });

  it('shows value / threshold, the dash for a missing part, UP or DOWN for state', () => {
    expect(squashText(alertValueText(0.06, 0.05, 'ratio'))).toBe(
      `${formatPercent(0.06, 'en')} / ${formatPercent(0.05, 'en')}`,
    );
    expect(squashText(alertValueText(0.06, 0.05, 'ratio'))).toMatch(/^6(\.0+)?% \/ 5(\.0+)?%$/);
    expect(alertValueText(null, 0.05, 'ratio')).toBe(`— / ${alertNumberText(0.05, 'ratio')}`);
    expect(alertValueText(0, null, 'count')).toBe(`${alertNumberText(0, 'count')} / —`);
    expect(alertValueText(1, 1, 'state')).toBe(t('common:component.alertValue.down'));
    expect(alertValueText(0, 1, 'state')).toBe(t('common:component.alertValue.up'));
    expect(alertValueText(null, null, 'state')).toBe('—');
  });

  it('keys the reason and destination codes as the alerts locale does', () => {
    expect(camelKey('target_gone')).toBe('targetGone');
    expect(camelKey('link_local')).toBe('linkLocal');
    expect(camelKey('private')).toBe('private');
    for (const reason of [
      'condition',
      'cleared',
      'escalated',
      'acked',
      'resolved',
      'target_gone',
      'rule_disabled',
      'rule_deleted',
      'no_data',
      'data_returned',
    ]) {
      expect(t(`alerts:common.reason.${camelKey(reason)}`), reason).not.toBe('');
    }
    expect(t(`alerts:channels.blocked.${camelKey('private')}`)).not.toBe('');
    expect(t(`alerts:channels.blocked.${camelKey('link_local')}`)).not.toBe('');
  });
});

describe('alertsChecks helpers', () => {
  const alert = {
    app_id: 3,
    env: 'prod',
    rule_id: 'r',
    rule_name: 'E2E seed error rate',
    event_id: 'e',
    severity: 'warn',
    fired_at: '2030-01-01T10:20:30Z',
    template_rules: [],
  } satisfies ManifestAlert;

  it('history range: whole minutes, ends at the current minute, holds the seed alert', () => {
    const now = Date.parse('2030-01-01T10:45:12.345Z');
    expect(alertsWindow(alert, now)).toEqual({
      from: '2030-01-01T08:45:00Z',
      to: '2030-01-01T10:45:00Z',
    });
    // An alert fired long before: the range starts an hour before it.
    expect(alertsWindow(alert, Date.parse('2030-01-01T15:00:00Z')).from).toBe(
      '2030-01-01T09:20:00Z',
    );
    expect(floorMinute(Date.parse('2030-01-01T10:20:30Z'))).toBe(
      Date.parse('2030-01-01T10:20:00Z'),
    );
    expect(ceilMinute(Date.parse('2030-01-01T10:20:30Z'))).toBe(Date.parse('2030-01-01T10:21:00Z'));
    expect(ceilMinute(Date.parse('2030-01-01T10:20:00Z'))).toBe(Date.parse('2030-01-01T10:20:00Z'));
    expect(isoSeconds(Date.parse('2030-01-01T10:20:00Z'))).toBe('2030-01-01T10:20:00Z');
  });

  it('builds history URLs and compares links by path and query', () => {
    expect(alertsPath()).toBe('/alerts');
    expect(alertsPath({ state: 'firing', app: 3, rule: undefined })).toBe(
      '/alerts?state=firing&app=3',
    );
    expect(pathAndQuery('http://127.0.0.1:1/apps/3/dashboard?from=a&alert=e')).toBe(
      '/apps/3/dashboard?from=a&alert=e',
    );
    expect(pathAndQuery('/alerts?x=1')).toBe('/alerts?x=1');
    expect(linkKey('http://h:1/apps/3/dashboard?to=2030-01-01T10%3A15%3A00Z&from=a&alert=e')).toBe(
      linkKey('/apps/3/dashboard?alert=e&from=a&to=2030-01-01T10:15:00Z'),
    );
    expect(linkKey('/apps/3/dashboard?alert=e')).not.toBe(linkKey('/apps/3/xlog?alert=e'));
  });

  it('reads the details of an API error body', () => {
    expect(
      errorDetails({
        error: {
          code: 'webhook_destination_blocked',
          details: [{ field: 'target', reason: 'private' }],
        },
      }),
    ).toEqual([{ field: 'target', reason: 'private' }]);
    expect(errorDetails({ error: { code: 'x' } })).toEqual([]);
    expect(errorDetails(null)).toEqual([]);
  });

  it('names the 10 history columns and every rule editor field of the contract', () => {
    expect(ALERT_COLUMNS).toHaveLength(10);
    expect(RULE_FIELDS).toHaveLength(26);
    expect(new Set(RULE_FIELDS).size).toBe(RULE_FIELDS.length);
  });
});
