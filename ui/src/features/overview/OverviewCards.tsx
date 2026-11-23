// SPDX-License-Identifier: Apache-2.0
//
// Card view of the overview (05 §2.10 "카드/표 전환"): the same rows in the same order (abnormal
// applications pinned first) as cards with the same required items. A card is one link to the
// application's dashboard with the global keys carried (time range included).
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

import { SafeText } from '../../components/SafeText';
import { normalizeStatus } from '../../components/StatusBadge';

import { DeployCell, FiringCell, InstancesCell, NumberCell, StatusCell, TrendCell } from './cells';
import type { SortedRow } from './sortOverview';

export interface OverviewCardsProps {
  readonly rows: readonly SortedRow[];
  readonly hrefOf: (appId: number) => string;
  readonly now: number;
}

export function OverviewCards({ rows, hrefOf, now }: OverviewCardsProps) {
  const { t } = useTranslation(['common', 'overview']);
  return (
    <ul
      className="overview-cards"
      data-testid="overview-cards"
      aria-label={t('overview:table.caption')}
    >
      {rows.map(({ app, pinned }) => {
        const id = String(app.app_id);
        const status = normalizeStatus(app.status);
        return (
          <li key={id} className="overview-cards__item">
            <Link
              to={hrefOf(app.app_id)}
              className={`overview-card overview-card--${status.toLowerCase()}${pinned ? ' overview-card--pinned' : ''}`}
              data-testid={`overview-card-${id}`}
              data-app-id={id}
              data-pinned={pinned ? 'true' : 'false'}
              data-status={status}
            >
              <span className="overview-card__head">
                <SafeText value={app.name} className="overview-card__name" />
                <StatusCell app={app} />
              </span>
              <span className="overview-card__env">
                <SafeText value={app.env} />
                {app.group === null ? null : (
                  <SafeText value={app.group} className="overview-card__group" />
                )}
              </span>
              <dl className="overview-card__values">
                <div>
                  <dt>{t('overview:table.instances')}</dt>
                  <dd>
                    <InstancesCell app={app} />
                  </dd>
                </div>
                <div>
                  <dt>{t('overview:table.tps')}</dt>
                  <dd>
                    <NumberCell app={app} field="tps" />
                  </dd>
                </div>
                <div>
                  <dt>{t('overview:table.p95')}</dt>
                  <dd>
                    <NumberCell app={app} field="p95" />
                  </dd>
                </div>
                <div>
                  <dt>{t('overview:table.errorRate')}</dt>
                  <dd>
                    <NumberCell app={app} field="error-rate" />
                  </dd>
                </div>
                <div>
                  <dt>{t('overview:table.active')}</dt>
                  <dd>
                    <NumberCell app={app} field="active" />
                  </dd>
                </div>
                <div>
                  <dt>{t('overview:table.batches')}</dt>
                  <dd>
                    <NumberCell app={app} field="batches" />
                  </dd>
                </div>
                <div>
                  <dt>{t('overview:table.firing')}</dt>
                  <dd>
                    <FiringCell app={app} />
                  </dd>
                </div>
                <div>
                  <dt>{t('overview:table.deploy')}</dt>
                  <dd>
                    <DeployCell app={app} now={now} />
                  </dd>
                </div>
              </dl>
              <TrendCell app={app} />
            </Link>
          </li>
        );
      })}
    </ul>
  );
}
