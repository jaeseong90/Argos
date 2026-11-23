// SPDX-License-Identifier: Apache-2.0
//
// The absolute query range of the instance screens: the URL range resolved on the query clock,
// relative ranges re-resolved once a minute so that the metric and event queries keep a stable
// cache key between refreshes (the same rule as the statistics screens).
import { useMemo } from 'react';

import type { InstanceRange } from '../../api/instances';
import { browserTimeZone, resolveForQuery } from '../../state/timeRange';
import type { TimeRange } from '../../state/urlState';
import { useClock } from '../../state/useTimeRange';

/** Relative ranges are re-resolved this often. */
export const INSTANCE_REFRESH_MS = 60_000;

export function useInstanceRange(time: TimeRange): InstanceRange {
  const now = useClock(INSTANCE_REFRESH_MS);
  const timeZone = useMemo(() => browserTimeZone(), []);
  const key = JSON.stringify(time);
  return useMemo(() => {
    const resolved = resolveForQuery(JSON.parse(key) as TimeRange, now, timeZone);
    return { from: resolved.from, to: resolved.to };
  }, [key, now, timeZone]);
}
