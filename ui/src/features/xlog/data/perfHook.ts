// SPDX-License-Identifier: Apache-2.0
//
// window.__xlogPerf (src/xlog/perf.ts, version 1) on the XLog screen, for the performance spec's
// route target (ui/perf, T-NFR-14, ADR-0003 C-35/C-36). Installed only when the browser has the
// preference `argos.ui.xlogPerf` = '1' (the spec sets it before opening the page): then every
// drawn frame is also a `performance.measure('xlog:frame')` (the trace's frame delimiter,
// perf/lib/frames.ts) and the selection and load costs are kept. Without it the screen records
// nothing. It exposes timings and counts only, never data.
import { useCallback, useEffect, useRef, useState, type RefObject } from 'react';

import type { FrameRecorder, FrameSample, SelectionTiming } from '../../../xlog/perf';
import type { XLogBuffer } from '../../../xlog/XLogBuffer';
import type { XLogCanvasHandle, XLogSelection } from '../../../xlog/XLogCanvas';
import type { SummaryTiming } from '../summary/summaryModel';

import type { XLogLoadTiming } from './loadTiming';

/** The hit index facts of a reported selection (EngineSelection, src/xlog/engine.ts). */
export type SelectionIndexInfo = Pick<XLogSelection, 'indexRebuilt' | 'indexBuildMs'>;

/** The XLog screen records performance only with this browser preference. */
export function readXLogPerfFlag(): boolean {
  try {
    return localStorage.getItem('argos.ui.xlogPerf') === '1';
  } catch {
    return false;
  }
}

/** Frame measures kept in the performance timeline before it is cleared (bounded memory). */
const MAX_MEASURES = 1000;

export interface XLogPerf {
  readonly enabled: boolean;
  /**
   * Canvas onFrame: marks the first full frame of a load (data-ready of the page); with the
   * preference it also records the frame and its `xlog:frame` measure.
   */
  readonly onFrame: (sample: FrameSample) => void;
  /**
   * The canvas reported a selection (pointer up or keyboard): its hit index query time and, from
   * `index`, whether that time includes a rebuild of the hit index and the rebuild's share (WO-33
   * review r1-new-3).
   */
  readonly selectionStarted: (queryMs: number | null, index?: SelectionIndexInfo) => void;
  /**
   * The summary counted the selection (after React committed it): selectMs finding the selected
   * points (the screen's selection computation, ADR-0003 C-36), computeMs selection + summary.
   */
  readonly selectionComputed: (timing: SummaryTiming) => void;
  /**
   * A load finished: its timing. Its `firstFrameAt` is the end of the first full point-layer frame
   * drawn with the new range and dictionary (the first full frame from `convertedAt` on: the points
   * enter the buffer in the commit that gives the canvas those props, useXLogPoints).
   */
  readonly loaded: (load: XLogLoadTiming | null) => void;
  /** True once the loaded points are drawn (data-ready of the page). */
  readonly ready: boolean;
}

