// SPDX-License-Identifier: Apache-2.0
//
// Points of a data-space selection without the hit index (docs/15 §7.3 condition
// `t1 <= ts <= t2 && e1 <= elapsed <= e2`): used when the selection comes from the URL (`sel=`,
// a deep link, Back, a reload, or new data under an existing selection), where there is no drag on
// the current layout to query. One linear pass over the buffer, about 1 ms for 100,000 points.
import { inDataRect, type DataRect } from './scale';
import type { XLogBuffer } from './XLogBuffer';

/** Physical indices of the points inside `rect`, oldest first. */
export function scanDataRect(buffer: XLogBuffer, rect: DataRect): Uint32Array {
  const matches = new Uint32Array(buffer.size);
  let found = 0;
  const { ts, elapsed } = buffer;
  for (let logical = 0; logical < buffer.size; logical += 1) {
    const p = buffer.physical(logical);
    if (inDataRect(rect, ts[p] ?? 0, elapsed[p] ?? 0)) {
      matches[found] = p;
      found += 1;
    }
  }
  return matches.slice(0, found);
}
