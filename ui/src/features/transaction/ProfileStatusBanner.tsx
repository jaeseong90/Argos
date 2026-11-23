// SPDX-License-Identifier: Apache-2.0
//
// What the server says about the profile of the transaction, above the summary (D-07, docs/15 §1
// "hide nothing", 05 §2.2-9, T-ACC-07 display, ADR-0010 C-154): not captured by the sampling policy
// (`sampled_out`), captured but lost before ingest (`lost`), older than the profile retention, steps
// that may still arrive (with a refresh), a profile the agent cut at its step cap (`step_truncated`,
// partial), and summary mode (C-69 flag bit 4: later steps folded into SUMMARY steps). Nothing is
// shown for a complete captured profile.
import { useTranslation } from 'react-i18next';

import { formatNumber } from '../../utils/format';

import type { TransactionDetail } from './data/transactionApi';
import { profileNotices, type ProfileNotice } from './data/transactionModel';

/** A notice of the banner (the server's profile notices plus summary mode). */
export type BannerNotice = ProfileNotice | 'summarized';

/** The notices of a detail in display order. */
export function bannerNotices(detail: TransactionDetail): BannerNotice[] {
  const notices: BannerNotice[] = profileNotices(detail.profile);
  if (detail.flags.summarized) {
    notices.push('summarized');
  }
  return notices;
}

const KEYS = {
  sampled_out: 'tx:profile.sampledOut',
  lost: 'tx:profile.lost',
  retention_exceeded: 'tx:profile.retentionExceeded',
  steps_pending: 'tx:profile.stepsPending',
  step_truncated: 'tx:profile.stepTruncated',
  summarized: 'tx:profile.summarized',
} as const;

export interface ProfileStatusBannerProps {
  readonly detail: TransactionDetail;
  /** Fetches the detail again (steps that may still arrive). */
  readonly onRefresh?: () => void;
  readonly refreshing?: boolean;
}

export function ProfileStatusBanner({
  detail,
  onRefresh,
  refreshing = false,
}: ProfileStatusBannerProps) {
  const { t, i18n } = useTranslation(['common', 'tx']);
  const locale = i18n.resolvedLanguage ?? 'en';
  const notices = bannerNotices(detail);
  if (notices.length === 0) {
    return null;
  }
  const severe = notices.some(
    (n) => n === 'sampled_out' || n === 'lost' || n === 'retention_exceeded',
  );
  const total = detail.profile.total_steps;
  return (
    <div
      className={`tx-banner${severe ? ' tx-banner--warning' : ''}`}
      role="status"
      data-testid="transaction-profile-banner"
      data-state={detail.profile.state}
    >
      <ul className="tx-banner__list">
        {notices.map((notice) => (
          <li
            key={notice}
            data-testid={`transaction-profile-notice-${notice.replaceAll('_', '-')}`}
            data-notice={notice}
          >
            {notice === 'step_truncated'
              ? t(KEYS.step_truncated, {
                  shown: formatNumber(detail.profile.steps.length, locale),
                  total: total === null ? '—' : formatNumber(total, locale),
                })
              : t(KEYS[notice])}
            {notice === 'steps_pending' && onRefresh !== undefined ? (
              <button
                type="button"
                className="control-button tx-banner__action"
                onClick={onRefresh}
                disabled={refreshing}
                data-testid="transaction-profile-refresh"
              >
                {t('tx:profile.refresh')}
              </button>
            ) : null}
          </li>
        ))}
      </ul>
    </div>
  );
}
