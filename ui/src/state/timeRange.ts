// SPDX-License-Identifier: Apache-2.0
//
// The global time range (docs/15 §5, DSN-15-02, 05 §2.0-1..3). One reducer implements every
// rule of the docs/15 §5 table: presets, absolute input, previous/next, zoom in/out, "now", chart
// drag, XLog drag and the live/resume rules. It is pure: the caller passes the clock, the browser
// time zone and the remembered live preference, so the rules are testable with a fake clock and
// a fixed zone (T-UI-002). useTimeRange (state/useTimeRange.ts) connects it to the URL.
import {
  durationMs,
  durationSpec,
  PRESETS,
  RANGE_MAX_MS,
  RANGE_MIN_MS,
  type RelativeRange,
  type Selection,
  type TimeRange,
} from './urlState';

const MINUTE = 60_000;
const DAY = 24 * 60 * MINUTE;
/** Zoom limits (docs/15 §5): zoom in to 1 minute, zoom out to 30 days. */
export const ZOOM_MIN_MS = MINUTE;
export const ZOOM_MAX_MS = 30 * DAY;
/** Query keys resolve relative ranges at a clock floored to 10 s (docs/15 §4.2). */
export const QUERY_CLOCK_STEP_MS = 10_000;
/** Range that "resume live" returns to (docs/15 §5). */
export const RESUME_RANGE: RelativeRange = '15m';
/** Length used for a relative spec that is not a length (never happens for valid state). */
const DEFAULT_RELATIVE_MS = 15 * MINUTE;

export interface ResolvedRange {
  /** Inclusive start, epoch ms. */
  readonly from: number;
  /** Exclusive end, epoch ms (docs/14 §2.3 half-open). */
  readonly to: number;
}

// ---------------------------------------------------------------------------------------------
// Time zones
// ---------------------------------------------------------------------------------------------

/** The browser's IANA time zone (docs/15 §12: display in the browser zone). */
export function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}

const partsFormatters = new Map<string, Intl.DateTimeFormat>();

function partsFormatter(timeZone: string): Intl.DateTimeFormat {
  let formatter = partsFormatters.get(timeZone);
  if (formatter === undefined) {
    formatter = new Intl.DateTimeFormat('en-US', {
      timeZone,
      hourCycle: 'h23',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    });
    partsFormatters.set(timeZone, formatter);
  }
  return formatter;
}

interface WallClock {
  readonly year: number;
  readonly month: number;
  readonly day: number;
  readonly hour: number;
  readonly minute: number;
  readonly second: number;
}

/** The wall clock of an instant in a time zone. */
export function wallClock(epochMs: number, timeZone: string): WallClock {
  const values: Record<string, number> = {};
  for (const part of partsFormatter(timeZone).formatToParts(new Date(epochMs))) {
    if (part.type !== 'literal') {
      values[part.type] = Number(part.value);
    }
  }
  return {
    year: values.year ?? 1970,
    month: values.month ?? 1,
    day: values.day ?? 1,
    hour: values.hour ?? 0,
    minute: values.minute ?? 0,
    second: values.second ?? 0,
  };
}

/** Offset of a time zone at an instant in ms (local wall clock minus UTC), e.g. +9h for Seoul. */
export function zoneOffsetMs(epochMs: number, timeZone: string): number {
  const wall = wallClock(epochMs, timeZone);
  const asUtc = Date.UTC(wall.year, wall.month - 1, wall.day, wall.hour, wall.minute, wall.second);
  return asUtc - Math.floor(epochMs / 1000) * 1000;
}

/**
 * The first instant of the local day that contains `epochMs`. Usually local midnight; in zones
 * where a DST change skips midnight it is the first existing instant of that day.
 */
export function startOfLocalDay(epochMs: number, timeZone: string): number {
  const wall = wallClock(epochMs, timeZone);
  const midnightAsUtc = Date.UTC(wall.year, wall.month - 1, wall.day);
  // The offset at local midnight can differ from the one at `epochMs` (DST change that day):
  // try the offsets around midnight and keep the earliest candidate on the same local date.
  const offsets = new Set([
    zoneOffsetMs(midnightAsUtc - 12 * 3_600_000, timeZone),
    zoneOffsetMs(midnightAsUtc + 12 * 3_600_000, timeZone),
    zoneOffsetMs(epochMs, timeZone),
  ]);
  let best: number | null = null;
  for (const offset of offsets) {
    const candidate = midnightAsUtc - offset;
    const local = wallClock(candidate, timeZone);
    if (local.year === wall.year && local.month === wall.month && local.day === wall.day) {
      if (best === null || candidate < best) {
        best = candidate;
      }
    }
  }
  if (best !== null) {
    return best;
  }
  // Midnight does not exist (the clock jumped over it): walk forward to the first instant of
  // the day in minutes. At most the length of a DST gap (normally 60 steps).
  let probe = midnightAsUtc - zoneOffsetMs(midnightAsUtc, timeZone);
  for (let i = 0; i < 24 * 60; i += 1) {
    const local = wallClock(probe, timeZone);
    if (local.year === wall.year && local.month === wall.month && local.day === wall.day) {
      return probe;
    }
    probe += MINUTE;
  }
  return epochMs;
}

