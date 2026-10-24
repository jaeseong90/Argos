// SPDX-License-Identifier: Apache-2.0
//
// T-UI-001: sampling indicator ("표시 N / 전체 M", docs/05 §2.1, docs/15 §7.2).
import { describe, expect, it } from 'vitest';

import { samplingIndicator } from './sampling';

describe('T-UI-001 samplingIndicator', () => {
  it('shows "shown of total" when the server returned a sample', () => {
    expect(samplingIndicator({ shown: 100_000, total: 342_118 })).toEqual({
      shown: 100_000,
      total: 342_118,
    });
  });

  it('shows nothing when every point is shown', () => {
    expect(samplingIndicator({ shown: 1000, total: 1000 })).toBeNull();
    expect(samplingIndicator({ shown: 1000, total: 900 })).toBeNull();
    expect(samplingIndicator(null)).toBeNull();
    expect(samplingIndicator(undefined)).toBeNull();
  });

  it('ignores values that are not counts', () => {
    expect(samplingIndicator({ shown: -1, total: 10 })).toBeNull();
    expect(samplingIndicator({ shown: 1.5, total: 10 })).toBeNull();
    expect(samplingIndicator({ shown: 1, total: Number.NaN })).toBeNull();
    expect(samplingIndicator({ shown: 1, total: Number.POSITIVE_INFINITY })).toBeNull();
  });
});
