// SPDX-License-Identifier: Apache-2.0
//
// Model of the active transactions screen (05 §2.6, docs/15 §9.6, WO-35): elapsed bands with
// default and custom thresholds (server buckets, recount from items, approximate when the agent
// truncated its snapshot), threshold validation and storage, the top summary (null max without
// instances), the client-side list filters (T-UI-004), bar-click instance toggling, snapshot
// replacement by server time and "as of N s ago" independent of the browser clock.
// Requirements (test/README.md §5): { type: 'req', description: 'F-U04' }
import { afterEach, describe, expect, it, vi } from 'vitest';

import { untrusted } from '../../api/untrusted';
import type { Filter } from '../../state/urlState';

import {
  asOfSeconds,
  bandOf,
  DEFAULT_THRESHOLDS,
  filterItems,
  instanceBands,
  newerSnapshot,
  receiveSnapshot,
  selectedState,
  silenceSeconds,
  summarize,
  thresholdProblem,
  toggleInstanceFilter,
  withStateFilter,
} from './model';
import { activeInstance, activeItem, activeSnapshot, scenario } from './testing/fixtures';
import { readThresholds, writeThresholds } from './thresholdPreference';

export const REQ = { type: 'req', description: 'F-U04' };

const f = (field: string, value: string, op: Filter['op'] = 'eq'): Filter => ({
  field,
  op,
  value: untrusted(value),
});

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
});

describe('active model: bands (05 §2.6-1)', () => {
  it('bandOf splits at t1, t2, t3 (the lower bound belongs to the higher band)', () => {
    expect([0, 999, 1000, 2999, 3000, 7999, 8000, 60_000].map((e) => bandOf(e, [1, 3, 8]))).toEqual(
      [0, 0, 1, 1, 2, 2, 3, 3],
    );
    expect(bandOf(1500, [2, 5, 10])).toBe(0);
  });

  it('default thresholds use the server buckets as they are', () => {
    const data = scenario();
    const instance = data.per_instance[0];
    if (instance === undefined) throw new Error('fixture');
    const result = instanceBands(instance, data.items, DEFAULT_THRESHOLDS);
    expect(result.bands).toEqual(instance.buckets);
    expect(result.bands).toEqual([1, 1, 1, 1]);
    expect(result.count).toBe(4);
    expect(result.approximate).toBe(false);
  });

  it('custom thresholds recount from the items of the instance (all kinds)', () => {
    const data = scenario();
    const instance = data.per_instance[0];
    if (instance === undefined) throw new Error('fixture');
    // 12.5 s, 5 s, 2 s, 0.3 s with 2/5/10 s → [0.3, 2] < 2? 2 s is band 1 (lower bound), 5 s band 2.
    const result = instanceBands(instance, data.items, [2, 5, 10]);
    expect(result.bands).toEqual([1, 1, 1, 1]);
    const coarse = instanceBands(instance, data.items, [10, 20, 30]);
    expect(coarse.bands).toEqual([3, 1, 0, 0]);
    expect(coarse.approximate).toBe(false);
  });

  it('the first band holds the transactions the agent did not send; exact while its shortest sent is under t1', () => {
    // 600 running, the agent sent its 500 longest, the shortest of them 0.5 s.
    const items = Array.from({ length: 500 }, (_, i) => activeItem(1, String(i + 1), 500 + i * 30));
    const base = activeSnapshot([activeInstance(1, 'a')], items).per_instance[0];
    if (base === undefined) throw new Error('fixture');
    const instance = { ...base, count: 600, truncated: true };
    const byDefault = instanceBands(instance, items, DEFAULT_THRESHOLDS);
    expect(byDefault.bands.reduce((a, b) => a + b, 0)).toBe(600);
    expect(byDefault.bands[0]).toBe(
      600 - (instance.buckets[1] ?? 0) - (instance.buckets[2] ?? 0) - (instance.buckets[3] ?? 0),
    );
    expect(byDefault.approximate).toBe(false);
    const custom = instanceBands(instance, items, [0.6, 3, 8]);
    expect(custom.approximate).toBe(false);
    expect(custom.bands.reduce((a, b) => a + b, 0)).toBe(600);
  });

  it('approximate when truncated and the shortest sent one is at or above t1 (default and custom)', () => {
    const items = Array.from({ length: 500 }, (_, i) => activeItem(1, String(i + 1), 2_000 + i));
    const base = activeSnapshot([activeInstance(1, 'a')], items).per_instance[0];
    if (base === undefined) throw new Error('fixture');
    const instance = { ...base, count: 700, truncated: true };
    expect(instanceBands(instance, items, DEFAULT_THRESHOLDS).approximate).toBe(true);
    expect(instanceBands(instance, items, [1.5, 3, 8]).approximate).toBe(true);
    // With t1 above the shortest sent, the omitted ones (shorter) are all in the first band.
    expect(instanceBands(instance, items, [2.5, 3, 8]).approximate).toBe(false);
  });

  it('approximate for custom thresholds when the list holds fewer items of the instance than the agent sent', () => {
    const items = [activeItem(1, '1', 9_000), activeItem(1, '2', 4_000)];
    const base = activeSnapshot([activeInstance(1, 'a')], items).per_instance[0];
    if (base === undefined) throw new Error('fixture');
    // The server's 2,000-item cap left out the 4 s one: it counts in the first band, estimated.
    const result = instanceBands(base, items.slice(0, 1), [2, 5, 10]);
    expect(result.approximate).toBe(true);
    expect(result.bands).toEqual([1, 0, 1, 0]);
  });
});

