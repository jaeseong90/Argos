// SPDX-License-Identifier: Apache-2.0
//
// Cells of the statistics tables: raw values with null apart from 0 (T-UI-007, D-23), the change
// against the previous range per ADR-0010 C-211 (3) (no basis → "No comparison data", beyond
// ±999 % clamped with both values in the tooltip, error rate in percentage points), and the
// estimated badge only for bucket estimates (C-14).
// Requirements (test/README.md §5): { type: 'req', description: 'F-U05' }, { type: 'req', description: 'D-23' }
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { withI18n } from '../../../components/testing/withI18n';

import { EstimatedBadge, formatPoints, StatsDelta, StatsValue } from './cells';

describe('T-UI-007 statistics values (D-23)', () => {
  it('T-UI-007 shows 0 as a value and null as the dash with an empty data-value', () => {
    render(
      <>
        <StatsValue value={0} unit="count" data-testid="zero" />
        <StatsValue value={null} unit="ms" data-testid="none" />
        <StatsValue value={450} unit="ms" data-testid="p95" />
      </>,
      { wrapper: withI18n('en') },
    );
    const zero = screen.getByTestId('zero');
    expect(zero).toHaveAttribute('data-value', '0');
    expect(zero).not.toHaveAttribute('data-missing');
    expect(zero).toHaveTextContent('0');
    const none = screen.getByTestId('none');
    expect(none).toHaveAttribute('data-value', '');
    expect(none).toHaveAttribute('data-missing', 'true');
    expect(none).toHaveTextContent('—');
    expect(screen.getByTestId('p95')).toHaveAttribute('data-value', '450');
  });

  it('T-UI-007 says "Not measurable" for a value the server did not measure, apart from 0', () => {
    render(<StatsValue value={null} unit="ms" measured={false} data-testid="wait" />, {
      wrapper: withI18n('en'),
    });
    const wait = screen.getByTestId('wait');
    expect(wait).toHaveTextContent('Not measurable');
    expect(wait).toHaveAttribute('data-missing', 'true');
    expect(wait.querySelector('[data-missing="notMeasurable"]')).not.toBeNull();
  });
});

describe('C-211 (3) changes against the previous range', () => {
  it('C-211 (3) shows "No comparison data" without a basis', () => {
    render(
      <StatsDelta
        field="count"
        value={null}
        current={12}
        unit="count"
        mode="relative"
        goodWhen="neutral"
      />,
      { wrapper: withI18n('en') },
    );
    const delta = screen.getByTestId('stats-delta-count');
    expect(delta).toHaveAttribute('data-value', '');
    expect(delta).toHaveAttribute('data-missing', 'true');
    expect(delta).toHaveTextContent('No comparison data');
  });

  it('C-211 (3) shows a relative change with its direction; p95 up is worse', () => {
    render(
      <StatsDelta
        field="p95_ms"
        value={0.12}
        current={450}
        unit="ms"
        mode="relative"
        goodWhen="down"
      />,
      { wrapper: withI18n('en') },
    );
    const delta = screen.getByTestId('stats-delta-p95_ms');
    expect(delta).toHaveAttribute('data-value', '0.12');
    expect(delta).toHaveTextContent('+12%');
    expect(delta).toHaveTextContent('(worse)');
    expect(delta.querySelector('[data-tone="bad"]')).not.toBeNull();
  });

  it('C-211 (3) clamps beyond +999 % and keeps the raw value', () => {
    render(
      <StatsDelta
        field="count"
        value={25}
        current={52}
        unit="count"
        mode="relative"
        goodWhen="neutral"
      />,
      { wrapper: withI18n('en') },
    );
    const delta = screen.getByTestId('stats-delta-count');
    expect(delta).toHaveAttribute('data-value', '25');
    expect(delta).toHaveAttribute('data-clamped', 'true');
    expect(delta).toHaveTextContent('>+999%');
    // The previous range's value (52 / 26 = 2) is part of the hidden detail text.
    expect(delta).toHaveTextContent('Previous range: 2');
  });

  it('C-211 (3) shows the error rate change in percentage points; down is better', () => {
    render(
      <StatsDelta
        field="error_rate"
        value={-0.004}
        current={0.01}
        unit="ratio"
        mode="points"
        goodWhen="down"
      />,
      { wrapper: withI18n('ko') },
    );
    const delta = screen.getByTestId('stats-delta-error_rate');
    expect(delta).toHaveAttribute('data-value', '-0.004');
    expect(delta).toHaveTextContent('−0.4%p');
    expect(delta.querySelector('[data-tone="good"]')).not.toBeNull();
    expect(formatPoints(0.0125, 'en')).toBe('+1.25');
    expect(formatPoints(0, 'en')).toBe('0');
  });
});

describe('C-14 estimated badge', () => {
  it('C-14 names bucket-estimated percentiles', () => {
    render(<EstimatedBadge />, { wrapper: withI18n('en') });
    const badge = screen.getByTestId('estimated-badge');
    expect(badge).toHaveTextContent('Estimated');
    expect(badge).toHaveAttribute('title', 'Percentiles estimated from latency buckets');
  });
});
