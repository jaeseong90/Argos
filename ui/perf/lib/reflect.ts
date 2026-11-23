// SPDX-License-Identifier: Apache-2.0
//
// "Screen reflect" of an XLog selection (ADR-0003 C-36, ADR-0010 C-204, docs/poc/xlog-ui.md 8
// item 5): from the moment the pointer is released until the frame in which the summary count and
// the first row of the transaction list are on screen. The React commit alone is not the measure:
// the style, layout and paint of the frame that follows it are part of what the user waits for.
//
// The start is the pointerup event's own time (event.timeStamp, the time base of performance.now()),
// so the input's queueing delay before the listener runs is part of the reflect. Three ends per
// selection:
//   page   an in-page probe: a capture pointerup listener takes the start, a MutationObserver sees
//          the summary count and the first list row in the DOM (the commit), and the end is the
//          first task after the next animation frame (a MessageChannel message posted from
//          requestAnimationFrame runs after that frame's rendering update: style, layout, paint)
//   trace  the same start and commit as user timing marks in a Chrome trace; the end is the end of
//          the renderer main thread's first Commit (or Paint) event after the commit mark
//   gpu    in the same trace (with the `viz` category, REFLECT_TRACE_CATEGORIES), the end of the
//          display compositor's first draw and swap after that main-thread frame
//          (Display::DrawAndSwap, else DirectRenderer::DrawFrame, on VizCompositorThread: raster,
//          GPU and viz drawing included); without them the end of the frame's PipelineReporter
// The judged value is the largest of the three (of those the trace has).
//
// A preview row from the chart's points (ADR-0010 C-204) counts as the list's first row only when
// it is the server's first row (review fe-perf-3, PM 결정 대기): the probe records the first preview
// row's key and the first server row's key and the frame after the server rows; when the keys
// differ, the selection's reflect is the server rows' (judgedSelectionReflect).
import type { TraceEvent } from './frames';

/** User timing marks the probe writes (also read back from the trace). */
export const REFLECT_START = 'xlog:reflect:start';
export const REFLECT_COMMIT = 'xlog:reflect:commit';
export const REFLECT_PAINTED = 'xlog:reflect:painted';
/** The list's server rows in the DOM (the commit of the server-row reflect). */
export const REFLECT_SERVER_COMMIT = 'xlog:reflect:server-commit';

/** ADR-0003 C-36: screen reflect after the drag p95 <= 100 ms per run. */
export const REFLECT_BUDGET_MS = 100;

/** What has to be on screen for a selection to count as reflected. */
export interface ReflectSelectors {
  /** The summary's point count of the new selection. */
  readonly summary: string;
  /** The first list row of the new selection (or the list's "no transaction" state). */
  readonly list: string;
  /**
   * The list's server rows, when the screen previews them first (serverListAt, and the reflect of a
   * selection whose preview's first row is not the server's); undefined when the first list row is
   * the server's. The first match's `data-row-key` is the server's first row (none: empty state).
   */
  readonly serverList?: string;
  /**
   * The list's preview rows (C-204): when the reflect counts one of them, the first match's
   * `data-row-key` is compared with the server's first row (ReflectProbe.previewKey).
   */
  readonly preview?: string;
}

/**
 * The WO-33 XLog screen: the summary and list components report their state (data-state). The
 * list's first row is the first row on screen: the preview from the chart's points while the server
 * search runs (`preview`, ADR-0010 C-204 "목록 점진 렌더") or the server's first row (`ready`).
 */
export const ROUTE_SELECTORS: ReflectSelectors = {
  summary: '[data-testid="xlog-summary"][data-state="ready"] [data-testid="xlog-summary-count"]',
  list: '[data-testid="xlog-list"][data-state="preview"] [data-testid="data-table-row"], [data-testid="xlog-list"][data-state="ready"] [data-testid="data-table-row"], [data-testid="xlog-list"][data-state="empty"]',
  // The server's rows (how long the search takes after a drag; they are the reflect of a selection
  // whose preview's first row is not the server's).
  serverList:
    '[data-testid="xlog-list"][data-state="ready"] [data-testid="data-table-row"], [data-testid="xlog-list"][data-state="empty"]',
  preview: '[data-testid="xlog-list"][data-state="preview"] [data-testid="data-table-row"]',
};

