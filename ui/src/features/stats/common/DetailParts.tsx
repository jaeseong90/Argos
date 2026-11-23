// SPDX-License-Identifier: Apache-2.0
//
// Parts shared by the statistics details (docs/15 §9.7): the header links (back to the list with
// the range kept, the XLog with the view's filter: `stats-open-xlog`), the raw window note of the
// breakdowns read from raw rows (`stats-raw-window`: instances, SQL, external calls of the last
// 24 h of the range at most, docs/14 §4.8), a summary figure with its raw value, and the check of
// a 64-bit key in the route (decimal string, C-175).
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';

import type { RawWindow } from '../../../api/stats';
import { SafeLink } from '../../../components/SafeLink';
import { formatDateTime, type ValueUnit } from '../../../utils/format';

import { StatsValue } from './cells';

/** 64-bit keys of the routes: unsigned decimal strings (ADR-0010 C-175). */
const ID64 = /^[0-9]{1,20}$/;

/** The route key, or null when it is not an unsigned decimal string. */
export function routeKey(value: string | undefined): string | null {
  return value !== undefined && ID64.test(value) ? value : null;
}

export interface DetailLinksProps {
  /** The list of the detail, with the carried keys. */
  readonly backHref: string;
  /** The XLog with the view's filter, or null. */
  readonly xlogHref: string | null;
}

/** "Back to list" and "Related transactions in XLog". */
export function DetailLinks({ backHref, xlogHref }: DetailLinksProps) {
  const { t } = useTranslation(['common', 'stats']);
  const navigate = useNavigate();
  const onNavigate = (path: string): void => {
    void navigate(path);
  };
  return (
    <>
      <SafeLink href={backHref} onNavigate={onNavigate} data-testid="stats-back">
        {t('stats:backToList')}
      </SafeLink>
      {xlogHref === null ? null : (
        <SafeLink href={xlogHref} onNavigate={onNavigate} data-testid="stats-open-xlog">
          {t('stats:openXlog')}
        </SafeLink>
      )}
    </>
  );
}

/** Where the raw-row breakdowns come from (docs/14 §4.8 raw_window). */
export function RawWindowNote({ window }: { readonly window: RawWindow | null }) {
  const { t, i18n } = useTranslation(['common', 'stats']);
  const locale = i18n.resolvedLanguage ?? 'en';
  if (window === null) {
    return (
      <p className="stats-note" data-testid="stats-raw-window" data-state="none">
        {t('stats:rawWindow.none')}
      </p>
    );
  }
  const from = Date.parse(window.from);
  const to = Date.parse(window.to);
  return (
    <p
      className="stats-note"
      data-testid="stats-raw-window"
      data-from={window.from}
      data-to={window.to}
    >
      {t('stats:rawWindow.text', {
        from: Number.isFinite(from) ? formatDateTime(from, locale) : window.from,
        to: Number.isFinite(to) ? formatDateTime(to, locale) : window.to,
      })}
    </p>
  );
}

export interface SummaryItemProps {
  readonly label: string;
  readonly value: number | null | undefined;
  readonly unit: ValueUnit;
  readonly measured?: boolean | null;
  readonly 'data-testid': string;
  readonly note?: ReactNode;
}

/** One figure of a detail summary, with its raw value (`data-value`). */
export function SummaryItem({
  label,
  value,
  unit,
  measured,
  'data-testid': testId,
  note,
}: SummaryItemProps) {
  return (
    <div className="stats-summary__item">
      <dt>{label}</dt>
      <dd>
        <StatsValue value={value} unit={unit} measured={measured} data-testid={testId} />
        {note === undefined ? null : <span className="stats-summary__note">{note}</span>}
      </dd>
    </div>
  );
}
