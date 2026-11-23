// SPDX-License-Identifier: Apache-2.0
//
// The error tab (05 §2.2-7, docs/15 §8.6): the exception class of the transaction, its masked
// message, the ERROR steps with their stack frames (application frames bold once the application's
// packages are known, ADR-0010 C-207 (3)) and the link to the error group in the detail's time
// range. The detail response carries no exception message (docs/14 §4.6 has none): the tab says so
// and points to the error group, whose masked message pattern is the source (reported to the PM).
import { useTranslation } from 'react-i18next';

import { EmptyState } from '../../../components/EmptyState';
import { SafeLink } from '../../../components/SafeLink';
import { SafeText } from '../../../components/SafeText';
import type { TransactionDetail } from '../data/transactionApi';
import { FrameList } from '../FrameList';
import { errorSteps } from '../model/calls';
import type { DetailLinks } from '../model/links';
import { Field } from '../parts';

export interface ErrorTabProps {
  readonly detail: TransactionDetail;
  readonly links: DetailLinks;
  readonly onNavigate: (path: string) => void;
  /** Application packages for bold frames (none until the API provides them). */
  readonly appPackages: readonly string[];
}

export function ErrorTab({ detail, links, onNavigate, appPackages }: ErrorTabProps) {
  const { t } = useTranslation(['common', 'tx']);
  const steps = errorSteps(detail.profile.steps);
  if (!detail.error && steps.length === 0) {
    return (
      <EmptyState
        compact
        data-testid="transaction-error-empty"
        title={t('tx:error.noneTitle')}
        description={t('tx:error.none')}
      />
    );
  }
  return (
    <div className="tx-tab tx-error" data-testid="transaction-error">
      <dl className="tx-totals">
        <Field label={t('tx:error.class')} emphasis>
          <SafeText value={detail.error_class} mono data-testid="transaction-error-class" />
        </Field>
        <Field label={t('tx:error.message')} note={t('tx:error.messageNote')}>
          <span data-testid="transaction-error-message">
            <SafeText value={null} />
          </span>
        </Field>
        <Field label={t('tx:error.group')}>
          {detail.error_group_id === null ? (
            <span className="tx-muted" data-testid="transaction-error-no-group">
              {t('tx:error.noGroup')}
            </span>
          ) : (
            <SafeLink
              href={links.errorGroup(detail.error_group_id)}
              onNavigate={onNavigate}
              data-testid="transaction-error-group-link"
            >
              {t('tx:error.groupLink')}
            </SafeLink>
          )}
        </Field>
      </dl>
      {appPackages.length === 0 ? (
        <p className="tx-muted" data-testid="transaction-error-app-frames-note">
          {t('tx:error.appFramesUnavailable')}
        </p>
      ) : null}
      {steps.length === 0 ? (
        <p className="tx-muted" data-testid="transaction-error-no-steps">
          {t('tx:error.noSteps')}
        </p>
      ) : (
        steps.map((step) => (
          <section
            key={step.seq}
            className="tx-error__step"
            aria-label={t('tx:error.stepLabel', { seq: step.seq })}
            data-testid="transaction-error-step"
            data-seq={step.seq}
          >
            <h3 className="tx-section-title">
              <SafeText value={step.name ?? null} mono />
            </h3>
            <FrameList
              source={step}
              appPackages={appPackages}
              data-testid="transaction-error-frames"
            />
          </section>
        ))
      )}
    </div>
  );
}
