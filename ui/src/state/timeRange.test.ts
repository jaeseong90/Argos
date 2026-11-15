// SPDX-License-Identifier: Apache-2.0
//
// The global time range (docs/15 §5, DSN-15-02, 05 §2.0-1..3). The clock and the time zone are
// parameters, so every rule runs with a fixed zone and a fixed "now". DST dates are computed for
// the current year instead of being written down (no calendar constants in tests).
// Requirements (test/README.md §5): { type: 'req', description: '05 §2.0' }, { type: 'req', description: 'DSN-15-02' }
import { describe, expect, it } from 'vitest';

import {
  endsNow,
  historyModeOf,
  queryClock,
  reduceTimeRange,
  relativeOfLength,
  resolveForQuery,
  resolveRange,
  startOfLocalDay,
  wallClock,
  zoneOffsetMs,
  ZOOM_MAX_MS,
  type TimeAction,
  type TimeContext,
  type TimeState,
} from './timeRange';

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
const UTC = 'UTC';
const SEOUL = 'Asia/Seoul';
const BERLIN = 'Europe/Berlin';

/** A fixed "now": 12:34:56.789 UTC on 15 June of the current year. */
const NOW = Date.UTC(new Date().getUTCFullYear(), 5, 15, 12, 34, 56, 789);

function ctx(overrides: Partial<TimeContext> = {}): TimeContext {
  return { now: NOW, timeZone: UTC, livePreference: false, liveAvailable: true, ...overrides };
}

function state(overrides: Partial<TimeState> = {}): TimeState {
  return {
    range: { kind: 'relative', range: '15m' },
    live: false,
    resumeAvailable: false,
    ...overrides,
  };
}

function apply(s: TimeState, ...actions: TimeAction[]): TimeState {
  return actions.reduce((acc, action) => reduceTimeRange(acc, action, ctx()), s);
}

/** Noon UTC of every day of the current year. */
function daysOfYear(): number[] {
  const year = new Date(NOW).getUTCFullYear();
  const days: number[] = [];
  for (let t = Date.UTC(year, 0, 1, 12); new Date(t).getUTCFullYear() === year; t += DAY) {
    days.push(t);
  }
  return days;
}

/** The first noon of the year whose zone offset differs from the day before, in a direction. */
function dstChangeDay(zone: string, direction: 'forward' | 'back'): number | null {
  const days = daysOfYear();
  for (let i = 1; i < days.length; i += 1) {
    const before = zoneOffsetMs(days[i - 1] ?? 0, zone);
    const after = zoneOffsetMs(days[i] ?? 0, zone);
    if ((direction === 'forward' && after > before) || (direction === 'back' && after < before)) {
      return days[i] ?? null;
    }
  }
  return null;
}