/** The WO-04 PoC page: summary and client-side list (not virtualised). */
export const POC_SELECTORS: ReflectSelectors = {
  summary: '[data-testid="xlog-summary-count"]',
  list: '[data-testid="xlog-list-row"], [data-testid="xlog-list-empty"]',
};

/** One probe result, performance.now() ms of the page. */
export interface ReflectProbe {
  readonly startAt: number;
  /** After the frame that followed the summary count's appearance. */
  readonly summaryPaintedAt: number;
  /** Summary count and first list row in the DOM. */
  readonly commitAt: number;
  /** After the frame that followed the commit. */
  readonly paintedAt: number;
  /** The list's server rows in the DOM (selectors.serverList); null without that selector. */
  readonly serverListAt: number | null;
  /** After the frame that followed the server rows' appearance; null without serverList. */
  readonly serverListPaintedAt: number | null;
  /**
   * `data-row-key` of the first preview row (selectors.preview) when the reflect counted a preview
   * row; null when it counted the server's rows or the screen has no preview selector.
   */
  readonly previewKey: string | null;
  /** `data-row-key` of the server's first row; null for the empty state or without serverList. */
  readonly serverKey: string | null;
}

declare global {
  interface Window {
    /** The armed probe of perf/lib/reflect.ts (one selection). */
    __xlogReflect?: Promise<ReflectProbe>;
  }
}

/**
 * Arms the probe in the page (pass to page.evaluate): the next pointerup starts it; it resolves
 * window.__xlogReflect when both selectors match and the following frame is rendered, or rejects
 * after `timeoutMs`. Self-contained: it runs in the browser.
 */
export function armReflectProbe(args: {
  readonly selectors: ReflectSelectors;
  readonly timeoutMs: number;
  readonly marks: readonly [string, string, string];
  /** User timing mark written when the server rows appear (REFLECT_SERVER_COMMIT). */
  readonly serverCommitMark?: string;
}): void {
  const [startMark, commitMark, paintedMark] = args.marks;
  const serverCommitMark = args.serverCommitMark;
  const previewRows = args.selectors.preview;
  window.__xlogReflect = new Promise<ReflectProbe>((resolve, reject) => {
    let start: number | null = null;
    let summaryPainted: number | null = null;
    let summarySeen = false;
    let done = false;
    const afterFrame = (callback: (at: number) => void): void => {
      requestAnimationFrame(() => {
        const channel = new MessageChannel();
        channel.port1.onmessage = () => {
          callback(performance.now());
        };
        channel.port2.postMessage(0);
      });
    };
    // The optional server-rows selector keeps the probe open until the server's rows are in the
    // DOM and painted as well (their first row is compared with the preview's first row).
    const serverList = args.selectors.serverList;
    let serverListAt: number | null = null;
    let serverListPaintedAt: number | null = null;
    let serverKey: string | null = null;
    let previewKey: string | null = null;
    let reflected: Omit<
      ReflectProbe,
      'serverListAt' | 'serverListPaintedAt' | 'previewKey' | 'serverKey'
    > | null = null;
    let reflecting = false;
    const finish = (): void => {
      done = true;
      observer.disconnect();
      clearTimeout(timer);
    };
    const settle = (): void => {
      if (
        !done &&
        reflected !== null &&
        (serverList === undefined || serverListPaintedAt !== null)
      ) {
        finish();
        resolve({ ...reflected, serverListAt, serverListPaintedAt, previewKey, serverKey });
      }
    };
    const check = (): void => {
      if (start === null || done) {
        return;
      }
      const serverFirst =
        serverList !== undefined && serverListAt === null
          ? document.querySelector(serverList)
          : null;
      if (serverFirst !== null) {
        serverListAt = performance.now();
        serverKey = serverFirst.getAttribute('data-row-key');
        if (serverCommitMark !== undefined) {
          performance.mark(serverCommitMark);
        }
        afterFrame((painted) => {
          serverListPaintedAt = painted;
          settle();
        });
      }
      const summary = document.querySelector(args.selectors.summary) !== null;
      const list = document.querySelector(args.selectors.list) !== null;
      if (summary && !summarySeen) {
        summarySeen = true;
        afterFrame((at) => {
          summaryPainted = at;
        });
      }
      if (summary && list && !reflecting) {
        reflecting = true;
        const started = start;
        const commit = performance.now();
        // The reflect counts a preview row when one is on screen now (the server rows replace it).
        previewKey =
          previewRows === undefined
            ? null
            : (document.querySelector(previewRows)?.getAttribute('data-row-key') ?? null);
        performance.mark(commitMark);
        afterFrame((painted) => {
          performance.mark(paintedMark);
          reflected = {
            startAt: started,
            summaryPaintedAt: summaryPainted ?? painted,
            commitAt: commit,
            paintedAt: painted,
          };
          settle();
        });
      }
      settle();
    };
    const observer = new MutationObserver(check);
    observer.observe(document.body, {
      subtree: true,
      childList: true,
      attributes: true,
      characterData: true,
    });
    window.addEventListener(
      'pointerup',
      (event) => {
        // The input's own time (performance.now() time base): its queueing delay counts.
        const at =
          Number.isFinite(event.timeStamp) && event.timeStamp > 0
            ? Math.min(event.timeStamp, performance.now())
            : performance.now();
        start = at;
        performance.mark(startMark, { startTime: at });
        queueMicrotask(check);
      },
      { capture: true, once: true },
    );
    const timer = setTimeout(() => {
      finish();
      reject(new Error(`no screen reflect within ${String(args.timeoutMs)} ms`));
    }, args.timeoutMs);
  });
}

