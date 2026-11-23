// SPDX-License-Identifier: Apache-2.0
//
// Cost of one XLog load (docs/14 §4.5 "10만 점 … 2초(N-07)", docs/13 §15 Q3; WO-33 확인 작업
// "10만 점 API 응답 크기·파싱 시간"): request → response on the wire (Resource Timing: gzip
// transfer size and end of the body) → JSON parsed by the client → columns copied into the buffer
// (tx_id parse measured separately) → first full canvas frame. All times are performance.now()
// milliseconds of the page; exposed through window.__xlogPerf.lastLoad() for the perf spec.
import type { LoadTiming } from '../../../xlog/perf';

/** One load's cost; the shape of window.__xlogPerf.lastLoad() (src/xlog/perf.ts LoadTiming). */
export type XLogLoadTiming = LoadTiming;

interface ResourceSizes {
  readonly responseEndAt: number;
  readonly encodedBytes: number;
  readonly decodedBytes: number;
  readonly transferBytes: number;
}

/**
 * Resource Timing of the newest request whose URL contains `pathPart` and that started at or after
 * `since` (performance.now() ms); null when the browser does not expose it (jsdom, old entries
 * cleared).
 */
export function resourceSizes(pathPart: string, since: number): ResourceSizes | null {
  try {
    const entries = performance.getEntriesByType('resource');
    for (let i = entries.length - 1; i >= 0; i -= 1) {
      const entry = entries[i];
      if (
        entry instanceof PerformanceResourceTiming &&
        entry.name.includes(pathPart) &&
        entry.startTime >= since - 1
      ) {
        return {
          responseEndAt: entry.responseEnd,
          encodedBytes: entry.encodedBodySize,
          decodedBytes: entry.decodedBodySize,
          transferBytes: entry.transferSize,
        };
      }
    }
  } catch {
    // No Resource Timing: the sizes stay unknown.
  }
  return null;
}
