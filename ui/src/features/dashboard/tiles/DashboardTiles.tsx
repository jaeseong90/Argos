// SPDX-License-Identifier: Apache-2.0
//
// The six summary tiles of the dashboard (05 §2.3-1, docs/15 §6.2, §9.2 top line): TPS, average,
// p95 and p99 response time, error rate and active transactions. The delta basis is the global
// `tileRef` (previous period or 7 days ago); the server gives TPS, average, p95, p99 and active
// transactions as relative changes and the error rate as an absolute difference (docs/14
// dashboard_get, ADR-0010 C-212 (1): the active change compares the live count with the basis
// range's mean, so it is missing for a range that does not end now). A missing value is the dash,
// never 0 (T-UI-007). A missing delta (no or 0 comparison value) reads "No comparison data", and a
// relative change beyond ±999 % is clamped with both raw values in its tooltip (ADR-0010 C-211 (3),
// tileDelta.ts). Each tile keeps its raw value in `data-value` ("null" when missing) and its delta
// and basis on the `…-delta` element, so tests compare the screen with the API.
import { useTranslation } from 'react-i18next';

import type { GoodDirection } from '../../../components/DeltaValue';
import { StatTile } from '../../../components/StatTile';
import type { ValueUnit } from '../../../utils/format';
import { MISSING_DASH } from '../../../utils/missing';
import type { PanelId } from '../charts/panels';
import type { DashboardModel, SeriesValues } from '../data/model';

import { formatTileRaw, relativeTileDelta } from './tileDelta';

export type TileId = 'tps' | 'avg' | 'p95' | 'p99' | 'error-rate' | 'active';

interface TileSpec {
  readonly id: TileId;
  readonly value: number | null;
  readonly unit: ValueUnit;
  readonly sparkline: SeriesValues;
  readonly panel: PanelId;
  readonly delta: { readonly value: number | null; readonly mode: 'relative' | 'absolute' } | null;
  readonly goodWhen: GoodDirection;
}

/** The tiles of a model (pure, tested): values, units, deltas and the panel each tile opens. */
export function tileSpecs(model: DashboardModel): TileSpec[] {
  const { tiles, delta, series } = model;
  return [
    {
      id: 'tps',
      value: tiles.tps,
      unit: 'rate',
      sparkline: series.tps,
      panel: 'tps',
      delta: { value: delta.tps, mode: 'relative' },
      goodWhen: 'neutral',
    },
    {
      id: 'avg',
      value: tiles.avg_ms,
      unit: 'ms',
      sparkline: series.avg,
      panel: 'response',
      delta: { value: delta.avg_ms, mode: 'relative' },
      goodWhen: 'down',
    },
    {
      id: 'p95',
      value: tiles.p95_ms,
      unit: 'ms',
      sparkline: series.p95,
      panel: 'response',
      delta: { value: delta.p95_ms, mode: 'relative' },
      goodWhen: 'down',
    },
    {
      id: 'p99',
      value: tiles.p99_ms,
      unit: 'ms',
      sparkline: series.p99,
      panel: 'response',
      delta: { value: delta.p99_ms, mode: 'relative' },
      goodWhen: 'down',
    },
    {
      id: 'error-rate',
      value: tiles.error_rate,
      unit: 'ratio',
      sparkline: series.error_rate,
      panel: 'error-rate',
      delta: { value: delta.error_rate, mode: 'absolute' },
      goodWhen: 'down',
    },
    {
      id: 'active',
      value: tiles.active,
      unit: 'count',
      sparkline: series.active,
      panel: 'active',
      delta: { value: delta.active, mode: 'relative' },
      goodWhen: 'neutral',
    },
  ];
}

/** `data-value` of a raw number: the number as JSON, "null" when missing. */
export function rawValue(value: number | null): string {
  return value === null ? 'null' : String(value);
}

export interface DashboardTilesProps {
  readonly model: DashboardModel;
  /** Scrolls to a tile's chart (docs/15 §6.2 "클릭 → 관련 차트로 스크롤"). */
  readonly onOpenPanel: (panel: PanelId) => void;
}

export function DashboardTiles({ model, onOpenPanel }: DashboardTilesProps) {
  const { t, i18n } = useTranslation(['common', 'dashboard']);
  const locale = i18n.resolvedLanguage ?? 'en';
  const label = (id: TileId): string => {
    switch (id) {
      case 'tps':
        return t('dashboard:tiles.tps');
      case 'avg':
        return t('dashboard:tiles.avg');
      case 'p95':
        return t('dashboard:tiles.p95');
      case 'p99':
        return t('dashboard:tiles.p99');
      case 'error-rate':
        return t('dashboard:tiles.errorRate');
      case 'active':
        return t('dashboard:tiles.active');
    }
  };
  const basis = model.delta.basis;
  const basisLabel =
    basis === '7d' ? t('dashboard:tileRef.sevenDays') : t('dashboard:tileRef.prev');
  const shown = (value: number | null, unit: ValueUnit): string =>
    value === null || !Number.isFinite(value) ? MISSING_DASH : formatTileRaw(value, unit, locale);
  const deltaProps = (tile: TileSpec) => {
    if (tile.delta === null) {
      return {};
    }
    const base = { value: tile.delta.value, basis, mode: tile.delta.mode, noBasisText: true };
    if (tile.delta.mode !== 'relative') {
      return { delta: base, display: null };
    }
    const display = relativeTileDelta(tile.delta.value, tile.value, locale);
    if (display.kind !== 'value' || !display.clamped || display.text === null) {
      return { delta: base, display };
    }
    const detail = t('dashboard:tileDelta.detail', {
      current: shown(tile.value, tile.unit),
      basis: basisLabel,
      previous: shown(display.previous, tile.unit),
    });
    return { delta: { ...base, text: display.text, detail }, display };
  };
  return (
    <ul className="dashboard-tiles" aria-label={t('dashboard:tiles.label')}>
      {tileSpecs(model).map((tile) => {
        const { delta, display } = deltaProps(tile);
        return (
          <li
            key={tile.id}
            className="dashboard-tiles__tile"
            data-testid={`dashboard-tile-${tile.id}`}
            data-value={rawValue(tile.value)}
          >
            <StatTile
              label={label(tile.id)}
              value={tile.value}
              unit={tile.unit}
              goodWhen={tile.goodWhen}
              sparkline={tile.sparkline}
              onClick={() => {
                onOpenPanel(tile.panel);
              }}
              data-testid={`dashboard-tile-${tile.id}-stat`}
              {...(delta === undefined ? {} : { delta })}
            />
            {tile.delta === null ? null : (
              // The raw delta and its basis for tests and tooling; the tile shows them formatted.
              <span
                hidden
                data-testid={`dashboard-tile-${tile.id}-delta`}
                data-basis={basis}
                data-value={rawValue(tile.delta.value)}
                data-clamped={display?.kind === 'value' && display.clamped ? 'true' : 'false'}
                data-previous={display?.kind === 'value' ? rawValue(display.previous) : 'null'}
              />
            )}
          </li>
        );
      })}
    </ul>
  );
}
