// SPDX-License-Identifier: Apache-2.0
//
// Links built from collected or configured URLs (external call URLs, log links, runbooks; docs/16
// §9.3, SEC-61). Only targets that safeHref() accepts become links; anything else (javascript:,
// data:, vbscript:, //host) is shown as plain text without a link.
import type { MouseEvent, ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import { isExternalHref, safeHref } from './safeHref';
import { SafeText } from './SafeText';

import './safe.css';

export interface SafeLinkProps {
  /** The link target; untrusted values are allowed here (argos/untrusted-text). */
  readonly href: string | null | undefined;
  /**
   * The visible content. Omit it to show the target itself (rendered with SafeText). Collected
   * strings must be wrapped in SafeText when passed here.
   */
  readonly children?: ReactNode;
  /** Lines of the target text before clamping when no children are given. Default 1. */
  readonly maxLines?: number;
  /**
   * Called instead of a full page load when an internal link is clicked without modifier keys
   * (the shell passes the router's navigate). External links always use the browser.
   */
  readonly onNavigate?: (path: string) => void;
  readonly className?: string;
  readonly 'data-testid'?: string;
}

/**
 * Renders a link when safeHref() accepts the target: external URLs open in a new tab with
 * rel="noopener noreferrer"; internal paths stay in the tab. Rejected targets render as text.
 */
export function SafeLink({
  href,
  children,
  maxLines = 1,
  onNavigate,
  className,
  'data-testid': testId,
}: SafeLinkProps) {
  const { t } = useTranslation();
  const target = safeHref(href);
  const content = children ?? <SafeText value={href} maxLines={maxLines} mono />;
  const classes = ['safe-link'];
  if (className !== undefined) {
    classes.push(className);
  }
  if (target === undefined) {
    classes.push('safe-link--disabled');
    return (
      <span className={classes.join(' ')} data-testid={testId}>
        {content}
        {href === null || href === undefined || href === '' ? null : (
          <span className="visually-hidden">{t('component.safeLink.blocked')}</span>
        )}
      </span>
    );
  }
  if (isExternalHref(target)) {
    return (
      <a
        className={classes.join(' ')}
        href={target}
        target="_blank"
        rel="noopener noreferrer"
        data-testid={testId}
      >
        {content}
        <span className="visually-hidden">{t('component.safeLink.newTab')}</span>
      </a>
    );
  }
  const handleClick = (event: MouseEvent<HTMLAnchorElement>): void => {
    if (
      onNavigate === undefined ||
      event.button !== 0 ||
      event.metaKey ||
      event.ctrlKey ||
      event.shiftKey ||
      event.altKey
    ) {
      return;
    }
    event.preventDefault();
    onNavigate(target);
  };
  return (
    <a className={classes.join(' ')} href={target} onClick={handleClick} data-testid={testId}>
      {content}
    </a>
  );
}
