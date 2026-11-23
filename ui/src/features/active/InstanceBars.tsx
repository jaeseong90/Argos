// SPDX-License-Identifier: Apache-2.0
//
// Per-instance bars of the active transactions screen (05 §2.6-1, docs/15 §9.6 InstanceBars): the
// length is the instance's running count (relative to the busiest instance), split into the four
// elapsed bands with their numbers in text; the instance state of the session (UP, WARN, STALE,
// DOWN; ADR-0010 C-218 (4)) as a StatusBadge when it is not UP; "approx." when the split is an
// estimate and the hint when the agent sent only its longest transactions. A click or Enter on a
// bar toggles the list's `f.instance` filter; the bars themselves never filter.
import { useTranslation } from 'react-i18next';

import type { ActiveInstance } from '../../api/active';
import { SafeText } from '../../components/SafeText';
import { normalizeStatus, StatusBadge } from '../../components/StatusBadge';
import { ValueText } from '../../components/ValueText';
import { useFormat } from '../../i18n/format';

import { bandLabel } from './bands';
import { BANDS, type InstanceBands, type Thresholds } from './model';

export interface InstanceBarsProps {
  readonly instances: readonly ActiveInstance[];
  readonly bands: ReadonlyMap<number, InstanceBands>;
  readonly thresholds: Thresholds;
  readonly selected: ReadonlySet<string>;
  readonly onToggle: (instanceId: number) => void;
}

export function InstanceBars({
  instances,
  bands,
  thresholds,
  selected,
  onToggle,
}: InstanceBarsProps) {
  const { t } = useTranslation(['common', 'active']);
  const format = useFormat();
  const busiest = Math.max(1, ...instances.map((instance) => instance.count));
  return (
    <ul className="active-bars" aria-label={t('active:bars.label')} data-testid="active-bars">
      {instances.map((instance) => {
        const id = instance.instance_id;
        const entry = bands.get(id);
        const split = entry?.bands ?? [0, 0, 0, 0];
        const count = entry?.count ?? instance.count;
        const status = normalizeStatus(instance.state);
        const isSelected = selected.has(String(id));
        return (
          <li
            key={id}
            className={`active-bar${isSelected ? ' active-bar--selected' : ''}`}
            data-testid={`active-bar-${String(id)}`}
            data-state={status}
            data-count={count}
            data-buckets={split.join(',')}
            data-approx={String(entry?.approximate ?? false)}
            data-selected={String(isSelected)}
          >
            <button
              type="button"
              className="active-bar__button"
              aria-pressed={isSelected}
              title={isSelected ? t('active:bars.unfilter') : t('active:bars.filter')}
              onClick={() => {
                onToggle(id);
              }}
              data-testid={`active-bar-button-${String(id)}`}
            >
              <span className="active-bar__name">
                {instance.name === null ? (
                  t('active:bars.unnamed', { id })
                ) : (
                  <SafeText value={instance.name} />
                )}
              </span>
              <span className="active-bar__track" aria-hidden="true">
                {BANDS.map((band) =>
                  split[band] > 0 ? (
                    <span
                      key={band}
                      className={`active-bar__segment active-swatch--band-${String(band)}`}
                      style={{ width: `${String((split[band] / busiest) * 100)}%` }}
                    />
                  ) : null,
                )}
              </span>
              <span className="active-bar__count" data-testid={`active-bar-count-${String(id)}`}>
                {t('active:bars.count', { count })}
              </span>
            </button>
            <span className="active-bar__numbers" data-testid={`active-bar-numbers-${String(id)}`}>
              {BANDS.map((band) => (
                <span
                  key={band}
                  className="active-bar__number"
                  title={bandLabel(band, thresholds, t, format)}
                  data-band={band}
                >
                  <span
                    className={`active-swatch active-swatch--band-${String(band)}`}
                    aria-hidden="true"
                  />
                  <span className="visually-hidden">{bandLabel(band, thresholds, t, format)}</span>
                  {format.number(split[band])}
                </span>
              ))}
            </span>
            <span className="active-bar__max">
              {t('active:bars.max')}{' '}
              <ValueText
                value={instance.max_elapsed_ms}
                unit="ms"
                data-testid={`active-bar-max-${String(id)}`}
              />
            </span>
            {status === 'UP' ? null : (
              <StatusBadge
                status={instance.state}
                data-testid={`active-bar-status-${String(id)}`}
              />
            )}
            {entry?.approximate === true ? (
              <span
                className="active-bar__note"
                title={t('active:bars.approximateTitle')}
                data-testid={`active-bar-approx-${String(id)}`}
              >
                {t('active:bars.approximate')}
                <span className="visually-hidden">{t('active:bars.approximateTitle')}</span>
              </span>
            ) : null}
            {instance.truncated ? (
              <span className="active-bar__note" data-testid={`active-bar-truncated-${String(id)}`}>
                {t('active:bars.truncated')}
              </span>
            ) : null}
          </li>
        );
      })}
    </ul>
  );
}
