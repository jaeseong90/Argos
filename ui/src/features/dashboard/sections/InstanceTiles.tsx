// SPDX-License-Identifier: Apache-2.0
//
// Instance tiles of the dashboard (05 §2.3-3, docs/15 §9.2, F-S28): one tile per instance with the
// state colour (UP green, STALE yellow, DOWN red, WARN orange border; StatusBadge always shows the
// label too, so colour is never the only cue), the active-transaction bar against the busiest
// instance, heap and CPU from the instance comparison (joined by instance id) and a click to the
// instance detail in the same range. A WARN tile lists the server's WARN reasons (`warn_reasons`,
// docs/12 §4.2, ADR-0010 C-216 (2)) as text under the name and in the badge tooltip. The active
// count follows the `counter` push (the model carries it). Instance names are collected strings
// (SafeText).
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

import { SafeText } from '../../../components/SafeText';
import { normalizeStatus, StatusBadge, warnReasonKey } from '../../../components/StatusBadge';
import { ValueText } from '../../../components/ValueText';

import type { InstanceTileModel } from './sectionsModel';

export interface InstanceTilesProps {
  readonly tiles: readonly InstanceTileModel[];
  /** The instance detail link of an instance id (keeps the dashboard's range). */
  readonly detailHref: (instanceId: number) => string;
  /** The comparison (heap, CPU) is still loading. */
  readonly metricsPending?: boolean;
}

export function InstanceTiles({ tiles, detailHref, metricsPending = false }: InstanceTilesProps) {
  const { t } = useTranslation(['common', 'dashboard']);
  if (tiles.length === 0) {
    return (
      <p className="dashboard-sections__note" data-testid="dashboard-instance-tiles-empty">
        {t('dashboard:instanceTiles.empty')}
      </p>
    );
  }
  return (
    <ul
      className="dashboard-instance-tiles"
      aria-label={t('dashboard:instanceTiles.label')}
      aria-busy={metricsPending || undefined}
      data-testid="dashboard-instance-tiles"
    >
      {tiles.map(({ instance, heapRatio, cpu, activeShare }) => {
        const status = normalizeStatus(instance.state);
        const reasons = status === 'WARN' ? [...new Set(instance.warn_reasons)] : [];
        const reasonLabels = [...new Set(reasons.map((reason) => t(warnReasonKey(reason))))];
        return (
          <li key={instance.instance_id} className="dashboard-instance-tiles__item">
            <Link
              to={detailHref(instance.instance_id)}
              className={`dashboard-instance-tile dashboard-instance-tile--${status.toLowerCase()}`}
              data-testid="dashboard-instance-tile"
              data-instance-id={instance.instance_id}
              data-state={instance.state}
              data-warn-reasons={reasons.join(',')}
              data-active={instance.active ?? 'null'}
              data-heap={heapRatio ?? 'null'}
              data-cpu={cpu ?? 'null'}
            >
              <span className="dashboard-instance-tile__head">
                <StatusBadge
                  status={instance.state}
                  warnReasons={reasons}
                  data-testid="dashboard-instance-tile-status"
                />
                <SafeText
                  value={instance.name}
                  className="dashboard-instance-tile__name"
                  data-testid="dashboard-instance-tile-name"
                />
              </span>
              {reasonLabels.length > 0 ? (
                <span
                  className="dashboard-instance-tile__reasons"
                  data-testid="dashboard-instance-tile-reasons"
                  data-reasons={reasons.join(',')}
                >
                  {reasonLabels.join(' · ')}
                </span>
              ) : null}
              <span className="dashboard-instance-tile__active">
                <span className="dashboard-instance-tile__label">
                  {t('dashboard:instanceTiles.active')}
                </span>
                <span className="dashboard-instance-tile__bar" aria-hidden="true">
                  <span
                    className="dashboard-instance-tile__bar-fill"
                    style={{ width: `${String(Math.round((activeShare ?? 0) * 100))}%` }}
                    data-testid="dashboard-instance-tile-bar"
                    data-share={activeShare ?? 'null'}
                  />
                </span>
                <ValueText value={instance.active} unit="count" />
              </span>
              <span className="dashboard-instance-tile__metrics">
                <span>
                  <span className="dashboard-instance-tile__label">
                    {t('dashboard:instanceTiles.heap')}
                  </span>{' '}
                  {metricsPending ? (
                    <span className="dashboard-instance-tile__pending">
                      {t('dashboard:instanceTiles.pending')}
                    </span>
                  ) : (
                    <ValueText
                      value={heapRatio}
                      unit="ratio"
                      data-testid="dashboard-instance-tile-heap"
                    />
                  )}
                </span>
                <span>
                  <span className="dashboard-instance-tile__label">
                    {t('dashboard:instanceTiles.cpu')}
                  </span>{' '}
                  {metricsPending ? (
                    <span className="dashboard-instance-tile__pending">
                      {t('dashboard:instanceTiles.pending')}
                    </span>
                  ) : (
                    <ValueText value={cpu} unit="ratio" data-testid="dashboard-instance-tile-cpu" />
                  )}
                </span>
              </span>
            </Link>
          </li>
        );
      })}
    </ul>
  );
}
