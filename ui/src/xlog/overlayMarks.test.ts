// SPDX-License-Identifier: Apache-2.0
//
// Overlay marks of the XLog canvas (docs/15 §7.2 "마커·음영", WO-33): outage bands clipped to the
// plot (C-5, D-18), hatch lines inside their band, marker grouping and emphasis (docs/15 §6.1,
// §4.3 item 4), keyboard band in plot px (docs/15 §13).
import { describe, expect, it } from 'vitest';

import { colorTokens } from '../styles/tokens';

import {
  HATCH_SPACING_PX,
  MARKER_GROUP_PX,
  MIN_BAND_PX,
  bandPixels,
  hatchSegments,
  markerColor,
  outageBands,
  overlayMarkers,
  type XLogMarker,
} from './overlayMarks';
import { createScale, xOf } from './scale';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U02' };

const FROM = 1_700_000_000_000;
const TO = FROM + 100_000;
const scale = createScale(FROM, TO, 1000, 400, 2000, 'linear');

function marker(id: string, ts: number, extra: Partial<XLogMarker> = {}): XLogMarker {
  return { id, ts, type: 'deploy', ...extra };
}

describe('outage bands (C-5, D-18)', () => {
  it('T-UI-001 maps an outage to plot px and clips it to the range', () => {
    expect(outageBands(scale, [{ from: FROM + 10_000, to: FROM + 20_000 }])).toEqual([
      { x1: 100, x2: 200 },
    ]);
    // Starts before the range, ends after it: the whole plot.
    expect(outageBands(scale, [{ from: FROM - 50_000, to: TO + 50_000 }])).toEqual([
      { x1: 0, x2: 1000 },
    ]);
  });

  it('T-UI-001 extends an ongoing outage (to null) to the end of the range', () => {
    expect(outageBands(scale, [{ from: FROM + 90_000, to: null }])).toEqual([
      { x1: 900, x2: 1000 },
    ]);
  });

  it('T-UI-001 drops outages outside the range and empty ones, keeps short ones visible', () => {
    expect(
      outageBands(scale, [
        { from: FROM - 20_000, to: FROM - 10_000 },
        { from: TO + 1, to: TO + 10 },
        { from: FROM + 5_000, to: FROM + 5_000 },
      ]),
    ).toEqual([]);
    const [short] = outageBands(scale, [{ from: FROM + 50_000, to: FROM + 50_010 }]);
    expect(short?.x1).toBe(500);
    expect((short?.x2 ?? 0) - (short?.x1 ?? 0)).toBe(MIN_BAND_PX);
    // A minimum-width band at the right edge is clipped to the plot.
    const [edge] = outageBands(scale, [{ from: TO - 1, to: TO }]);
    expect(edge?.x2).toBe(1000);
  });

  it('draws hatch lines only inside the band rectangle, on a shared 45° grid', () => {
    const segments = hatchSegments(100, 160, 0, 40);
    expect(segments.length).toBeGreaterThan(0);
    for (const [xa, ya, xb, yb] of segments) {
      for (const x of [xa, xb]) {
        expect(x).toBeGreaterThanOrEqual(100);
        expect(x).toBeLessThanOrEqual(160);
      }
      for (const y of [ya, yb]) {
        expect(y).toBeGreaterThanOrEqual(0);
        expect(y).toBeLessThanOrEqual(40);
      }
      // On x + y = k with k a multiple of the spacing.
      expect((xa + ya) % HATCH_SPACING_PX).toBe(0);
      expect(xa + ya).toBe(xb + yb);
      expect(xb).toBeGreaterThan(xa);
    }
    expect(hatchSegments(10, 10, 0, 40)).toEqual([]);
    expect(hatchSegments(10, 20, 40, 40)).toEqual([]);
  });
});

describe('event markers (docs/15 §6.1)', () => {
  it('T-UI-001 places a marker at its time and drops markers outside the plot', () => {
    const marks = overlayMarkers(
      scale,
      [marker('a', FROM + 25_000), marker('out', TO + 5_000), marker('before', FROM - 1)],
      null,
    );
    expect(marks).toEqual([
      {
        x: 250,
        shape: 'triangle',
        color: colorTokens['color-primary'],
        count: 1,
        emphasized: false,
      },
    ]);
  });

  it('groups markers closer than MARKER_GROUP_PX into one with a count', () => {
    const near = FROM + 50_000;
    const step = ((MARKER_GROUP_PX - 1) / scale.width) * (TO - FROM);
    const marks = overlayMarkers(
      scale,
      [
        marker('a', near, { type: 'start' }),
        marker('b', near + step / 2, { type: 'stop' }),
        marker('c', near + 30_000, { type: 'config' }),
      ],
      null,
    );
    expect(marks.map((m) => [m.count, m.shape])).toEqual([
      [2, 'dot'],
      [1, 'diamond'],
    ]);
  });

  it('T-UI-001 lets the alert= event lead and emphasise its group (deep link 4)', () => {
    const near = FROM + 50_000;
    const marks = overlayMarkers(
      scale,
      [
        marker('first', near, { type: 'start' }),
        marker('target', near + 100, { type: 'alert', severity: 'critical' }),
      ],
      'target',
    );
    expect(marks).toHaveLength(1);
    expect(marks[0]).toMatchObject({
      x: xOf(scale, near + 100),
      shape: 'asterisk',
      color: colorTokens['sev-critical'],
      count: 2,
      emphasized: true,
    });
    expect(overlayMarkers(scale, [marker('first', near)], 'absent')[0]?.emphasized).toBe(false);
  });

  it('colours alert transitions by severity and deployments in the primary colour', () => {
    expect(markerColor(marker('a', FROM, { type: 'alert' }))).toBe(colorTokens['sev-warn']);
    expect(markerColor(marker('a', FROM, { type: 'alert', severity: 'critical' }))).toBe(
      colorTokens['sev-critical'],
    );
    expect(markerColor(marker('a', FROM, { type: 'stop' }))).toBe(colorTokens['color-text-muted']);
  });
});

describe('keyboard band in plot px (docs/15 §13)', () => {
  it('maps the band and clips it to the plot', () => {
    expect(bandPixels(scale, { t1: FROM + 10_000, t2: FROM + 30_000 })).toEqual({
      x1: 100,
      x2: 300,
    });
    expect(bandPixels(scale, { t1: FROM + 30_000, t2: FROM + 10_000 })).toEqual({
      x1: 100,
      x2: 300,
    });
    expect(bandPixels(scale, { t1: FROM - 10_000, t2: TO + 10_000 })).toEqual({ x1: 0, x2: 1000 });
    expect(bandPixels(scale, { t1: TO + 1, t2: TO + 2 })).toBeNull();
    expect(bandPixels(scale, null)).toBeNull();
  });
});