/** Main-thread events that end the rendering update of a frame (the last one wins per frame). */
const FRAME_END_EVENTS: readonly string[] = ['Commit', 'Paint'];

/**
 * Screen reflect from a trace (ms): from the start mark to the end of the renderer main thread's
 * first Commit (else Paint) event that begins at or after the commit mark (`commitMark`: the
 * reflect's commit, or REFLECT_SERVER_COMMIT for the server rows); null when the trace lacks the
 * marks or the events.
 */
export function reflectFromTrace(
  events: readonly TraceEvent[],
  commitMark: string = REFLECT_COMMIT,
): number | null {
  const mark = (name: string): TraceEvent | undefined =>
    events.find((e) => e.name === name && e.ph !== 'e');
  const start = mark(REFLECT_START);
  const commit = mark(commitMark);
  if (start === undefined || commit === undefined) {
    return null;
  }
  const pid = commit.pid;
  let mainTid: number | null = null;
  for (const e of events) {
    if (
      e.ph === 'M' &&
      e.name === 'thread_name' &&
      e.pid === pid &&
      e.args?.name === 'CrRendererMain'
    ) {
      mainTid = e.tid;
      break;
    }
  }
  if (mainTid === null) {
    return null;
  }
  for (const name of FRAME_END_EVENTS) {
    let first: TraceEvent | null = null;
    for (const e of events) {
      if (
        e.name === name &&
        e.ph === 'X' &&
        e.pid === pid &&
        e.tid === mainTid &&
        e.ts >= commit.ts &&
        (first === null || e.ts < first.ts)
      ) {
        first = e;
      }
    }
    if (first !== null) {
      return (first.ts + (first.dur ?? 0) - start.ts) / 1000;
    }
  }
  return null;
}

/**
 * Trace categories a selection's trace adds to perf/lib/tracing.ts TRACE_CATEGORIES: the display
 * compositor's draw and swap (Display::DrawAndSwap, DirectRenderer::DrawFrame) are category `viz`.
 * The frame scenarios do not use them (the frame definition of frames.ts is unchanged).
 */
export const REFLECT_TRACE_CATEGORIES: readonly string[] = ['viz'];

/** Display compositor events that end the drawing of a frame (GPU process, VizCompositorThread). */
const VIZ_FRAME_END_EVENTS: readonly string[] = [
  'Display::DrawAndSwap',
  'DirectRenderer::DrawFrame',
];

function threadTids(events: readonly TraceEvent[], name: string, pid?: number): Set<string> {
  const keys = new Set<string>();
  for (const e of events) {
    if (
      e.ph === 'M' &&
      e.name === 'thread_name' &&
      e.args?.name === name &&
      (pid === undefined || e.pid === pid)
    ) {
      keys.add(`${String(e.pid)}:${String(e.tid)}`);
    }
  }
  return keys;
}

/**
 * Screen reflect including the GPU (ms): from the start mark to the end of the display
 * compositor's first draw and swap that begins at or after the end of the renderer main thread's
 * first Commit (else Paint) after `commitMark` (the frame that carries the DOM change; the commit
 * mark itself when the trace has no such event). Without viz events: the end of the frame's
 * PipelineReporter (the renderer's latest one that began by then and ended after it). Null when
 * the trace lacks the marks or every such event.
 */
