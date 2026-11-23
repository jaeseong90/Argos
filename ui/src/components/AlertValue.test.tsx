// SPDX-License-Identifier: Apache-2.0
//
// Alert value and threshold with the rule's unit (ADR-0010 C-227 (4), 05 §2.9-2): each unit of the
// server's AlertMetric.Unit, state as UP/DOWN (ko too), unknown units as plain numbers, and a
// missing value or threshold as the dash with the "No data" tooltip, apart from 0 (T-UI-007).
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { AlertValue, chartUnitOf, formatAlertNumber, normalizeAlertUnit } from './AlertValue';
import { withI18n } from './testing/withI18n';

/** Requirements of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'D-23' };

function shown(
  value: number | null,
  threshold: number | null,
  unit: string | null,
  language: 'en' | 'ko' = 'en',
) {
  const view = render(<AlertValue value={value} threshold={threshold} unit={unit} />, {
    wrapper: withI18n(language),
  });
  const element = screen.getByTestId('alert-value');
  const text = element.textContent;
  view.unmount();
  return { text, element };
}

describe('AlertValue (C-227 (4))', () => {
  it('formats value and threshold by the rule unit', () => {
    expect(shown(0.071, 0.05, 'ratio').text).toBe('7.1% / 5%');
    expect(shown(1500, 1000, 'ms').text).toBe('1.5s / 1s');
    expect(shown(42.5, 10, 'per_second').text).toBe('42.5/s / 10/s');
    expect(shown(90, 60, 'seconds').text).toBe('1m 30s / 1m');
    expect(shown(3, 30, 'days').text).toBe('3d / 30d');
    expect(shown(0.81, 0.85, 'score').text).toBe('0.81 / 0.85');
    expect(shown(12_000, 100, 'count').text).toBe('12K / 100');
  });

  it('shows the instance state as DOWN/UP without a threshold', () => {
    expect(shown(1, 1, 'state').text).toBe('DOWN');
    expect(shown(0, 1, 'state').text).toBe('UP');
  });

  it('falls back to plain numbers for an unknown or missing unit', () => {
    expect(shown(3.5, 2, 'furlongs').text).toBe('3.5 / 2');
    expect(shown(3, 2, null).element).toHaveAttribute('data-unit', 'none');
  });

  it('T-UI-007 shows a missing value or threshold as the dash, never as 0', () => {
    const missing = shown(null, 0, 'ratio');
    expect(missing.text).toBe('— / 0%');
    expect(missing.element).toHaveAttribute('data-value', 'null');
    expect(missing.element).toHaveAttribute('data-threshold', '0');
    const both = shown(0, null, 'ms');
    expect(both.text).toBe('0ms / —');
  });

  it('carries the raw values and unit for checks', () => {
    const { element } = shown(0.071, 0.05, 'ratio');
    expect(element).toHaveAttribute('data-value', '0.071');
    expect(element).toHaveAttribute('data-threshold', '0.05');
    expect(element).toHaveAttribute('data-unit', 'ratio');
  });

  it('maps units for charts and formats numbers without React', () => {
    expect(normalizeAlertUnit('per_second')).toBe('per_second');
    expect(normalizeAlertUnit(7)).toBeNull();
    expect(chartUnitOf('per_second')).toBe('rate');
    expect(chartUnitOf('days')).toBe('none');
    expect(formatAlertNumber(0.5, 'ratio', 'en')).toBe('50%');
  });
});
