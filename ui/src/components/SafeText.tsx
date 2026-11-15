// SPDX-License-Identifier: Apache-2.0
//
// Collected strings (service names, URLs, SQL, exception messages, header values, user ids, ...;
// docs/16 §9.1, src/security/untrusted.md) reach the DOM only through SafeText, SafeLink and
// CodeBlock (SEC-60, SEC-61, docs/15 §6.6). SafeText renders the value as one React text node:
// no markup is ever built from it, so `<img onerror>` stays the literal text "<img onerror>".
import { useTranslation } from 'react-i18next';

import { MISSING_DASH } from '../utils/missing';

import './safe.css';

/** Longest text rendered as is; longer values end in an ellipsis (title keeps the start). */
export const SAFE_TEXT_MAX_CHARS = 4096;
/** Longest tooltip (title attribute) text. */
const TITLE_MAX_CHARS = 1024;
const ELLIPSIS = '…';

export interface SafeTextProps {
  /** The collected string. null/undefined render the missing dash with the "No data" tooltip. */
  readonly value: string | null | undefined;
  /** Lines shown before clamping with an ellipsis (CSS line clamp); 0 = no clamp. Default 1. */
  readonly maxLines?: number;
  /** Monospace font (SQL, ids, paths). */
  readonly mono?: boolean;
  /** Show the full value as a native tooltip (title). Default true. */
  readonly showTitle?: boolean;
  readonly className?: string;
  readonly 'data-testid'?: string;
}

function truncate(value: string, max: number): string {
  return value.length > max ? value.slice(0, max) + ELLIPSIS : value;
}

/**
 * Renders a collected string as a text node with an optional line clamp and the full value as
 * its tooltip. Never pass the value to anything that parses markup (SEC-60).
 */
export function SafeText({
  value,
  maxLines = 1,
  mono = false,
  showTitle = true,
  className,
  'data-testid': testId,
}: SafeTextProps) {
  const { t } = useTranslation();
  if (value === null || value === undefined) {
    return (
      <span
        className="safe-text safe-text--missing"
        title={t('component.missing.noData')}
        data-testid={testId}
      >
        {MISSING_DASH}
      </span>
    );
  }
  const classes = ['safe-text'];
  if (mono) {
    classes.push('safe-text--mono');
  }
  if (maxLines === 1) {
    classes.push('safe-text--single');
  } else if (maxLines > 1) {
    classes.push('safe-text--clamp');
  }
  if (className !== undefined) {
    classes.push(className);
  }
  return (
    <span
      className={classes.join(' ')}
      // React sets style through the CSSOM, which CSP style-src allows (docs/16 §9.9).
      style={maxLines > 1 ? { WebkitLineClamp: maxLines, lineClamp: maxLines } : undefined}
      title={showTitle ? truncate(value, TITLE_MAX_CHARS) : undefined}
      data-testid={testId}
    >
      {truncate(value, SAFE_TEXT_MAX_CHARS)}
    </span>
  );
}
