// SPDX-License-Identifier: Apache-2.0
//
// The Batch option of the global kind filter (C-205 (4), C-225 (7)).
import { describe, expect, it } from 'vitest';

import { batchAvailable } from './uiStore';

describe('batchAvailable', () => {
  it('unknown app kinds keep Batch; stored kinds or the live snapshot enable it', () => {
    expect(batchAvailable(null, null)).toBe(true);
    expect(batchAvailable(null, ['HTTP'])).toBe(true);
    expect(batchAvailable(['HTTP', 'BATCH'], null)).toBe(true);
    expect(batchAvailable(['HTTP'], null)).toBe(false);
    expect(batchAvailable(['HTTP'], [])).toBe(false);
    expect(batchAvailable(['HTTP'], ['HTTP', 'MESSAGE'])).toBe(false);
    // A running BATCH on the active screen although no batch job finished in the last 7 days.
    expect(batchAvailable(['HTTP'], ['BATCH', 'HTTP'])).toBe(true);
    expect(batchAvailable([], ['BATCH'])).toBe(true);
  });
});
