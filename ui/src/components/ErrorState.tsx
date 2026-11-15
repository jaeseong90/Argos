// SPDX-License-Identifier: Apache-2.0
//
// Error state (docs/15 §6.7, 05 §2.0-8): what went wrong, what to do next, the request id and
// status, and a retry button. 403 asks for a role, 429 waits for Retry-After (no automatic retry,
// C-176 (2)), 504 query_timeout asks to narrow the range. Server messages are not shown: 5xx
// bodies carry a fixed text and every case has a translated sentence here (C-132).
import { ExclamationCircleOutlined } from '@ant-design/icons';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import './states.css';

/** What the API wrapper knows about a failed request (ErrorBody, C-132). */
export interface ApiErrorInfo {
  /** HTTP status; 0 or undefined for a network failure (no response). */
  readonly status?: number;
  /** `error.code` of the body (lowercase ErrorCode name, e.g. query_timeout). */
  readonly code?: string;
  readonly requestId?: string;
  /** Seconds from the Retry-After header (429, 503). */
  readonly retryAfterSeconds?: number;
}

/** Message kinds of the error state, chosen from status and code. */
export type ErrorKind =
  | 'network'
  | 'forbidden'
  | 'notFound'
  | 'rateLimited'
  | 'timeout'
  | 'unavailable'
  | 'starting'
  | 'rangeInvalid'
  | 'limitExceeded'
  | 'filterInvalid'
  | 'unsupportedSort'
  | 'badRequest'
  | 'server';

/** Maps an API error to the message kind (codes per server ErrorCode, C-132). */
export function errorKind(error: ApiErrorInfo): ErrorKind {
  const code = error.code?.toLowerCase();
  const status = error.status ?? 0;
  if (status === 0 && code === undefined) {
    return 'network';
  }
  if (code === 'query_timeout' || status === 504) {
    return 'timeout';
  }
  if (code === 'rate_limited' || status === 429) {
    return 'rateLimited';
  }
  if (code === 'starting') {
    return 'starting';
  }
  if (code === 'storage_unavailable' || status === 503) {
    return 'unavailable';
  }
  if (status === 403) {
    return 'forbidden';
  }
  if (status === 404) {
    return 'notFound';
  }
  switch (code) {
    case 'range_invalid':
      return 'rangeInvalid';
    case 'limit_exceeded':
      return 'limitExceeded';
    case 'filter_invalid':
      return 'filterInvalid';
    case 'unsupported_sort':
      return 'unsupportedSort';
    default:
      return status >= 500 ? 'server' : 'badRequest';
  }
}

export interface ErrorStateProps {
  readonly error: ApiErrorInfo;
  /** Shows the retry button (disabled while a Retry-After wait runs). */
  readonly onRetry?: () => void;
  /** Shows a "Narrow range" button for timeouts and limit errors. */
  readonly onNarrowRange?: () => void;
  /** Shows a "Clear filters" button for filter errors. */
  readonly onClearFilters?: () => void;
  readonly compact?: boolean;
  readonly 'data-testid'?: string;
}

/**
 * Remaining whole seconds of a Retry-After wait (no automatic retry, C-176 (2)). The deadline is
 * fixed when the error arrives; a one-second interval re-reads the clock, so a throttled
 * background tab still ends the wait on time.
 */
function useCountdown(seconds: number | undefined): number {
  const [deadline] = useState(() => Date.now() + Math.max(0, seconds ?? 0) * 1000);
  const [remaining, setRemaining] = useState(() =>
    Math.max(0, Math.ceil((deadline - Date.now()) / 1000)),
  );
  useEffect(() => {
    if (remaining <= 0) {
      return undefined;
    }
    const id = setInterval(() => {
      setRemaining(Math.max(0, Math.ceil((deadline - Date.now()) / 1000)));
    }, 1000);
    return () => {
      clearInterval(id);
    };
  }, [deadline, remaining]);
  return remaining;
}

/**
 * Failed request with the next action. A new error (other request id or Retry-After) restarts the
 * Retry-After countdown.
 */
export function ErrorState(props: ErrorStateProps) {
  const { error } = props;
  return (
    <ErrorStateBody
      key={`${error.requestId ?? ''}|${String(error.status)}|${String(error.retryAfterSeconds)}`}
      {...props}
    />
  );
}

function ErrorStateBody({
  error,
  onRetry,
  onNarrowRange,
  onClearFilters,
  compact = false,
  'data-testid': testId,
}: ErrorStateProps) {
  const { t } = useTranslation();
  const kind = errorKind(error);
  const waitSeconds = useCountdown(
    kind === 'rateLimited' || kind === 'unavailable' ? error.retryAfterSeconds : undefined,
  );
  const message = (() => {
    switch (kind) {
      case 'network':
        return t('component.error.network');
      case 'forbidden':
        return t('component.error.forbidden');
      case 'notFound':
        return t('component.error.notFound');
      case 'rateLimited':
        return waitSeconds > 0
          ? t('component.error.rateLimited', { seconds: waitSeconds })
          : t('component.error.rateLimitedNow');
      case 'timeout':
        return t('component.error.timeout');
      case 'unavailable':
        return t('component.error.unavailable');
      case 'starting':
        return t('component.error.starting');
      case 'rangeInvalid':
        return t('component.error.rangeInvalid');
      case 'limitExceeded':
        return t('component.error.limitExceeded');
      case 'filterInvalid':
        return t('component.error.filterInvalid');
      case 'unsupportedSort':
        return t('component.error.unsupportedSort');
      case 'badRequest':
        return t('component.error.badRequest');
      case 'server':
        return t('component.error.server');
    }
  })();
  const showNarrow =
    onNarrowRange !== undefined && (kind === 'timeout' || kind === 'limitExceeded');
  const showClearFilters = onClearFilters !== undefined && kind === 'filterInvalid';
  const retryable = kind !== 'forbidden' && kind !== 'notFound';
  return (
    <div
      className={`state state--error${compact ? ' state--compact' : ''}`}
      role="alert"
      data-testid={testId ?? 'error-state'}
      data-error-kind={kind}
    >
      <span className="state__icon" aria-hidden="true">
        <ExclamationCircleOutlined />
      </span>
      <p className="state__title">{message}</p>
      <p className="state__meta">
        {error.status !== undefined && error.status > 0 ? (
          <span data-testid="error-state-status">
            {t('component.error.status', { status: error.status })}
          </span>
        ) : null}
        {error.requestId !== undefined && error.requestId !== '' ? (
          <span data-testid="error-state-request-id">
            {t('component.error.requestId', { id: error.requestId })}
          </span>
        ) : null}
      </p>
      <div className="state__actions">
        {showNarrow ? (
          <button
            type="button"
            className="state__button"
            onClick={onNarrowRange}
            data-testid="error-state-narrow"
          >
            {t('component.error.narrowRange')}
          </button>
        ) : null}
        {showClearFilters ? (
          <button
            type="button"
            className="state__button"
            onClick={onClearFilters}
            data-testid="error-state-clear-filters"
          >
            {t('component.empty.clearFilters')}
          </button>
        ) : null}
        {onRetry !== undefined && retryable ? (
          <button
            type="button"
            className="state__button state__button--primary"
            onClick={onRetry}
            disabled={waitSeconds > 0}
            data-testid="error-state-retry"
          >
            {waitSeconds > 0
              ? t('component.error.retryIn', { seconds: waitSeconds })
              : t('component.error.retry')}
          </button>
        ) : null}
      </div>
    </div>
  );
}