// ---------------------------------------------------------------------------------------------
// Resolution
// ---------------------------------------------------------------------------------------------

/** Length of a relative range spec that is not today/yesterday. */
export function relativeLengthMs(range: string): number {
  return durationMs(range) ?? DEFAULT_RELATIVE_MS;
}

/**
 * Absolute bounds of a range at `now` in `timeZone` (docs/15 §5): relative -> [now - d, now],
 * today -> [local start of day, now], yesterday -> [start of yesterday, start of today].
 */
export function resolveRange(range: TimeRange, now: number, timeZone: string): ResolvedRange {
  if (range.kind === 'absolute') {
    return { from: range.from, to: range.to };
  }
  if (range.range === 'today') {
    return { from: startOfLocalDay(now, timeZone), to: now };
  }
  if (range.range === 'yesterday') {
    const today = startOfLocalDay(now, timeZone);
    return { from: startOfLocalDay(today - 1, timeZone), to: today };
  }
  return { from: now - relativeLengthMs(range.range), to: now };
}

/** The clock that query keys use for relative ranges: floored to 10 s (docs/15 §4.2). */
export function queryClock(now: number): number {
  return Math.floor(now / QUERY_CLOCK_STEP_MS) * QUERY_CLOCK_STEP_MS;
}

/** Bounds for query keys and API calls: absolute as is, relative at the 10-second clock. */
export function resolveForQuery(range: TimeRange, now: number, timeZone: string): ResolvedRange {
  return resolveRange(range, range.kind === 'absolute' ? now : queryClock(now), timeZone);
}

/** True for ranges that end at the current time (live is possible). */
export function endsNow(range: TimeRange): boolean {
  return range.kind === 'relative' && range.range !== 'yesterday';
}

/**
 * The relative range with the same length (docs/15 §5 "now"): the preset of that length if
 * there is one, otherwise the length itself (`2h`), within 1 minute .. 366 days.
 */
export function relativeOfLength(lengthMs: number): TimeRange {
  const clamped = Math.min(Math.max(lengthMs, RANGE_MIN_MS), RANGE_MAX_MS);
  const rounded = Math.max(MINUTE, Math.round(clamped / MINUTE) * MINUTE);
  const preset = PRESETS.find((p) => durationMs(p) === rounded);
  return { kind: 'relative', range: preset ?? durationSpec(rounded) };
}

// ---------------------------------------------------------------------------------------------
// Reducer
// ---------------------------------------------------------------------------------------------

/** The time part of the URL state plus the transient "resume live" offer. */
export interface TimeState {
  readonly range: TimeRange;
  readonly live: boolean;
  /** XLog selection (docs/14 §6 `sel`), part of the state because XLog drag sets it. */
  readonly sel?: Selection | undefined;
  /** True after a past selection turned live off: the picker shows "resume live" (§5). */
  readonly resumeAvailable: boolean;
}

export type TimeAction =
  | { readonly type: 'preset'; readonly range: RelativeRange }
  | { readonly type: 'absolute'; readonly from: number; readonly to: number }
  | { readonly type: 'prev' }
  | { readonly type: 'next' }
  | { readonly type: 'zoomIn' }
  | { readonly type: 'zoomOut' }
  | { readonly type: 'now' }
  | { readonly type: 'setLive'; readonly live: boolean }
  | { readonly type: 'toggleLive' }
  | { readonly type: 'chartDrag'; readonly from: number; readonly to: number }
  | {
      readonly type: 'xlogDrag';
      readonly sel: Selection;
      /**
       * The window the chart showed while live, when it differs from the URL range (XLog: the
       * 5-minute live window, docs/15 §7.5): frozen as the absolute range instead of the URL's.
       */
      readonly frozen?: ResolvedRange;
    }
  | { readonly type: 'clearSelection' }
  | { readonly type: 'resume' };

