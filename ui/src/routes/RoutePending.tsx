// SPDX-License-Identifier: Apache-2.0
//
// Placeholder of a screen that a later work order builds (WO-31..39): an empty state with the
// screen's name and the next action, and nothing else (no fake data). The shell around it (top
// bar, time range, filters, menu) is complete, so links, deep links and shortcuts already work.
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';

import { EmptyState } from '../components/EmptyState';
import { carriedQuery } from '../state/urlState';
import { useUrlState } from '../state/useUrlState';

import type { ScreenDef } from './screens';

export function RoutePending({ screen }: { readonly screen: ScreenDef }) {
  const { t } = useTranslation(['common', 'shell']);
  const navigate = useNavigate();
  const { state } = useUrlState();
  const title = t(`shell:screen.${screen.label}`);
  const overview = (): void => {
    const query = carriedQuery(state);
    void navigate(query === '' ? '/overview' : `/overview?${query}`);
  };
  return (
    <section
      className="route-pending"
      data-testid="route-pending"
      data-screen={screen.id}
      data-owner={screen.owner}
    >
      <h1 className="route-pending__title">{title}</h1>
      <EmptyState
        title={t('shell:pending.title', { screen: title })}
        description={t('shell:pending.description')}
        actions={
          screen.id === 'overview'
            ? []
            : [{ label: t('shell:pending.overview'), onClick: overview }]
        }
      />
    </section>
  );
}
