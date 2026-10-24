// SPDX-License-Identifier: Apache-2.0
//
// Chrome tracing over the DevTools protocol (Chromium only): records the trace events of every
// browser process (renderer, GPU, browser) while `action` runs.
import type { Page } from '@playwright/test';

import { isTraceEvent, type TraceEvent } from './frames';

/**
 * Categories of the DevTools Performance panel that carry what frames.ts reads: top-level tasks of
 * every thread (RunTask), user timing measures (xlog:frame) and thread names. Fewer categories
 * keep the tracing overhead low.
 */
export const TRACE_CATEGORIES: readonly string[] = [
  '__metadata',
  'blink.user_timing',
  'devtools.timeline',
  'disabled-by-default-devtools.timeline',
  'disabled-by-default-devtools.timeline.frame',
];

export interface TraceCapture<T> {
  readonly result: T;
  readonly events: readonly TraceEvent[];
  /** Chrome reported that the trace buffer overflowed. */
  readonly dataLoss: boolean;
}

export async function traceDuring<T>(
  page: Page,
  action: () => Promise<T>,
): Promise<TraceCapture<T>> {
  const cdp = await page.context().newCDPSession(page);
  const events: TraceEvent[] = [];
  let dataLoss = false;
  cdp.on('Tracing.dataCollected', (payload) => {
    for (const value of payload.value as unknown[]) {
      if (isTraceEvent(value)) {
        events.push(value);
      }
    }
  });
  const complete = new Promise<void>((resolve) => {
    cdp.once('Tracing.tracingComplete', (payload) => {
      dataLoss = payload.dataLossOccurred;
      resolve();
    });
  });
  await cdp.send('Tracing.start', {
    transferMode: 'ReportEvents',
    traceConfig: {
      recordMode: 'recordAsMuchAsPossible',
      includedCategories: [...TRACE_CATEGORIES],
    },
  });
  try {
    const result = await action();
    await cdp.send('Tracing.end');
    await complete;
    return { result, events, dataLoss };
  } finally {
    await cdp.detach().catch(() => undefined);
  }
}