export function useXLogPerf(
  buffer: XLogBuffer,
  canvasRef: RefObject<XLogCanvasHandle | null>,
): XLogPerf {
  const [enabled] = useState(readXLogPerfFlag);
  /** The frame recorder, once src/xlog/perf.ts is loaded (only with the preference). */
  const recorder = useRef<FrameRecorder | null>(null);
  const [ready, setReady] = useState(false);
  const bufferRef = useRef(buffer);
  const measures = useRef(0);
  const pendingSelection = useRef<{
    start: number;
    queryMs: number | null;
    indexRebuilt: boolean | null;
    indexBuildMs: number | null;
  } | null>(null);
  const lastSelection = useRef<SelectionTiming | null>(null);
  const lastLoad = useRef<XLogLoadTiming | null>(null);
  /** The load whose first full frame is still to come. */
  const awaitingFrame = useRef<XLogLoadTiming | null>(null);
  /** End of the last full point-layer frame. */
  const lastFullFrameEnd = useRef<number | null>(null);

  useEffect(() => {
    bufferRef.current = buffer;
  }, [buffer]);

  useEffect(() => {
    if (!enabled) {
      return undefined;
    }
    // The recorder, its statistics and window.__xlogPerf (src/xlog/perf.ts) load in their own chunk
    // only with the preference (docs/15 §14 initial path, WO-33 review r1-new-2); the readiness
    // above does not need them. Frames drawn before the chunk arrives are not recorded (the
    // performance spec resets the samples before every measured run).
    let cancelled = false;
    let uninstall: (() => void) | null = null;
    import('../../../xlog/perf')
      .then(({ FrameRecorder: Recorder, frameStats, installPerfHook }) => {
        if (cancelled) {
          return;
        }
        const frames = new Recorder();
        recorder.current = frames;
        uninstall = installPerfHook(window, {
          version: 1,
          get ready() {
            return lastLoad.current !== null && lastLoad.current.firstFrameAt !== null;
          },
          pointCount: () => bufferRef.current.size,
          visibleCount: () => canvasRef.current?.visibleCount() ?? 0,
          samples: () => frames.samples(),
          stats: () => frames.stats(),
          reset: () => {
            frames.reset();
          },
          benchmark: async (options) => {
            const handle = canvasRef.current;
            if (handle === null) {
              throw new Error('The XLog canvas is not mounted');
            }
            const samples = await handle.benchmark(options);
            return { samples, stats: frameStats(samples) };
          },
          lastSelection: () => lastSelection.current,
          lastLoad: () => lastLoad.current,
        });
      })
      .catch(() => {
        // Diagnostics only: without the chunk the screen records nothing (as without the preference).
      });
    return () => {
      cancelled = true;
      uninstall?.();
      recorder.current = null;
    };
  }, [enabled, canvasRef]);

  const onFrame = useCallback(
    (sample: FrameSample) => {
      if (enabled) {
        recorder.current?.push(sample);
        performance.measure('xlog:frame', { start: sample.t, duration: sample.totalMs });
        measures.current += 1;
        if (measures.current >= MAX_MEASURES) {
          performance.clearMeasures('xlog:frame');
          measures.current = 0;
        }
      }
      if (!sample.full) {
        return;
      }
      const end = sample.t + sample.totalMs;
      lastFullFrameEnd.current = end;
      const load = awaitingFrame.current;
      if (load !== null && sample.t >= load.convertedAt) {
        awaitingFrame.current = null;
        lastLoad.current = { ...load, firstFrameAt: end };
        setReady(true);
      }
    },
    [enabled],
  );

  const selectionStarted = useCallback(
    (queryMs: number | null, index?: SelectionIndexInfo) => {
      if (!enabled) {
        return;
      }
      const now = performance.now();
      const start = now - (queryMs ?? 0);
      pendingSelection.current = {
        start,
        queryMs,
        indexRebuilt: index?.indexRebuilt ?? null,
        indexBuildMs: index?.indexBuildMs ?? null,
      };
      performance.mark('xlog:selection:start', { startTime: start });
    },
    [enabled],
  );

  const selectionComputed = useCallback(
    (timing: SummaryTiming) => {
      const pending = pendingSelection.current;
      if (!enabled || pending === null) {
        return;
      }
      pendingSelection.current = null;
      const now = performance.now();
      performance.mark('xlog:selection:commit', { startTime: now });
      lastSelection.current = {
        points: timing.points,
        queryMs: pending.queryMs,
        selectMs: timing.selectMs,
        indexRebuilt: pending.indexRebuilt,
        indexBuildMs: pending.indexBuildMs,
        computeMs: timing.computeMs,
        commitMs: now - pending.start,
      };
    },
    [enabled],
  );

  const loaded = useCallback((load: XLogLoadTiming | null) => {
    if (load === null) {
      return;
    }
    // The points were copied into the buffer at `convertedAt`, in the layout effect of the commit
    // that gave the canvas the new range and dictionary, and this runs in the synchronous commit
    // right after it (the one that carries the timing): any full frame from `convertedAt` on drew
    // the new range. In practice none has yet (frames run in animation frames, after both commits),
    // so the next full frame completes the load; the check keeps the definition exact if one did.
    const drawn = lastFullFrameEnd.current;
    if (drawn !== null && drawn >= load.convertedAt) {
      awaitingFrame.current = null;
      lastLoad.current = { ...load, firstFrameAt: drawn };
      setReady(true);
      return;
    }
    awaitingFrame.current = load;
    lastLoad.current = load;
    setReady(false);
  }, []);

  return {
    enabled,
    onFrame,
    selectionStarted,
    selectionComputed,
    loaded,
    ready,
  };
}
