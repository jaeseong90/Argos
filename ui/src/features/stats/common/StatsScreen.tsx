// SPDX-License-Identifier: Apache-2.0
//
// Frame of every statistics screen (docs/15 §6.7, §9.7, 05 §2.0-8): the root with
// `data-state="loading|empty|error|ready"` (the WO-36 data-testid contract), the title, header
// links, controls that stay visible in every state, and the state bodies whose text names the next
// action (`<root>-loading`, `<root>-empty`, `<root>-error` with request id and retry). A failed
// refresh over shown data is a compact notice above the data.
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import { errorInfo } from '../../../api/errors';
import type { StatsRange } from '../../../api/stats';
import { EmptyState, type StateAction } from '../../../components/EmptyState';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';

import './stats.css';

export type StatsScreenState = 'loading' | 'empty' | 'error' | 'ready';

export interface StatsEmpty {
  /** The fact ("No SQL statements in the selected range."). */
  readonly title: string;
  /** The next action ("Widen the range or check the kind and filters."). */
  readonly description: string;
  readonly onWidenRange?: () => void;
  readonly onClearFilters?: () => void;
  readonly actions?: readonly StateAction[];
}

export interface StatsScreenProps {
  /** Root test id (`stats-services`, `stats-sql-detail`, ...). */
  readonly testId: string;
  readonly title: ReactNode;
  readonly state: StatsScreenState;
  /** The query range (data-range-from/to, epoch ms). */
  readonly range: StatsRange;
  readonly error?: unknown;
  readonly onRetry?: () => void;
  readonly onNarrowRange?: () => void;
  readonly empty: StatsEmpty;
  /** A newer question loads while the previous answer is shown. */
  readonly refreshing?: boolean;
  /** Links next to the title (back to the list, open XLog). */
  readonly headerActions?: ReactNode;
  /** Controls shown in every state (sort, Top N, toggles). */
  readonly controls?: ReactNode;
  /** The data of the ready state. */
  readonly children?: ReactNode;
  /** Kind of loading skeleton. */
  readonly loadingVariant?: 'table' | 'chart' | 'block';
}

export function StatsScreen({
  testId,
  title,
  state,
  range,
  error,
  onRetry,
  onNarrowRange,
  empty,
  refreshing = false,
  headerActions,
  controls,
  children,
  loadingVariant = 'table',
}: StatsScreenProps) {
  const { t } = useTranslation(['common', 'stats']);
  const titleId = `${testId}-title`;
  let body: ReactNode;
  switch (state) {
    case 'loading':
      body = <LoadingState variant={loadingVariant} data-testid={`${testId}-loading`} />;
      break;
    case 'error':
      body = (
        <ErrorState
          error={errorInfo(error)}
          onRetry={onRetry}
          onNarrowRange={onNarrowRange}
          onClearFilters={empty.onClearFilters}
          data-testid={`${testId}-error`}
        />
      );
      break;
    case 'empty':
      body = (
        <EmptyState
          title={empty.title}
          description={empty.description}
          onWidenRange={empty.onWidenRange}
          onClearFilters={empty.onClearFilters}
          actions={empty.actions}
          data-testid={`${testId}-empty`}
        />
      );
      break;
    case 'ready':
      body = children;
      break;
  }
  return (
    <section
      className="stats-page"
      aria-labelledby={titleId}
      aria-busy={state === 'loading' || refreshing || undefined}
      data-testid={testId}
      data-state={state}
      data-refreshing={refreshing ? 'true' : undefined}
      data-range-from={range.from}
      data-range-to={range.to}
    >
      <header className="stats-page__header">
        <h1 id={titleId} className="stats-page__title">
          {title}
        </h1>
        {headerActions === undefined ? null : (
          <div className="stats-page__actions">{headerActions}</div>
        )}
      </header>
      {controls === undefined ? null : <div className="stats-page__controls">{controls}</div>}
      {state === 'ready' && error !== null && error !== undefined ? (
        <ErrorState
          compact
          error={errorInfo(error)}
          onRetry={onRetry}
          data-testid={`${testId}-refresh-error`}
        />
      ) : null}
      {refreshing && state !== 'loading' ? (
        <span className="visually-hidden" role="status">
          {t('stats:state.refreshing')}
        </span>
      ) : null}
      {body}
    </section>
  );
}
