// SPDX-License-Identifier: Apache-2.0
//
// Keyboard alternative to the XLog drag selection (docs/15 §13 "키보드 이동", WO-04 C-12,
// docs/poc/xlog-ui.md 8): with the plot focused, the arrow keys move a time band, Shift + arrows
// resize it, Home/End move it to the ends of the range, Enter selects it (the same `sel=` as a
// drag over the full height) and Escape drops it. Pure functions; XLogCanvas keeps the band.
import type { TimeBand } from './overlayMarks';

/** A move steps 1/20 of the range. */
export const BAND_STEPS = 20;
/** A new band covers 1/10 of the range. */
export const BAND_INITIAL_FRACTION = 0.1;
/** The band never gets narrower than 1/100 of the range. */
export const BAND_MIN_FRACTION = 0.01;

export type KeyboardBandAction = 'left' | 'right' | 'grow' | 'shrink' | 'start' | 'end';

/** What a key does with the plot focused: move/resize, select, cancel, or nothing. */
export function keyboardBandAction(event: {
  readonly key: string;
  readonly shiftKey: boolean;
  readonly altKey?: boolean;
  readonly ctrlKey?: boolean;
  readonly metaKey?: boolean;
}): KeyboardBandAction | 'select' | 'cancel' | null {
  if (event.altKey === true || event.ctrlKey === true || event.metaKey === true) {
    return null;
  }
  switch (event.key) {
    case 'ArrowRight':
      return event.shiftKey ? 'grow' : 'right';
    case 'ArrowLeft':
      return event.shiftKey ? 'shrink' : 'left';
    case 'Home':
      return 'start';
    case 'End':
      return 'end';
    case 'Enter':
    case ' ':
      return 'select';
    case 'Escape':
      return 'cancel';
    default:
      return null;
  }
}

/**
 * The band after `action` within [from, to]. Without a band, the first key creates one of
 * BAND_INITIAL_FRACTION of the range at the start (or at the end for End). A band outside the
 * range (the realtime window moved on) is first pulled back inside it.
 */
export function nextKeyboardBand(
  band: TimeBand | null,
  action: KeyboardBandAction,
  from: number,
  to: number,
): TimeBand {
  const span = Math.max(1, to - from);
  const step = span / BAND_STEPS;
  const minWidth = span * BAND_MIN_FRACTION;
  if (band === null) {
    const width = span * BAND_INITIAL_FRACTION;
    return action === 'end' ? { t1: to - width, t2: to } : { t1: from, t2: from + width };
  }
  let width = Math.min(span, Math.max(minWidth, Math.abs(band.t2 - band.t1)));
  let t1 = Math.min(Math.max(Math.min(band.t1, band.t2), from), to - width);
  switch (action) {
    case 'left':
      t1 = Math.max(from, t1 - step);
      break;
    case 'right':
      t1 = Math.min(to - width, t1 + step);
      break;
    case 'grow':
      width = Math.min(to - t1, width + step);
      break;
    case 'shrink':
      width = Math.max(minWidth, width - step);
      break;
    case 'start':
      t1 = from;
      break;
    case 'end':
      t1 = to - width;
      break;
  }
  return { t1, t2: t1 + width };
}
