// SPDX-License-Identifier: Apache-2.0
//
// One active alert as a link row (ADR-0010 C-227 (4)): state label with the severity, the rule
// name, then the application, target, value/threshold with the rule's unit and the time since the
// firing. The rule name has the priority width: it takes its own first line and wraps to a second
// one before it is clamped (the full name stays in the title), so the overview panel at 1366 px no
// longer cuts it to 'E…' when the application and target names are long. Rule, application and
// target names are entered or collected strings (SafeText, SEC-60). Shared by the overview and
// dashboard firing panels; not imported by the shell (initial route, C-218 (1)).
import type { ReactNode } from 'react';
import { Link } from 'react-router';

import type { Untrusted } from '../api/untrusted';
import { useFormat } from '../i18n/format';

import { AlertStateLabel, type AlertSeverity } from './AlertStateLabel';
import { SafeText } from './SafeText';

import './alertRow.css';

/** The alert a row shows (names are entered or collected strings: rendered with SafeText). */
export interface AlertSummary {
  readonly state: string | null;
  readonly severity: AlertSeverity | null;
  readonly rule: Untrusted | null;
  readonly app?: Untrusted | null;
  readonly target?: Untrusted | null;
  /** Epoch ms of the firing start. */
  readonly since: number | null;
}

export interface AlertSummaryRowProps {
  readonly href: string;
  readonly alert: AlertSummary;
  /** How the start is shown: relative ("5 min ago", overview) or the local date and time. */
  readonly sinceFormat?: 'relative' | 'dateTime';
  /**
   * Value and threshold with the rule's unit (an AlertValue), on the second line; omitted by the
   * overview panel, so its chunk (initial path, C-218 (1)) does not carry the value formatting.
   */
  readonly value?: ReactNode;
  /** Extra test ids around the rule and target names (the panels' own ids, kept stable). */
  readonly ruleTestId?: string;
  readonly targetTestId?: string;
  readonly className?: string;
}

export function AlertSummaryRow({
  href,
  alert,
  sinceFormat = 'relative',
  value,
  ruleTestId,
  targetTestId,
  className,
}: AlertSummaryRowProps) {
  const format = useFormat();
  const { state, severity, rule, since } = alert;
  const app = alert.app ?? null;
  const target = alert.target ?? null;
  const classes = ['alert-summary-row'];
  if (className !== undefined) {
    classes.push(className);
  }
  const ruleText = (
    <SafeText
      value={rule}
      maxLines={2}
      className="alert-summary-row__rule-text"
      data-testid="alert-row-rule"
    />
  );
  const targetText =
    target === null ? null : <SafeText value={target} className="alert-summary-row__target-text" />;
  const showValue = value !== undefined && value !== null;
  return (
    <Link to={href} className={classes.join(' ')} data-testid="alert-summary-row">
      <span className="alert-summary-row__head">
        <AlertStateLabel state={state} severity={severity} />
        <span className="alert-summary-row__rule" data-testid={ruleTestId}>
          {ruleText}
        </span>
        {since === null ? null : (
          <time className="alert-summary-row__since" dateTime={new Date(since).toISOString()}>
            {sinceFormat === 'relative' ? format.relative(since) : format.dateTime(since)}
          </time>
        )}
      </span>
      {app === null && target === null && !showValue ? null : (
        <span className="alert-summary-row__meta">
          {app === null ? null : <SafeText value={app} className="alert-summary-row__app" />}
          {targetText === null ? null : (
            <span className="alert-summary-row__target" data-testid={targetTestId}>
              {targetText}
            </span>
          )}
          {showValue ? value : null}
        </span>
      )}
    </Link>
  );
}