describe('T-UI-002 time range: resolution (docs/15 §5)', () => {
  it('T-UI-002 resolves relative ranges to [now - d, now]', () => {
    expect(resolveRange({ kind: 'relative', range: '15m' }, NOW, UTC)).toEqual({
      from: NOW - 15 * MINUTE,
      to: NOW,
    });
    expect(resolveRange({ kind: 'relative', range: '7d' }, NOW, UTC)).toEqual({
      from: NOW - 7 * DAY,
      to: NOW,
    });
    expect(resolveRange({ kind: 'relative', range: '90m' }, NOW, UTC)).toEqual({
      from: NOW - 90 * MINUTE,
      to: NOW,
    });
  });

  it('T-UI-002 resolves today and yesterday at midnight of the browser zone', () => {
    const today = resolveRange({ kind: 'relative', range: 'today' }, NOW, SEOUL);
    // 12:34 UTC is 21:34 in Seoul (UTC+9, no DST): today starts at 15:00 UTC.
    expect(today).toEqual({
      from: Date.UTC(new Date(NOW).getUTCFullYear(), 5, 15, 15) - DAY,
      to: NOW,
    });
    const yesterday = resolveRange({ kind: 'relative', range: 'yesterday' }, NOW, SEOUL);
    expect(yesterday).toEqual({ from: today.from - DAY, to: today.from });
  });

  it('T-UI-002 handles the midnight edge: one millisecond before and at local midnight', () => {
    const midnight = startOfLocalDay(NOW, SEOUL);
    const before = resolveRange({ kind: 'relative', range: 'today' }, midnight - 1, SEOUL);
    expect(before.from).toBe(midnight - DAY);
    const at = resolveRange({ kind: 'relative', range: 'today' }, midnight, SEOUL);
    expect(at).toEqual({ from: midnight, to: midnight });
    const yesterdayAt = resolveRange({ kind: 'relative', range: 'yesterday' }, midnight, SEOUL);
    expect(yesterdayAt).toEqual({ from: midnight - DAY, to: midnight });
  });

  it('T-UI-002 gives yesterday 23 h after the spring DST change and 25 h after the autumn one', () => {
    const spring = dstChangeDay(BERLIN, 'forward');
    const autumn = dstChangeDay(BERLIN, 'back');
    expect(spring).not.toBeNull();
    expect(autumn).not.toBeNull();
    // "Yesterday" seen from the day after the change is the change day itself.
    const afterSpring = resolveRange(
      { kind: 'relative', range: 'yesterday' },
      (spring ?? 0) + DAY,
      BERLIN,
    );
    expect(afterSpring.to - afterSpring.from).toBe(23 * HOUR);
    const afterAutumn = resolveRange(
      { kind: 'relative', range: 'yesterday' },
      (autumn ?? 0) + DAY,
      BERLIN,
    );
    expect(afterAutumn.to - afterAutumn.from).toBe(25 * HOUR);
    // Today on the change day starts at local 00:00 (before the 02:00/03:00 change).
    const todaySpring = resolveRange({ kind: 'relative', range: 'today' }, spring ?? 0, BERLIN);
    const wall = wallClock(todaySpring.from, BERLIN);
    expect([wall.hour, wall.minute, wall.second]).toEqual([0, 0, 0]);
  });

  it.each([BERLIN, 'America/Santiago', 'Asia/Beirut', 'Australia/Lord_Howe', SEOUL])(
    'T-UI-002 finds the first instant of every local day of the year (%s)',
    (zone) => {
      for (const noon of daysOfYear()) {
        const start = startOfLocalDay(noon, zone);
        const wall = wallClock(noon, zone);
        const first = wallClock(start, zone);
        expect([first.year, first.month, first.day]).toEqual([wall.year, wall.month, wall.day]);
        const previous = wallClock(start - 1000, zone);
        expect([previous.year, previous.month, previous.day]).not.toEqual([
          wall.year,
          wall.month,
          wall.day,
        ]);
      }
    },
  );

  it('T-UI-002 floors the query clock of relative ranges to 10 s; absolute ranges stay', () => {
    expect(queryClock(NOW)).toBe(NOW - 6_789);
    const relative = resolveForQuery({ kind: 'relative', range: '1h' }, NOW, UTC);
    expect(relative).toEqual({ from: NOW - 6_789 - HOUR, to: NOW - 6_789 });
    expect(resolveForQuery({ kind: 'relative', range: '1h' }, NOW + 3_000, UTC)).toEqual(relative);
    expect(resolveForQuery({ kind: 'absolute', from: 1, to: 2 }, NOW, UTC)).toEqual({
      from: 1,
      to: 2,
    });
  });

  it('T-UI-002 maps a length to the relative range of that length', () => {
    expect(relativeOfLength(HOUR)).toEqual({ kind: 'relative', range: '1h' });
    expect(relativeOfLength(30 * DAY)).toEqual({ kind: 'relative', range: '30d' });
    expect(relativeOfLength(7.5 * MINUTE)).toEqual({ kind: 'relative', range: '8m' });
    expect(relativeOfLength(2 * HOUR)).toEqual({ kind: 'relative', range: '2h' });
    expect(relativeOfLength(1)).toEqual({ kind: 'relative', range: '1m' });
  });
});

