// SPDX-License-Identifier: Apache-2.0
//
// Partial-result strip (docs/15 §6.7, §1 "hide nothing", D-06, D-07, D-03): sampling ("Showing N of
// M"), rollup source ("1-minute aggregates"), raw retention exceeded, truncation at the limit and
// the reason a profile was not captured. Shown above the data it describes; renders nothing when
// the result is complete.
import { InfoCircleOutlined } from '@ant-design/icons';
import { useTranslation } from 'react-i18next';

import { formatNumber } from '../utils/format';

import './states.css';

/** The envelope fields that make a result partial (QueryMeta, docs/14 §2.5). */
export interface PartialMeta {
  readonly shown?: number | null;
  readonly total?: number | null;
  readonly sampling?: { readonly applied: boolean } | null;
  readonly source?: 'raw' | '1m' | '1h' | 'mixed' | null;
  readonly retention_exceeded?: boolean | null;
  readonly truncated?: boolean | null;
}

/** Profile state of a transaction (docs/14 §4.6 `profile.state`, D-07). */
export type ProfileState = 'captured' | 'sampled_out' | 'lost';

export interface PartialBannerProps {
  readonly meta?: PartialMeta | null;
  readonly profileState?: ProfileState | null;
  readonly 'data-testid'?: string;
}

interface Notice {
  readonly id: string;
  readonly text: string;
}

/** Notices for an envelope and profile state, in display order. */
export function usePartialNotices(
  meta: PartialMeta | null | undefined,
  profileState?: ProfileState | null,
): Notice[] {
  const { t, i18n } = useTranslation();
  const locale = i18n.resolvedLanguage ?? 'en';
  const notices: Notice[] = [];
  if (meta !== null && meta !== undefined) {
    const shown = meta.shown ?? null;
    const total = meta.total ?? null;
    if (meta.sampling?.applied === true) {
      notices.push({
        id: 'partial-sampling',
        text:
          shown !== null && total !== null
            ? t('component.partial.sampling', {
                shown: formatNumber(shown, locale),
                total: formatNumber(total, locale),
              })
            : t('component.partial.samplingNoCount'),
      });
    }
    if (meta.truncated === true) {
      notices.push({
        id: 'partial-truncated',
        text:
          shown !== null
            ? t('component.partial.truncated', { shown: formatNumber(shown, locale) })
            : t('component.partial.truncatedNoCount'),
      });
    }
    if (meta.source === '1m') {
      notices.push({ id: 'partial-source', text: t('component.partial.source1m') });
    } else if (meta.source === '1h') {
      notices.push({ id: 'partial-source', text: t('component.partial.source1h') });
    } else if (meta.source === 'mixed') {
      notices.push({ id: 'partial-source', text: t('component.partial.sourceMixed') });
    }
    if (meta.retention_exceeded === true) {
      notices.push({ id: 'partial-retention', text: t('component.partial.retentionExceeded') });
    }
  }
  if (profileState === 'sampled_out') {
    notices.push({ id: 'partial-profile', text: t('component.partial.profileSampledOut') });
  } else if (profileState === 'lost') {
    notices.push({ id: 'partial-profile', text: t('component.partial.profileLost') });
  }
  return notices;
}

/** Strip of partial-result notices; nothing when the result is complete. */
export function PartialBanner({ meta, profileState, 'data-testid': testId }: PartialBannerProps) {
  const notices = usePartialNotices(meta, profileState);
  if (notices.length === 0) {
    return null;
  }
  return (
    <div className="partial-banner" role="status" data-testid={testId ?? 'partial-banner'}>
      <span className="partial-banner__icon" aria-hidden="true">
        <InfoCircleOutlined />
      </span>
      <ul className="partial-banner__list">
        {notices.map((notice) => (
          <li key={notice.id} data-testid={notice.id}>
            {notice.text}
          </li>
        ))}
      </ul>
    </div>
  );
}
