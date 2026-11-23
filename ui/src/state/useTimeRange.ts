// SPDX-License-Identifier: Apache-2.0
//
// The global time range as a hook (docs/15 §5, 05 §2.0-1..3): the reducer of timeRange.ts on top
// of the URL state. Every screen, chart, the XLog and the picker change the same state.
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useMatches } from 'react-router';

import { readLivePreference, writeLivePreference } from './preferences';
import {
  browserTimeZone,
  historyModeOf,
  QUERY_CLOCK_STEP_MS,
  reduceTimeRange,
  resolveForQuery,
  resolveRange,
  type ResolvedRange,
  type TimeAction,
  type TimeState,
} from './timeRange';
import { useUiStore } from './uiStore';
import type { Selection, TimeRange, UrlSchema, UrlStateOf } from './urlState';
import { useUrlState } from './useUrlState';

/** The REST gap to fill after "resume live" (docs/15 §5, 05 §2.0-2). */
export interface ResumeGap {
  /** End of the last range on screen (epoch ms). */
  readonly from: number;
  /** Time of the resume (epoch ms). */
  readonly to: number;
}

type ResumeListener = (gap: ResumeGap) => void;
const resumeListeners = new Set<ResumeListener>();

/**
 * Registers the resync callback a live screen runs after "resume live": it queries the gap
 * through REST before live data continues (docs/15 §5, §10). Returns the unsubscribe function.
 */
export function onResumeLive(listener: ResumeListener): () => void {
  resumeListeners.add(listener);
  return () => {
    resumeListeners.delete(listener);
  };
}

/** Hook form of onResumeLive for screens. */
export function useResumeResync(listener: ResumeListener): void {
  useEffect(() => onResumeLive(listener), [listener]);
}

function emitResume(gap: ResumeGap): void {
  for (const listener of [...resumeListeners]) {
    listener(gap);
  }
}

/** The part of a screen route's handle that says whether the screen has live mode. */
interface LiveScreenHandle {
  readonly screen?: { readonly live?: boolean };
}

/**
 * True when the deepest matched screen route supports live mode: `handle.screen.live` of the
 * screen routes (routes/routeTree.tsx, routes/screens.ts), the same source as the top bar's live
 * toggle (useCurrentScreen()?.live === true). Read structurally so that src/state does not depend
 * on src/routes. False outside screen routes.
 */
export function useRouteLiveAvailable(): boolean {
  const matches = useMatches();
  for (let i = matches.length - 1; i >= 0; i -= 1) {
    const handle: unknown = matches[i]?.handle;
    if (typeof handle === 'object' && handle !== null && 'screen' in handle) {
      const screen = (handle as LiveScreenHandle).screen;
      if (screen !== undefined) {
        return screen.live === true;
      }
    }
  }
  return false;
}

export interface TimeRangeApi {
  readonly range: TimeRange;
  readonly live: boolean;
  /** The current screen supports live mode (docs/15 §5; "now" and live-on depend on it). */
  readonly liveAvailable: boolean;
  readonly sel: Selection | undefined;
  /** Bounds at the current clock (display). */
  readonly resolved: ResolvedRange;
  /** Bounds for query keys and API calls (relative ranges at a 10-second clock). */
  readonly queryRange: ResolvedRange;
  readonly resumeAvailable: boolean;
  readonly timeZone: string;
  readonly dispatch: (action: TimeAction) => void;
}

interface TimeFields {
  time: TimeRange;
  live: boolean;
  sel?: Selection | undefined;
}

/** Current time, re-read every `stepMs` (relative ranges move with the clock, docs/15 §4.2). */
export function useClock(stepMs: number = QUERY_CLOCK_STEP_MS): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => {
      setNow(Date.now());
    }, stepMs);
    return () => {
      clearInterval(timer);
    };
  }, [stepMs]);
  return now;
}

/** The global time range of the current route (docs/15 §5). */
export function useTimeRange(schema?: UrlSchema): TimeRangeApi {
  const { state, set, fresh } = useUrlState(schema);
  const fields = state as unknown as UrlStateOf<UrlSchema> & TimeFields;
  const resumeAvailable = useUiStore((s) => s.resumeAvailable);
  const setResumeAvailable = useUiStore((s) => s.setResumeAvailable);
  const timeZone = useMemo(() => browserTimeZone(), []);
  const range = fields.time;
  const live = fields.live;
  const sel = fields.sel;
  const now = useClock();
  // Live mode is a property of the screen; entering a screen without it keeps a carried live=1
  // (docs/15 §5 "앱·화면 전환: live 유지"), which the user can still turn off there.
  const liveAvailable = useRouteLiveAvailable();

  const dispatch = useCallback(
    (action: TimeAction) => {
      const ctx = {
        now: Date.now(),
        timeZone,
        livePreference: readLivePreference(),
        liveAvailable,
      };
      // The newest URL, not this render's: a key pressed right after a write whose render is still
      // pending (React Router renders navigations in a transition) acts on the written range
      // (05 §2.0-3: "[" then "+" zooms the range "[" wrote).
      const latest = fresh() as unknown as TimeFields;
      const current: TimeState = {
        range: latest.time,
        live: latest.live,
        sel: latest.sel,
        resumeAvailable,
      };
      const next = reduceTimeRange(current, action, ctx);
      // Remember the user's choice; a refused "live on" (no live mode here) changes nothing.
      if ((action.type === 'setLive' || action.type === 'toggleLive') && next !== current) {
        writeLivePreference(next.live);
      }
      const partial: Partial<TimeFields> = { time: next.range, live: next.live };
      if ('sel' in latest) {
        partial.sel = next.sel;
      }
      const written = set(partial, {
        replace: historyModeOf(action) === 'replace',
      });
      if (written) {
        setResumeAvailable(next.resumeAvailable);
        if (action.type === 'resume') {
          const before = resolveRange(current.range, ctx.now, timeZone);
          emitResume({ from: Math.min(before.to, ctx.now), to: ctx.now });
        }
      }
    },
    [fresh, liveAvailable, resumeAvailable, set, setResumeAvailable, timeZone],
  );

  return {
    range,
    live,
    liveAvailable,
    sel,
    resolved: resolveRange(range, now, timeZone),
    queryRange: resolveForQuery(range, now, timeZone),
    // "Resume live" turns live on, so it is offered only on a screen with live mode.
    resumeAvailable: resumeAvailable && !live && liveAvailable,
    timeZone,
    dispatch,
  };
}