describe('T-UI-002 time range reducer (docs/15 §5 table)', () => {
  it('T-UI-002 preset: relative, live kept, selection cleared', () => {
    const live = state({ live: true, sel: { msMin: 0, msMax: 1, tsFrom: 1, tsTo: 2 } });
    const next = apply(live, { type: 'preset', range: '1h' });
    expect(next).toEqual({
      range: { kind: 'relative', range: '1h' },
      live: true,
      sel: undefined,
      resumeAvailable: false,
    });
    expect(apply(state(), { type: 'preset', range: '6h' }).live).toBe(false);
    // Yesterday does not end now: live goes off.
    expect(apply(live, { type: 'preset', range: 'yesterday' }).live).toBe(false);
  });

  it('T-UI-002 absolute: sets the range and turns live off; from >= to is ignored', () => {
    const next = apply(state({ live: true }), { type: 'absolute', from: 1_000, to: 2_000 });
    expect(next.range).toEqual({ kind: 'absolute', from: 1_000, to: 2_000 });
    expect(next.live).toBe(false);
    const unchanged = state();
    expect(apply(unchanged, { type: 'absolute', from: 2_000, to: 2_000 })).toBe(unchanged);
  });

  it('T-UI-002 previous/next shift by the length, become absolute and turn live off', () => {
    const prev = apply(state({ live: true }), { type: 'prev' });
    expect(prev.range).toEqual({
      kind: 'absolute',
      from: NOW - 30 * MINUTE,
      to: NOW - 15 * MINUTE,
    });
    expect(prev.live).toBe(false);
    const back = apply(prev, { type: 'next' });
    expect(back.range).toEqual({ kind: 'absolute', from: NOW - 15 * MINUTE, to: NOW });
  });

  it('T-UI-002 zoom in halves the length around the centre, down to 1 minute', () => {
    const abs = state({ range: { kind: 'absolute', from: 0 + NOW - HOUR, to: NOW } });
    const zoomed = apply(abs, { type: 'zoomIn' });
    expect(zoomed.range).toEqual({
      kind: 'absolute',
      from: NOW - 45 * MINUTE,
      to: NOW - 15 * MINUTE,
    });
    let s = abs;
    for (let i = 0; i < 12; i += 1) {
      s = apply(s, { type: 'zoomIn' });
    }
    expect(s.range.kind === 'absolute' && s.range.to - s.range.from).toBe(MINUTE);
    expect(apply(s, { type: 'zoomIn' })).toBe(s);
  });

  it('T-UI-002 zoom out doubles the length up to 30 days and never reaches past now', () => {
    const past = state({ range: { kind: 'absolute', from: NOW - 3 * HOUR, to: NOW - 2 * HOUR } });
    const out = apply(past, { type: 'zoomOut' });
    expect(out.range).toEqual({ kind: 'absolute', from: NOW - 3.5 * HOUR, to: NOW - 1.5 * HOUR });
    const recent = apply(state(), { type: 'zoomOut' });
    expect(recent.range).toEqual({ kind: 'absolute', from: NOW - 30 * MINUTE, to: NOW });
    let s = state({ range: { kind: 'relative', range: '7d' } });
    for (let i = 0; i < 5; i += 1) {
      s = apply(s, { type: 'zoomOut' });
    }
    expect(s.range.kind === 'absolute' && s.range.to - s.range.from).toBe(ZOOM_MAX_MS);
    expect(apply(s, { type: 'zoomOut' })).toBe(s);
  });

  it('T-UI-002 now: relative of the same length, live from the remembered preference', () => {
    const past = state({ range: { kind: 'absolute', from: NOW - 5 * HOUR, to: NOW - 4 * HOUR } });
    const off = reduceTimeRange(past, { type: 'now' }, ctx({ livePreference: false }));
    expect(off).toEqual({
      range: { kind: 'relative', range: '1h' },
      live: false,
      sel: undefined,
      resumeAvailable: false,
    });
    const on = reduceTimeRange(past, { type: 'now' }, ctx({ livePreference: true }));
    expect(on.live).toBe(true);
  });

  it('T-UI-002 now on a screen without live mode: relative of the same length, live stays off', () => {
    const past = state({ range: { kind: 'absolute', from: NOW - 5 * HOUR, to: NOW - 4 * HOUR } });
    const next = reduceTimeRange(
      past,
      { type: 'now' },
      ctx({ livePreference: true, liveAvailable: false }),
    );
    expect(next).toEqual({
      range: { kind: 'relative', range: '1h' },
      live: false,
      sel: undefined,
      resumeAvailable: false,
    });
  });

  it('T-UI-002 live on a screen without live mode: cannot be turned on, a carried live turns off', () => {
    const noLive = ctx({ liveAvailable: false });
    const off = state({ range: { kind: 'absolute', from: NOW - 2 * HOUR, to: NOW - HOUR } });
    expect(reduceTimeRange(off, { type: 'toggleLive' }, noLive)).toBe(off);
    expect(reduceTimeRange(off, { type: 'setLive', live: true }, noLive)).toBe(off);
    // live=1 carried over from a live screen (docs/15 §5 "앱·화면 전환: live 유지").
    const carried = state({ range: { kind: 'relative', range: '1h' }, live: true });
    expect(reduceTimeRange(carried, { type: 'toggleLive' }, noLive)).toEqual({
      range: { kind: 'relative', range: '1h' },
      live: false,
      resumeAvailable: false,
    });
    expect(reduceTimeRange(carried, { type: 'setLive', live: false }, noLive).live).toBe(false);
    // Turning it on again there is refused.
    const turnedOff = reduceTimeRange(carried, { type: 'toggleLive' }, noLive);
    expect(reduceTimeRange(turnedOff, { type: 'toggleLive' }, noLive)).toBe(turnedOff);
  });

  it('T-UI-002 live toggle: on makes an absolute range relative with its length, off keeps the range', () => {
    const abs = state({ range: { kind: 'absolute', from: NOW - 2 * HOUR, to: NOW - HOUR } });
    const on = apply(abs, { type: 'toggleLive' });
    expect(on).toMatchObject({ range: { kind: 'relative', range: '1h' }, live: true });
    const off = apply(on, { type: 'setLive', live: false });
    expect(off).toMatchObject({ range: { kind: 'relative', range: '1h' }, live: false });
  });

  it('T-UI-002 chart drag sets the absolute range; while live it offers resume', () => {
    const notLive = apply(state(), {
      type: 'chartDrag',
      from: NOW - 10 * MINUTE,
      to: NOW - 5 * MINUTE,
    });
    expect(notLive).toMatchObject({
      range: { kind: 'absolute', from: NOW - 10 * MINUTE, to: NOW - 5 * MINUTE },
      live: false,
      resumeAvailable: false,
    });
    const live = apply(state({ live: true }), {
      type: 'chartDrag',
      from: NOW - 10 * MINUTE,
      to: NOW - 5 * MINUTE,
    });
    expect(live).toMatchObject({ live: false, resumeAvailable: true });
    expect(historyModeOf({ type: 'chartDrag', from: 1, to: 2 })).toBe('replace');
  });

  it('T-UI-002 XLog drag sets only the selection; the range does not change', () => {
    const sel = { msMin: 100, msMax: 900, tsFrom: NOW - 10 * MINUTE, tsTo: NOW - 5 * MINUTE };
    const next = apply(state(), { type: 'xlogDrag', sel });
    expect(next).toEqual({
      range: { kind: 'relative', range: '15m' },
      live: false,
      sel,
      resumeAvailable: false,
    });
    expect(historyModeOf({ type: 'xlogDrag', sel })).toBe('push');
  });

  it('T-UI-002 XLog drag while live: live off, window frozen as absolute, resume offered (05 §2.0-2)', () => {
    const sel = { msMin: 100, msMax: 900, tsFrom: NOW - 10 * MINUTE, tsTo: NOW - 5 * MINUTE };
    const next = apply(state({ live: true }), { type: 'xlogDrag', sel });
    expect(next).toEqual({
      range: { kind: 'absolute', from: NOW - 15 * MINUTE, to: NOW },
      live: false,
      sel,
      resumeAvailable: true,
    });
  });

  it('T-UI-002 resume: relative 15m, live on, selection and offer cleared', () => {
    const paused = state({
      range: { kind: 'absolute', from: 1, to: 2 },
      resumeAvailable: true,
      sel: { msMin: 0, msMax: 1, tsFrom: 1, tsTo: 2 },
    });
    expect(apply(paused, { type: 'resume' })).toEqual({
      range: { kind: 'relative', range: '15m' },
      live: true,
      sel: undefined,
      resumeAvailable: false,
    });
  });

  it('T-UI-002 every result keeps live only for ranges that end now', () => {
    const actions: TimeAction[] = [
      { type: 'preset', range: 'yesterday' },
      { type: 'prev' },
      { type: 'zoomIn' },
      { type: 'zoomOut' },
      { type: 'now' },
      { type: 'toggleLive' },
      { type: 'chartDrag', from: NOW - HOUR, to: NOW - MINUTE },
      { type: 'resume' },
    ];
    let s = state({ live: true });
    for (const action of actions) {
      s = reduceTimeRange(s, action, ctx({ livePreference: true }));
      expect(!s.live || endsNow(s.range)).toBe(true);
    }
  });

  it('T-UI-002 time actions replace the history entry, selection changes push it (docs/15 §4.2)', () => {
    for (const type of [
      'preset',
      'absolute',
      'prev',
      'next',
      'zoomIn',
      'zoomOut',
      'now',
      'resume',
      'toggleLive',
    ] as const) {
      expect(historyModeOf({ type } as TimeAction)).toBe('replace');
    }
    expect(historyModeOf({ type: 'clearSelection' })).toBe('push');
  });
});