describe('active model: thresholds (05 §2.6-1 "설정 가능")', () => {
  it('validates 0 < t1 < t2 < t3 <= 3,600 s', () => {
    expect(thresholdProblem([1, 3, 8])).toBeNull();
    expect(thresholdProblem([0.5, 1, 3600])).toBeNull();
    expect(thresholdProblem([0, 3, 8])).toBe('notPositive');
    expect(thresholdProblem([3, 3, 8])).toBe('notAscending');
    expect(thresholdProblem([5, 3, 8])).toBe('notAscending');
    expect(thresholdProblem([1, 3, 3601])).toBe('tooLarge');
    expect(thresholdProblem([1, Number.NaN, 8])).toBe('notNumber');
    expect(thresholdProblem([1, 3])).toBe('notNumber');
  });

  it('remembers custom thresholds per browser and falls back to the default on bad or failing storage', () => {
    expect(readThresholds()).toEqual([1, 3, 8]);
    writeThresholds([2, 5, 10]);
    expect(localStorage.getItem('argos.ui.activeThresholds')).toBe('[2,5,10]');
    expect(readThresholds()).toEqual([2, 5, 10]);
    writeThresholds([1, 3, 8]);
    expect(localStorage.getItem('argos.ui.activeThresholds')).toBeNull();
    localStorage.setItem('argos.ui.activeThresholds', '[5,3,1]');
    expect(readThresholds()).toEqual([1, 3, 8]);
    localStorage.setItem('argos.ui.activeThresholds', 'not json');
    expect(readThresholds()).toEqual([1, 3, 8]);
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('denied');
    });
    expect(readThresholds()).toEqual([1, 3, 8]);
    expect(() => {
      writeThresholds([2, 4, 6]);
    }).not.toThrow();
  });
});

describe('active model: summary (05 §2.6-5, T-UI-007)', () => {
  it('total = Σ count, slow = top band, max = busiest instance', () => {
    const data = scenario();
    const bands = data.per_instance.map((i) => instanceBands(i, data.items, DEFAULT_THRESHOLDS));
    const summary = summarize(bands);
    expect(summary.total).toBe(6);
    expect(summary.slow).toBe(2);
    expect(summary.max).toEqual({ instanceId: 1, count: 4 });
    expect(summary.slowApproximate).toBe(false);
  });

  it('T-UI-007 max is null without instances, never 0', () => {
    expect(summarize([])).toEqual({ total: 0, slow: 0, slowApproximate: false, max: null });
  });
});

