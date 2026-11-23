// SPDX-License-Identifier: Apache-2.0
//
// Pure helpers of the alert history (05 §2.9): pinned rows and counts, deep link targets, the
// mute target of a row and the 30-day mute limit, the ACK note limits (512 characters and 1 KB).
import { describe, expect, it } from 'vitest';

import type { AlertEventItem } from '../../../api/alerts';
import { untrusted } from '../../../api/untrusted';

import {
  alertTextFits,
  deepLinkTarget,
  muteRequest,
  muteTargetOf,
  pinnedRows,
  severityCounts,
} from './model';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U10' };

function item(id: string, overrides: Partial<AlertEventItem> = {}): AlertEventItem {
  return {
    event_id: id,
    ts: '2026-11-23T00:00:00.000Z',
    state: 'FIRING',
    severity: 'warn',
    app: { id: 1, name: untrusted('order-api') },
    env: untrusted('prod'),
    target: { type: 'app', id: '1', name: untrusted('order-api') },
    rule: { id: 'r-1', name: untrusted('Error rate'), metric: 'error_rate', unit: 'ratio' },
    value: 0.2,
    threshold: 0.05,
    duration_s: 60,
    acked_by: null,
    acked_at: null,
    note: null,
    deeplink: '/apps/1/dashboard',
    ...overrides,
  };
}

describe('pinned panel rows (05 §2.9-1)', () => {
  it('orders FIRING before ACKED and critical before warn, keeping the server order otherwise', () => {
    const rows = pinnedRows(
      [
        item('a', { state: 'ACKED', severity: 'critical' }),
        item('b', { severity: 'warn' }),
        item('c', { severity: 'critical' }),
        item('d', { severity: 'warn' }),
      ],
      null,
    );
    expect(rows.map((row) => row.event_id)).toEqual(['c', 'b', 'd', 'a']);
  });

  it('keeps one application when it is selected and counts by severity', () => {
    const rows = pinnedRows(
      [
        item('a', { app: { id: 2, name: untrusted('billing') } }),
        item('b', { severity: 'critical' }),
        item('c', { app: null }),
      ],
      1,
    );
    expect(rows.map((row) => row.event_id)).toEqual(['b']);
    expect(severityCounts([item('x'), item('y', { severity: 'critical' }), item('z')])).toEqual({
      critical: 1,
      warn: 2,
    });
  });
});

describe('deep link target (docs/14 §6)', () => {
  const origin = 'https://apm.example';

  it('opens a same-origin link in the tab and keeps another origin external', () => {
    expect(deepLinkTarget('/apps/1/dashboard?alert=e1', origin)).toEqual({
      internal: true,
      href: '/apps/1/dashboard?alert=e1',
    });
    expect(deepLinkTarget('https://apm.example/apps/1/xlog?from=a&alert=e1', origin)).toEqual({
      internal: true,
      href: '/apps/1/xlog?from=a&alert=e1',
    });
    expect(deepLinkTarget('https://other.example/apps/1', origin)).toEqual({
      internal: false,
      href: 'https://other.example/apps/1',
    });
  });

  it('refuses a script URL and an empty link (the action is disabled)', () => {
    const scheme = ['java', 'script'].join('');
    expect(deepLinkTarget(`${scheme}:alert(1)`, origin)).toBeNull();
    expect(deepLinkTarget('', origin)).toBeNull();
    expect(deepLinkTarget(null, origin)).toBeNull();
  });
});

describe('mute this target (05 §2.9-4, docs/14 §4.13)', () => {
  it('takes the application, environment, instance and rule of the row', () => {
    expect(
      muteTargetOf(item('e', { target: { type: 'instance', id: '42', name: untrusted('web-1') } })),
    ).toEqual({ app: 1, env: 'prod', instance: 42, rule: 'r-1' });
    expect(muteTargetOf(item('e', { app: null, env: null }))).toEqual({ rule: 'r-1' });
  });

  it('builds a request from now and refuses more than 30 days', () => {
    const now = Date.parse('2026-11-23T00:00:00.000Z');
    expect(muteRequest({ app: 1 }, 720, '  maintenance ', now)).toEqual({
      from: '2026-11-23T00:00:00.000Z',
      to: '2026-12-23T00:00:00.000Z',
      target: { app: 1 },
      reason: 'maintenance',
    });
    expect(muteRequest({ app: 1 }, 721, '', now)).toBeNull();
    expect(muteRequest({ app: 1 }, 0, '', now)).toBeNull();
    expect(muteRequest({ app: 1 }, 4, ' ', now)).not.toHaveProperty('reason');
  });
});

describe('ACK note and mute reason limits (ADR-0010 C-226 (7))', () => {
  it('accepts 512 characters within 1 KB and refuses more of either', () => {
    expect(alertTextFits('a'.repeat(512))).toBe(true);
    expect(alertTextFits('a'.repeat(513))).toBe(false);
    // 400 Hangul syllables are 1,200 UTF-8 bytes: under 512 characters but over 1 KB.
    expect(alertTextFits('가'.repeat(341))).toBe(true);
    expect(alertTextFits('가'.repeat(400))).toBe(false);
  });
});
