// SPDX-License-Identifier: Apache-2.0
//
// The summary tiles' delta display on screen (ADR-0010 C-211 (3)): a missing delta reads "No
// comparison data" (en and ko), a change beyond ±999 % reads ">+999%" with both raw values in the
// tooltip, a normal change the signed percent.
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { installDomStubs } from '../../../components/testing/dom';
import { withI18n } from '../../../components/testing/withI18n';
import { dashboardModel } from '../data/model';
import { dashboardBody, dashboardMeta } from '../testing/fixtures';

import { DashboardTiles } from './DashboardTiles';

/** Requirement of every titled test in this file (test/README.md §5). */
export const REQ = { type: 'req', description: 'F-U01' };

installDomStubs();

const FROM = Date.parse('2026-11-20T03:00:00.000Z');

function model(delta: { tps: number | null; p95_ms: number | null; error_rate: number | null }) {
  const body = dashboardBody({ from: FROM });
  return dashboardModel(
    { ...body, tiles: { ...body.tiles, delta: { ...body.tiles.delta, ...delta } } },
    dashboardMeta(FROM),
  );
}

function deltaOf(tile: string): HTMLElement {
  return within(screen.getByTestId(`dashboard-tile-${tile}-stat`)).getByTestId('delta');
}

describe('dashboard tile deltas (ADR-0010 C-211 (3))', () => {
  it('T-UI-007 a missing delta says "No comparison data" instead of a percent (en, ko)', () => {
    const m = model({ tps: null, p95_ms: null, error_rate: null });
    const { unmount } = render(<DashboardTiles model={m} onOpenPanel={() => undefined} />, {
      wrapper: withI18n('en'),
    });
    for (const tile of ['tps', 'p95', 'error-rate']) {
      expect(deltaOf(tile)).toHaveTextContent('No comparison data');
      expect(deltaOf(tile)).not.toHaveTextContent('%');
    }
    unmount();
    render(<DashboardTiles model={m} onOpenPanel={() => undefined} />, {
      wrapper: withI18n('ko'),
    });
    expect(deltaOf('tps')).toHaveTextContent('비교 데이터 없음');
  });

  it('T-UI-007 a change beyond ±999 % reads >+999% with both raw values in the tooltip', async () => {
    // tiles.tps is 12.5; a change of +5333.46 means a previous TPS of 12.5 / 5334.46.
    const m = model({ tps: 5333.46, p95_ms: -0.05, error_rate: 0.002 });
    render(<DashboardTiles model={m} onOpenPanel={() => undefined} />, {
      wrapper: withI18n('en'),
    });
    const tps = deltaOf('tps');
    expect(tps).toHaveTextContent('>+999%');
    expect(tps).not.toHaveTextContent('533,346');
    const raw = screen.getByTestId('dashboard-tile-tps-delta');
    expect(raw).toHaveAttribute('data-value', '5333.46');
    expect(raw).toHaveAttribute('data-clamped', 'true');
    expect(Number(raw.getAttribute('data-previous'))).toBeCloseTo(12.5 / 5334.46, 9);
    await userEvent.setup().hover(tps);
    const tooltip = await screen.findByRole('tooltip');
    expect(tooltip).toHaveTextContent('This period: 12.5/s');
    expect(tooltip).toHaveTextContent('Previous period: 0.00234/s');
    expect(deltaOf('p95')).toHaveTextContent('−5%');
    expect(screen.getByTestId('dashboard-tile-p95-delta')).toHaveAttribute('data-clamped', 'false');
  });
});
