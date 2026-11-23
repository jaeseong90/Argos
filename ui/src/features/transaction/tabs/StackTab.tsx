// SPDX-License-Identifier: Apache-2.0
//
// The STACK view (05 §2.2-8, docs/15 §8.5): the automatic stack captures of the transaction side by
// side, one column per capture (offset from the start, frames top first), frames present in every
// capture highlighted and the deepest of them marked as the point where the thread was stuck.
// Application frames are bold once the application's packages are known (C-207 (3)). The columns
// scroll inside their own box, so the page never scrolls sideways at 1366 px.
import { useTranslation } from 'react-i18next';

import { EmptyState } from '../../../components/EmptyState';
import { ValueText } from '../../../components/ValueText';
import type { TransactionDetail } from '../data/transactionApi';
import { hasSteps } from '../data/transactionModel';
import { FrameList } from '../FrameList';
import { compareCaptures, stackCaptures } from '../model/stacks';

export interface StackTabProps {
  readonly detail: TransactionDetail;
  readonly appPackages: readonly string[];
}

export function StackTab({ detail, appPackages }: StackTabProps) {
  const { t } = useTranslation(['common', 'tx']);
  const captures = stackCaptures(detail.profile.steps);
  if (captures.length === 0) {
    return (
      <EmptyState
        compact
        data-testid="transaction-stack-empty"
        title={t('tx:stack.emptyTitle')}
        description={hasSteps(detail.profile) ? t('tx:stack.empty') : t('tx:tabs.noProfile')}
      />
    );
  }
  const { common, stuckAt } = compareCaptures(captures);
  return (
    <div
      className="tx-tab tx-stack"
      data-testid="transaction-stack"
      data-captures={captures.length}
      data-common={common.size}
    >
      <p className="tx-muted">
        {captures.length > 1
          ? t('tx:stack.compareNote', { count: captures.length, common: common.size })
          : t('tx:stack.single')}
      </p>
      <div className="tx-stack__columns" role="list" aria-label={t('tx:stack.label')}>
        {captures.map((capture, index) => (
          <section
            key={capture.seq}
            role="listitem"
            className="tx-stack__column"
            aria-label={t('tx:stack.captureLabel', { n: index + 1 })}
            data-testid="transaction-stack-column"
            data-seq={capture.seq}
          >
            <h3 className="tx-stack__head">
              <span>{t('tx:stack.captureLabel', { n: index + 1 })}</span>
              <span className="tx-muted">
                {t('tx:panel.offset')} <ValueText value={capture.offsetMs} unit="ms" />
              </span>
            </h3>
            <FrameList
              source={capture}
              common={common}
              stuckAt={stuckAt}
              appPackages={appPackages}
              data-testid="transaction-stack-frames"
            />
          </section>
        ))}
      </div>
    </div>
  );
}
