// SPDX-License-Identifier: Apache-2.0
//
// "Compare with a normal request" (05 §2.2-2, docs/15 §8.4): this transaction's timeline and the
// timeline of the baseline's normal sample (compare=<txId>, docs/14 §6) side by side, on one shared
// scale (the longer of the two, so lengths compare directly) or each on its own 100 % (so shapes
// compare). The sample is fetched like any detail, with the baseline's start time as its hint.
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { LoadingState } from '../../components/LoadingState';
import { SafeLink } from '../../components/SafeLink';
import { SafeText } from '../../components/SafeText';
import { ValueText } from '../../components/ValueText';

import type { TransactionDetail } from './data/transactionApi';
import { hasSteps } from './data/transactionModel';
import { useTransaction } from './data/useTransaction';
import { canonicalHref } from './model/links';
import { profileTotal } from './model/timeline';
import { profileTree, setExpanded, type TreeNode } from './model/tree';
import { TimelineView } from './TimelineView';

export type CompareScale = 'shared' | 'own';

/** The scale options in display order. */
const SCALES: readonly CompareScale[] = ['shared', 'own'];

export interface CompareViewProps {
  readonly detail: TransactionDetail;
  readonly compareId: string;
  /** Start of the sample (the baseline's `sample_normal_ts`) when it is the baseline's sample. */
  readonly compareTs: string | null;
  readonly onClose: () => void;
  readonly onNavigate: (path: string) => void;
}

/** One side of the comparison. */
function Pane({
  detail,
  title,
  totalMs,
  testId,
}: {
  readonly detail: TransactionDetail;
  readonly title: string;
  readonly totalMs: number;
  readonly testId: string;
}) {
  const { t } = useTranslation(['common', 'tx']);
  const [roots] = useState<TreeNode[]>(() => profileTree(detail.profile.steps));
  const [toggled, setToggled] = useState<ReadonlySet<string>>(new Set());
  return (
    <section
      className="tx-compare__pane"
      aria-label={title}
      data-testid={testId}
      data-tx-id={detail.tx_id}
      data-total-ms={totalMs}
    >
      <h3 className="tx-compare__title">
        <span>{title}</span>
        <ValueText value={detail.elapsed_ms} unit="ms" />
      </h3>
      {hasSteps(detail.profile) ? null : (
        <p className="tx-muted" data-testid={`${testId}-no-profile`}>
          {t('tx:compare.noProfile')}
        </p>
      )}
      <TimelineView
        detail={detail}
        roots={roots}
        toggled={toggled}
        onToggle={(node, expanded) => {
          setToggled((current) => setExpanded(current, node, expanded));
        }}
        totalMs={totalMs}
        label={title}
        labelWidth={200}
        maxHeight={360}
        data-testid={`${testId}-timeline`}
      />
    </section>
  );
}

export function CompareView({
  detail,
  compareId,
  compareTs,
  onClose,
  onNavigate,
}: CompareViewProps) {
  const { t } = useTranslation(['common', 'tx']);
  const [scale, setScale] = useState<CompareScale>('shared');
  const { state, refetch } = useTransaction(compareId, compareTs);

  let body;
  if (state.status === 'pending') {
    body = <LoadingState variant="block" data-testid="transaction-compare-loading" />;
  } else if (state.status === 'error') {
    body = <ErrorState error={state.info} onRetry={refetch} compact />;
  } else if (state.status === 'invalid' || state.status === 'not_found') {
    body = (
      <EmptyState
        compact
        data-testid="transaction-compare-not-found"
        title={t('tx:compare.notFoundTitle')}
        description={t('tx:compare.notFound')}
      />
    );
  } else {
    const other = state.detail;
    const ownThis = profileTotal(detail);
    const ownOther = profileTotal(other);
    const shared = Math.max(ownThis, ownOther);
    body = (
      <>
        <div className="tx-compare__meta">
          <span>{t('tx:compare.normalOf')}</span>
          <SafeText value={other.service} />
          <SafeLink
            href={canonicalHref(other.app.app_id, other.tx_id, other.ts)}
            onNavigate={onNavigate}
            data-testid="transaction-compare-open-other"
          >
            {t('tx:compare.openOther')}
          </SafeLink>
        </div>
        <div className="tx-compare__panes" data-scale={scale}>
          <Pane
            detail={detail}
            title={t('tx:compare.thisRequest')}
            totalMs={scale === 'shared' ? shared : ownThis}
            testId="transaction-compare-this"
          />
          <Pane
            detail={other}
            title={t('tx:compare.normalRequest')}
            totalMs={scale === 'shared' ? shared : ownOther}
            testId="transaction-compare-other"
          />
        </div>
      </>
    );
  }

  return (
    <section
      className="tx-compare"
      aria-labelledby="tx-compare-title"
      data-testid="transaction-compare"
      data-compare={compareId}
    >
      <header className="tx-compare__head">
        <h2 id="tx-compare-title" className="tx-section-title">
          {t('tx:compare.title')}
        </h2>
        <div
          className="segmented"
          role="group"
          aria-label={t('tx:compare.scaleLabel')}
          data-testid="transaction-compare-scale"
        >
          {SCALES.map((option) => (
            <button
              key={option}
              type="button"
              aria-pressed={scale === option}
              className={`segmented__option${scale === option ? ' segmented__option--checked' : ''}`}
              onClick={() => {
                setScale(option);
              }}
              data-testid={`transaction-compare-scale-${option}`}
            >
              {t(`tx:compare.scale.${option}`)}
            </button>
          ))}
        </div>
        <button
          type="button"
          className="control-button"
          onClick={onClose}
          data-testid="transaction-compare-close"
        >
          {t('tx:compare.close')}
        </button>
      </header>
      {body}
    </section>
  );
}