describe('active model: list filters (T-UI-004, docs/14 §6)', () => {
  const items = scenario().items;
  const ids = (list: readonly { tx_id: string }[]) => list.map((i) => i.tx_id);

  it('the global kind filters the list (ALL keeps every kind)', () => {
    expect(ids(filterItems(items, [], 'ALL').items)).toEqual([
      '101',
      '201',
      '102',
      '103',
      '202',
      '104',
    ]);
    expect(ids(filterItems(items, [], 'BATCH').items)).toEqual(['104']);
    expect(ids(filterItems(items, [], 'HTTP').items)).not.toContain('104');
  });

  it('instance, service key and state; several eq values mean any, ne excludes; unknown fields are reported', () => {
    expect(ids(filterItems(items, [f('instance', '2')], 'ALL').items)).toEqual(['201', '202']);
    expect(ids(filterItems(items, [f('service', '502')], 'ALL').items)).toEqual(['201', '102']);
    expect(ids(filterItems(items, [f('state', 'sql'), f('state', 'ASYNC')], 'ALL').items)).toEqual([
      '101',
      '201',
    ]);
    expect(ids(filterItems(items, [f('instance', '1', 'ne')], 'ALL').items)).toEqual([
      '201',
      '202',
    ]);
    const result = filterItems(items, [f('status', '500'), f('elapsed_ms', '100', 'gte')], 'ALL');
    expect(result.items).toHaveLength(items.length);
    expect(result.ignored.map((x) => x.field)).toEqual(['status', 'elapsed_ms']);
  });

  it('bar click toggles f.instance and keeps the other filters; one state filter at a time', () => {
    const make = (value: string) => f('instance', value);
    const once = toggleInstanceFilter([f('state', 'SQL')], 2, make);
    expect(once.map((x) => `${x.field}=${x.value}`)).toEqual(['state=SQL', 'instance=2']);
    const twice = toggleInstanceFilter(once, 2, make);
    expect(twice.map((x) => `${x.field}=${x.value}`)).toEqual(['state=SQL']);
    const states = withStateFilter([f('state', 'SQL'), f('instance', '1')], 'DBC', (v) =>
      f('state', v),
    );
    expect(states.map((x) => `${x.field}=${x.value}`)).toEqual(['instance=1', 'state=DBC']);
    expect(selectedState(states)).toBe('DBC');
    expect(selectedState(withStateFilter(states, null, (v) => f('state', v)))).toBeNull();
  });
});

describe('active model: snapshot age and replacement (docs/15 §9.6, §10)', () => {
  const data = activeSnapshot([activeInstance(1, 'a')], [], '2026-11-23T10:00:00.000Z');

  it('"as of" uses the server clock at receipt plus browser time since, whatever the browser clock says', () => {
    // The browser clock is a year off: only differences of browser times enter.
    const receivedAt = Date.parse('2027-06-01T00:00:00.000Z');
    const snap = receiveSnapshot(data, '2026-11-23T10:00:03.000Z', receivedAt, 'ws');
    if (snap === null) throw new Error('invalid');
    expect(snap.offsetMs).toBe(3000);
    expect(asOfSeconds(snap, receivedAt)).toBe(3);
    expect(asOfSeconds(snap, receivedAt + 4_200)).toBe(7);
    expect(silenceSeconds(snap, receivedAt + 11_000)).toBe(11);
    // A browser clock that goes back never gives a negative age.
    expect(asOfSeconds(snap, receivedAt - 60_000)).toBe(3);
  });

  it('a snapshot without snapshot_at is as of the answer; an invalid server time is refused', () => {
    const none = activeSnapshot([], [], null);
    expect(receiveSnapshot(none, '2026-11-23T10:00:03.000Z', 5, 'rest')?.offsetMs).toBe(0);
    expect(receiveSnapshot(none, 'yesterday', 5, 'rest')).toBeNull();
  });

  it('the newer answer by server time replaces the snapshot as a whole; an older one is ignored', () => {
    const older = receiveSnapshot(data, '2026-11-23T10:00:05.000Z', 1, 'ws');
    const other = activeSnapshot([activeInstance(2, 'b')], [], '2026-11-23T10:00:05.000Z');
    const newer = receiveSnapshot(other, '2026-11-23T10:00:10.000Z', 2, 'rest');
    if (older === null || newer === null) throw new Error('invalid');
    expect(newerSnapshot(older, newer)).toBe(newer);
    expect(newerSnapshot(newer, older)).toBe(newer);
    expect(newerSnapshot(null, older)).toBe(older);
  });
});
