// SPDX-License-Identifier: Apache-2.0
//
// Diagnostics of the selection reflect (ADR-0003 C-36, WO-33 review fe-perf-2): with
// XLOG_PERF_TRACE_DIR set, the spec writes the renderer process's events of each selection's trace
// to <dir>/<name>.json, so that where the time between pointer up and the painted frame goes (event
// dispatch, React work, layout, paint, timers) can be read afterwards (perf/README or a script).
// Off by default: the measurement itself never depends on it. Timings only, never page data.
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

import type { TraceEvent } from './frames';
import { REFLECT_START } from './reflect';

/** A file name of `title` (letters, digits, '-', '_', '.' only). */
export function traceFileName(title: string): string {
  return title.replace(/[^A-Za-z0-9._-]+/g, '_').slice(0, 160);
}

/** The page's own selection start mark (src/features/xlog/data/perfHook.ts selectionStarted). */
const SELECTION_START = 'xlog:selection:start';

/**
 * The events of the renderer process that wrote the reflect start mark, or else the page's
 * selection start mark (all events when neither is there): the page's main thread, compositor and
 * raster threads.
 */
export function rendererEvents(events: readonly TraceEvent[]): TraceEvent[] {
  const start =
    events.find((e) => e.name === REFLECT_START && e.ph !== 'e') ??
    events.find((e) => e.name === SELECTION_START && e.ph !== 'e');
  return start === undefined ? [...events] : events.filter((e) => e.pid === start.pid);
}

/**
 * Extra trace categories of a selection's trace when `dir` is set (diagnostics only): V8's
 * compile and optimize events, so that a slow selection query can be told apart as a JIT tier-up
 * (WO-33 review r1-new-3). GC events (MinorGC, MajorGC, V8.GC_*) are in devtools.timeline
 * already. Nothing extra without `dir`: the judged measurement keeps its categories.
 */
export function diagnosticTraceCategories(dir: string | undefined): readonly string[] {
  return dir === undefined || dir.trim() === '' ? [] : ['disabled-by-default-v8.compile'];
}

/** Writes the renderer events of one selection when `dir` is set; returns the file or null. */
export function dumpSelectionTrace(
  dir: string | undefined,
  name: string,
  events: readonly TraceEvent[],
): string | null {
  if (dir === undefined || dir.trim() === '') {
    return null;
  }
  mkdirSync(dir, { recursive: true });
  const file = join(dir, `${traceFileName(name)}.json`);
  writeFileSync(file, JSON.stringify(rendererEvents(events)), 'utf8');
  return file;
}
