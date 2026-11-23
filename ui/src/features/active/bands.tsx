// SPDX-License-Identifier: Apache-2.0
//
// Band labels and the legend of the elapsed colours (05 §2.6-1): the four bands always carry their
// range in text next to the colour swatch, so the colour never carries the meaning alone
// (docs/15 §13).
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';

import { useFormat, type Formatter } from '../../i18n/format';

import { BANDS, type Band, type Thresholds } from './model';

/** "< 1 s", "1–3 s", "3–8 s", "≥ 8 s" for the thresholds. */
export function bandLabel(
  band: Band,
  thresholds: Thresholds,
  t: TFunction<['common', 'active']>,
  format: Formatter,
): string {
  const [t1, t2, t3] = thresholds.map((value) => format.number(value, 1)) as [
    string,
    string,
    string,
  ];
  switch (band) {
    case 0:
      return t('active:band.first', { to: t1 });
    case 1:
      return t('active:band.middle', { from: t1, to: t2 });
    case 2:
      return t('active:band.middle', { from: t2, to: t3 });
    case 3:
      return t('active:band.last', { from: t3 });
  }
}

/** The legend: swatch, range and (optionally) the total of each band. */
export function BandLegend({
  thresholds,
  totals,
}: {
  readonly thresholds: Thresholds;
  readonly totals: readonly number[];
}) {
  const { t } = useTranslation(['common', 'active']);
  const format = useFormat();
  return (
    <ul className="active-legend" aria-label={t('active:band.legend')} data-testid="active-legend">
      {BANDS.map((band) => (
        <li
          key={band}
          className="active-legend__item"
          data-testid={`active-legend-${String(band)}`}
        >
          <span
            className={`active-swatch active-swatch--band-${String(band)}`}
            aria-hidden="true"
          />
          <span>{bandLabel(band, thresholds, t, format)}</span>
          <span className="active-legend__count" data-value={totals[band] ?? 0}>
            {format.number(totals[band] ?? 0)}
          </span>
        </li>
      ))}
    </ul>
  );
}
