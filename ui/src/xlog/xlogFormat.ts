// SPDX-License-Identifier: Apache-2.0
//
// Pure formatting of XLog response times (docs/15 §7.2 "배치 단위", §12: the unit follows the
// value, the number and unit come from the i18n keys `xlog:unit.*`). One function for the canvas
// (useXLogFormat: axis, tooltip, threshold) and for the E2E checks that compare what the screen
// shows with an API value (ui/e2e/required/xlogChecks.ts), so both format a value the same way.
import { durationUnitFor, toUnit, type DurationUnit } from './scale';

/** The unit keys of the `xlog` namespace. */
export type DurationUnitKey = 'xlog:unit.ms' | 'xlog:unit.s' | 'xlog:unit.min';

/** Translation of a unit key with the value in that unit (i18next `t` or the E2E lookup). */
export type DurationTranslate = (
  key: DurationUnitKey,
  values: { readonly value: number },
) => string;

const UNIT_KEYS: Readonly<Record<DurationUnit, DurationUnitKey>> = {
  ms: 'xlog:unit.ms',
  s: 'xlog:unit.s',
  min: 'xlog:unit.min',
};

/** `ms` in `unit` (by default the unit of the value itself: ms below 10 s, s below 10 min). */
export function formatDuration(
  translate: DurationTranslate,
  ms: number,
  unit: DurationUnit = durationUnitFor(ms),
): string {
  return translate(UNIT_KEYS[unit], { value: toUnit(ms, unit) });
}