export function reflectGpuFromTrace(
  events: readonly TraceEvent[],
  commitMark: string = REFLECT_COMMIT,
): number | null {
  const mark = (name: string): TraceEvent | undefined =>
    events.find((e) => e.name === name && e.ph !== 'e');
  const start = mark(REFLECT_START);
  const commit = mark(commitMark);
  if (start === undefined || commit === undefined) {
    return null;
  }
  const main = reflectFromTrace(events, commitMark);
  // End of the main-thread frame of the change (µs), or the commit mark.
  const mainEnd = main === null ? commit.ts : start.ts + main * 1000;
  const viz = threadTids(events, 'VizCompositorThread');
  for (const name of VIZ_FRAME_END_EVENTS) {
    let first: TraceEvent | null = null;
    for (const e of events) {
      if (
        e.name === name &&
        e.ph === 'X' &&
        viz.has(`${String(e.pid)}:${String(e.tid)}`) &&
        e.ts >= mainEnd &&
        (first === null || e.ts < first.ts)
      ) {
        first = e;
      }
    }
    if (first !== null) {
      return (first.ts + (first.dur ?? 0) - start.ts) / 1000;
    }
  }
  // PipelineReporter (async begin/end pairs of the renderer's compositor, one per frame).
  const begins = new Map<string, number>();
  let chosen: [number, number] | null = null;
  const reporters = events
    .filter((e) => e.name === 'PipelineReporter' && e.pid === commit.pid)
    .sort((a, b) => a.ts - b.ts);
  for (const e of reporters) {
    const id = String(e.id ?? e.id2?.local ?? e.id2?.global ?? '');
    if (e.ph === 'b') {
      begins.set(id, e.ts);
    } else if (e.ph === 'e') {
      const begin = begins.get(id);
      begins.delete(id);
      if (
        begin !== undefined &&
        begin <= mainEnd &&
        e.ts >= mainEnd &&
        (chosen === null || begin > chosen[0])
      ) {
        chosen = [begin, e.ts];
      }
    }
  }
  return chosen === null ? null : (chosen[1] - start.ts) / 1000;
}

/**
 * The judged screen reflect of a selection: the largest of the page value, the trace's
 * main-thread value and the trace's GPU value (null trace values are left out).
 */
export function judgedReflect(
  pageMs: number,
  traceMs: number | null,
  gpuMs: number | null = null,
): number {
  return Math.max(pageMs, traceMs ?? pageMs, gpuMs ?? pageMs);
}

/** The reflect values of one selection (judgedSelectionReflect). */
export interface SelectionReflect {
  /** The judged reflect: the preview's when its first row is the server's, else the server's. */
  readonly reflectMs: number;
  /** Pointer up to the frame after the server rows (judged like the reflect); null without them. */
  readonly serverReflectMs: number | null;
  /**
   * The reflect counted a preview row: whether its first row is the server's first row (null when
   * the reflect counted the server rows, or when the server rows are unknown).
   */
  readonly previewMatchesServer: boolean | null;
}

/**
 * The judged reflect of one selection (C-36, C-204, review fe-perf-3): a preview row counts as the
 * list's first row only when the server's first row has the same key; otherwise the selection is
 * reflected when the server rows are painted.
 */
export function judgedSelectionReflect(
  probe: ReflectProbe,
  traceMs: number | null,
  serverTraceMs: number | null,
  gpuMs: number | null = null,
  serverGpuMs: number | null = null,
): SelectionReflect {
  const reflected = judgedReflect(probe.paintedAt - probe.startAt, traceMs, gpuMs);
  const serverReflectMs =
    probe.serverListPaintedAt === null
      ? null
      : judgedReflect(probe.serverListPaintedAt - probe.startAt, serverTraceMs, serverGpuMs);
  if (probe.previewKey === null || serverReflectMs === null) {
    return { reflectMs: reflected, serverReflectMs, previewMatchesServer: null };
  }
  const matches = probe.previewKey === probe.serverKey;
  return {
    reflectMs: matches ? reflected : Math.max(reflected, serverReflectMs),
    serverReflectMs,
    previewMatchesServer: matches,
  };
}
