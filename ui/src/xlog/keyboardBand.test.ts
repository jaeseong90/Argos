// SPDX-License-Identifier: Apache-2.0
//
// Keyboard alternative of the XLog drag selection (docs/15 §13, WO-04 C-12): which keys do what
// and how the band moves and resizes inside the range.
import { describe, expect, it } from 'vitest';

import {
  BAND_INITIAL_FRACTION,
  BAND_MIN_FRACTION,
  BAND_STEPS,
  keyboardBandAction,
  nextKeyboardBand,
} from './keyboardBand';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U02' };

const FROM = 1_000_000;
const TO = FROM + 100_000;
const SPAN = TO - FROM;

describe('keyboard band (docs/15 §13)', () => {
  it('maps arrows, Shift+arrows, Home, End, Enter, Space and Escape', () => {
    expect(keyboardBandAction({ key: 'ArrowRight', shiftKey: false })).toBe('right');
    expect(keyboardBandAction({ key: 'ArrowLeft', shiftKey: false })).toBe('left');
    expect(keyboardBandAction({ key: 'ArrowRight', shiftKey: true })).toBe('grow');
    expect(keyboardBandAction({ key: 'ArrowLeft', shiftKey: true })).toBe('shrink');
    expect(keyboardBandAction({ key: 'Home', shiftKey: false })).toBe('start');
    expect(keyboardBandAction({ key: 'End', shiftKey: false })).toBe('end');
    expect(keyboardBandAction({ key: 'Enter', shiftKey: false })).toBe('select');
    expect(keyboardBandAction({ key: ' ', shiftKey: false })).toBe('select');
    expect(keyboardBandAction({ key: 'Escape', shiftKey: false })).toBe('cancel');
    expect(keyboardBandAction({ key: 'Tab', shiftKey: false })).toBeNull();
    // Modified keys belong to the browser and the global shortcuts.
    expect(keyboardBandAction({ key: 'ArrowRight', shiftKey: false, ctrlKey: true })).toBeNull();
    expect(keyboardBandAction({ key: 'ArrowRight', shiftKey: false, altKey: true })).toBeNull();
  });

  it('T-UI-001 starts a band of 1/10 of the range at the start, or at the end for End', () => {
    expect(nextKeyboardBand(null, 'right', FROM, TO)).toEqual({
      t1: FROM,
      t2: FROM + SPAN * BAND_INITIAL_FRACTION,
    });
    expect(nextKeyboardBand(null, 'end', FROM, TO)).toEqual({
      t1: TO - SPAN * BAND_INITIAL_FRACTION,
      t2: TO,
    });
  });

  it('T-UI-001 moves by 1/20 of the range and stays inside it', () => {
    const step = SPAN / BAND_STEPS;
    const band = { t1: FROM + 10_000, t2: FROM + 20_000 };
    expect(nextKeyboardBand(band, 'right', FROM, TO)).toEqual({
      t1: FROM + 10_000 + step,
      t2: FROM + 20_000 + step,
    });
    expect(nextKeyboardBand(band, 'left', FROM, TO)).toEqual({
      t1: FROM + 5_000,
      t2: FROM + 15_000,
    });
    expect(nextKeyboardBand({ t1: FROM, t2: FROM + 10_000 }, 'left', FROM, TO)).toEqual({
      t1: FROM,
      t2: FROM + 10_000,
    });
    expect(nextKeyboardBand({ t1: TO - 10_000, t2: TO }, 'right', FROM, TO)).toEqual({
      t1: TO - 10_000,
      t2: TO,
    });
    expect(nextKeyboardBand(band, 'start', FROM, TO)).toEqual({ t1: FROM, t2: FROM + 10_000 });
    expect(nextKeyboardBand(band, 'end', FROM, TO)).toEqual({ t1: TO - 10_000, t2: TO });
  });

  it('T-UI-001 resizes with Shift: grows to the range end, shrinks to 1/100 of the range', () => {
    const band = { t1: FROM + 10_000, t2: FROM + 20_000 };
    expect(nextKeyboardBand(band, 'grow', FROM, TO)).toEqual({
      t1: FROM + 10_000,
      t2: FROM + 25_000,
    });
    expect(nextKeyboardBand({ t1: TO - 2_000, t2: TO }, 'grow', FROM, TO)).toEqual({
      t1: TO - 2_000,
      t2: TO,
    });
    let small = band;
    for (let i = 0; i < 10; i += 1) {
      small = nextKeyboardBand(small, 'shrink', FROM, TO);
    }
    expect(small.t2 - small.t1).toBeCloseTo(SPAN * BAND_MIN_FRACTION, 6);
    expect(small.t1).toBe(FROM + 10_000);
  });

  it('pulls a band that left the range (the realtime window moved) back inside', () => {
    const moved = nextKeyboardBand({ t1: FROM - 50_000, t2: FROM - 40_000 }, 'right', FROM, TO);
    expect(moved.t1).toBeGreaterThanOrEqual(FROM);
    expect(moved.t2).toBeLessThanOrEqual(TO);
    expect(moved.t2 - moved.t1).toBe(10_000);
  });
});
