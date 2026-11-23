// SPDX-License-Identifier: Apache-2.0
//
// The delta text of a dashboard summary tile (ADR-0010 C-211 (3)): without a comparison value (the
// server sends null when the comparison period has no value or a 0 value, docs/14 dashboard_get)
// the tile says "No comparison data" instead of a percent; a relative change beyond ±999 % is
// shown as ">+999%" or "<−999%" (a percent against a basis near 0 is noise, not information) and
// the two raw values go to the tooltip. The comparison value of a relative change is recovered
// from the current value and the change: previous = current / (1 + change).
import { formatSignedPercent, formatValue, type ValueUnit } from '../../../utils/format';

/** The largest relative change shown as a number: 9.99 = +999 %. */
export const TILE_DELTA_LIMIT = 9.99;

const MINUS = '−';

/** What a tile shows for its delta. */
export type TileDeltaDisplay =
  | { readonly kind: 'none' }
  | {
      readonly kind: 'value';
      /** The formatted change (relative: signed percent, possibly clamped). */
      readonly text: string | null;
      /** The change was beyond ±999 % and is shown clamped. */
      readonly clamped: boolean;
      /** The comparison period's value (relative mode), null when it cannot be recovered. */
      readonly previous: number | null;
    };

/**
 * The display of a relative change `ratio` (0.12 = +12 %) against a current value. Null, NaN or
 * infinite changes are "none" (no comparison data).
 */
export function relativeTileDelta(
  ratio: number | null | undefined,
  current: number | null | undefined,
  locale: string,
): TileDeltaDisplay {
  if (ratio === null || ratio === undefined || !Number.isFinite(ratio)) {
    return { kind: 'none' };
  }
  const previous =
    current === null || current === undefined || !Number.isFinite(current) || 1 + ratio === 0
      ? null
      : current / (1 + ratio);
  if (ratio > TILE_DELTA_LIMIT) {
    return { kind: 'value', text: '>+999%', clamped: true, previous };
  }
  if (ratio < -TILE_DELTA_LIMIT) {
    return { kind: 'value', text: `<${MINUS}999%`, clamped: true, previous };
  }
  return { kind: 'value', text: formatSignedPercent(ratio, locale), clamped: false, previous };
}

/**
 * A raw tile value for the tooltip of a clamped change: as the tile formats it, except that a
 * rate or a duration below 1 keeps 3 significant digits (the comparison value of a clamped change
 * is near 0 by definition, and "0/s" would hide it).
 */
export function formatTileRaw(value: number, unit: ValueUnit, locale: string): string {
  if (value !== 0 && Math.abs(value) < 1 && (unit === 'rate' || unit === 'ms')) {
    const text = new Intl.NumberFormat(locale, { maximumSignificantDigits: 3 }).format(value);
    return unit === 'rate' ? `${text}/s` : `${text}ms`;
  }
  return formatValue(value, unit, locale);
}