export interface TimeContext {
  /** Current time, epoch ms. */
  readonly now: number;
  /** Browser time zone (IANA). */
  readonly timeZone: string;
  /** The live state the user last chose (localStorage 'argos.ui.live'), for "now" (§5). */
  readonly livePreference: boolean;
  /**
   * The current screen supports live mode (screen registry `live`, the same flag the top bar's
   * toggle uses). Elsewhere "now" does not restore live and live cannot be turned on; a live
   * state carried over from another screen (§5 "앱·화면 전환: live 유지") can still be turned off.
   */
  readonly liveAvailable: boolean;
}

/** How the URL must be written for an action (docs/15 §4.2: time and live replace). */
export function historyModeOf(action: TimeAction): 'replace' | 'push' {
  return action.type === 'xlogDrag' || action.type === 'clearSelection' ? 'push' : 'replace';
}

function absolute(from: number, to: number): TimeRange {
  return { kind: 'absolute', from, to };
}

/**
 * Applies one action (docs/15 §5). Every result keeps the invariant that live is on only for a
 * range that ends now. Invalid input (from >= to) returns the state unchanged.
 */
export function reduceTimeRange(state: TimeState, action: TimeAction, ctx: TimeContext): TimeState {
  const current = resolveRange(state.range, ctx.now, ctx.timeZone);
  const length = current.to - current.from;
  switch (action.type) {
    case 'preset': {
      const range: TimeRange = { kind: 'relative', range: action.range };
      // Live stays as it is (§5), except for a range that does not end now.
      return {
        ...state,
        range,
        live: state.live && endsNow(range),
        sel: undefined,
        resumeAvailable: false,
      };
    }
    case 'absolute':
      if (!(action.from < action.to)) {
        return state;
      }
      return {
        ...state,
        range: absolute(action.from, action.to),
        live: false,
        sel: undefined,
        resumeAvailable: false,
      };
    case 'prev':
    case 'next': {
      const shift = action.type === 'prev' ? -length : length;
      return {
        ...state,
        range: absolute(current.from + shift, current.to + shift),
        live: false,
        sel: undefined,
        resumeAvailable: false,
      };
    }
    case 'zoomIn':
    case 'zoomOut': {
      const target =
        action.type === 'zoomIn'
          ? Math.max(ZOOM_MIN_MS, Math.round(length / 2))
          : Math.min(ZOOM_MAX_MS, length * 2);
      if (target === length) {
        return state;
      }
      const centre = current.from + length / 2;
      let from = Math.round(centre - target / 2);
      let to = from + target;
      // Zooming out never reaches into the future: keep the length and end at now.
      if (to > ctx.now && action.type === 'zoomOut') {
        to = Math.max(ctx.now, current.to);
        from = to - target;
      }
      return {
        ...state,
        range: absolute(from, to),
        live: false,
        sel: undefined,
        resumeAvailable: false,
      };
    }
    case 'now': {
      // Live comes back only on a screen with live mode (§5 "실시간 가능 화면이면 live=1 복원").
      const range = relativeOfLength(length);
      const live = ctx.liveAvailable && ctx.livePreference;
      return { ...state, range, live, sel: undefined, resumeAvailable: false };
    }
    case 'setLive':
    case 'toggleLive': {
      const live = action.type === 'setLive' ? action.live : !state.live;
      if (!live) {
        return { ...state, live: false, resumeAvailable: false };
      }
      // Turning live on needs a screen with live mode; turning it off is always possible.
      if (!ctx.liveAvailable) {
        return state;
      }
      const range = endsNow(state.range) ? state.range : relativeOfLength(length);
      return { ...state, range, live: true, sel: undefined, resumeAvailable: false };
    }
    case 'chartDrag':
      if (!(action.from < action.to)) {
        return state;
      }
      // A drag over the past while live turns live off and offers "resume live" (§5).
      return {
        ...state,
        range: absolute(action.from, action.to),
        live: false,
        sel: undefined,
        resumeAvailable: state.live,
      };
    case 'xlogDrag':
      if (!state.live) {
        return { ...state, sel: action.sel };
      }
      // While live, freeze the window that was on screen so the selection stays inside it: the
      // chart's own window when the caller gives one (frozen), else the URL range resolved now.
      if (action.frozen !== undefined && action.frozen.from < action.frozen.to) {
        return {
          ...state,
          range: absolute(action.frozen.from, action.frozen.to),
          live: false,
          sel: action.sel,
          resumeAvailable: true,
        };
      }
      return {
        ...state,
        range: absolute(current.from, current.to),
        live: false,
        sel: action.sel,
        resumeAvailable: true,
      };
    case 'clearSelection':
      return { ...state, sel: undefined };
    case 'resume':
      return {
        range: { kind: 'relative', range: RESUME_RANGE },
        live: true,
        sel: undefined,
        resumeAvailable: false,
      };
  }
}
