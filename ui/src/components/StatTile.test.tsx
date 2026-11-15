// SPDX-License-Identifier: Apache-2.0
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { deltaTone, DeltaValue } from './DeltaValue';
import { sparklinePath, StatTile } from './StatTile';
import { installDomStubs } from './testing/dom';
import { withI18n } from './testing/withI18n';
import { ValueText } from './ValueText';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'D-23' };

installDomStubs();

const wrapper = withI18n('en');

describe('StatTile (docs/15 §6.2)', () => {
  it('shows value, unit, delta against the basis and a sparkline', () => {
    render(
      <StatTile
        label="p95"
        value={1234}
        unit="ms"
        delta={{ value: 0.12, basis: '7d' }}
        goodWhen="down"
        sparkline={[1, 2, null, 4]}
      />,
      { wrapper },
    );
    const tile = screen.getByTestId('stat-tile');
    expect(tile).toHaveTextContent('p95');
    expect(tile).toHaveTextContent('1.23s');
    expect(tile).toHaveTextContent('▲+12%');
    expect(tile).toHaveTextContent('(worse)');
    expect(tile).toHaveTextContent('vs 7 days ago');
    expect(screen.getByTestId('delta')).toHaveAttribute('data-tone', 'bad');
    expect(screen.getByTestId('stat-tile-sparkline').querySelector('path')?.getAttribute('d')).toBe(
      sparklinePath([1, 2, null, 4]),
    );
  });

  it('T-UI-007 shows the dash with the No data tooltip for a missing value, never 0', async () => {
    const user = userEvent.setup();
    render(<StatTile label="Error rate" value={null} unit="ratio" />, { wrapper });
    const tile = screen.getByTestId('stat-tile');
    expect(tile).toHaveAttribute('data-missing', 'true');
    expect(tile.querySelector('.stat-tile__value')).toHaveTextContent('—');
    expect(tile.querySelector('.stat-tile__value')).not.toHaveTextContent('0');
    await user.hover(tile.querySelector('.stat-tile__missing') as HTMLElement);
    expect(await screen.findByRole('tooltip')).toHaveTextContent('No data');
  });

  it('T-UI-007 shows Not measurable when measured=false and 0 as a value', () => {
    const { rerender } = render(
      <StatTile label="CPU" value={0.4} unit="ratio" measured={false} />,
      { wrapper },
    );
    expect(screen.getByTestId('stat-tile')).toHaveTextContent('Not measurable');
    rerender(<StatTile label="Errors" value={0} unit="count" />);
    expect(screen.getByTestId('stat-tile').querySelector('.stat-tile__value')).toHaveTextContent(
      '0',
    );
    expect(screen.getByTestId('stat-tile')).not.toHaveAttribute('data-missing');
  });

  it('becomes a button when clickable', async () => {
    const user = userEvent.setup();
    const onClick = vi.fn();
    render(
      <StatTile
        label="TPS"
        value={12.5}
        unit="rate"
        delta={{ value: -0.1, basis: 'prev' }}
        onClick={onClick}
      />,
      {
        wrapper,
      },
    );
    await user.click(screen.getByRole('button'));
    expect(onClick).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button')).toHaveTextContent('vs previous period');
    expect(screen.getByTestId('delta')).toHaveAttribute('data-tone', 'bad');
  });
});

describe('DeltaValue and ValueText', () => {
  it('colours by the metric direction and keeps an arrow and sign', () => {
    expect(deltaTone(0.1, 'up')).toBe('good');
    expect(deltaTone(-0.1, 'up')).toBe('bad');
    expect(deltaTone(0.1, 'down')).toBe('bad');
    expect(deltaTone(-0.1, 'down')).toBe('good');
    expect(deltaTone(0, 'down')).toBe('neutral');
    expect(deltaTone(0.5, 'neutral')).toBe('neutral');
    const { rerender } = render(<DeltaValue value={-0.05} goodWhen="down" />, { wrapper });
    expect(screen.getByTestId('delta')).toHaveTextContent('▼−5%(better)');
    rerender(<DeltaValue value={0.002} goodWhen="down" mode="absolute" unit="ratio" />);
    expect(screen.getByTestId('delta')).toHaveTextContent('▲+0.2%(worse)');
    rerender(<DeltaValue value={null} goodWhen="up" />);
    expect(screen.getByTestId('delta')).toHaveTextContent('—');
  });

  it('T-UI-007 tells missing bytes and durations from zero', () => {
    const { rerender } = render(<ValueText value={undefined} unit="bytes" data-testid="v" />, {
      wrapper,
    });
    expect(screen.getByTestId('v')).toHaveTextContent('—');
    expect(screen.getByTestId('v')).toHaveAttribute('data-missing', 'noData');
    rerender(<ValueText value={0} unit="bytes" data-testid="v" />);
    expect(screen.getByTestId('v')).toHaveTextContent('0 B');
    rerender(<ValueText value={0} unit="ms" data-testid="v" />);
    expect(screen.getByTestId('v')).toHaveTextContent('0ms');
    rerender(<ValueText value={5} unit="ms" measured={false} data-testid="v" />);
    expect(screen.getByTestId('v')).toHaveTextContent('Not measurable');
  });

  it('draws sparklines with gaps at null', () => {
    expect(sparklinePath([])).toBe('');
    expect(sparklinePath([null, null])).toBe('');
    const path = sparklinePath([1, null, 3]);
    expect(path.match(/M/g)).toHaveLength(2);
    expect(path).not.toContain('L');
  });
});
