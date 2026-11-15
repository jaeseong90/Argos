// SPDX-License-Identifier: Apache-2.0
//
// Loading state (docs/15 §6.7): a skeleton in the shape of the content (table, chart, tiles) and,
// after 2 seconds, the hint that narrowing the range makes the query faster (05 §2.0-8).
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import './states.css';

/** Delay before the "narrow the range" hint (docs/15 §6.7). */
export const SLOW_LOADING_MS = 2000;

export interface LoadingStateProps {
  readonly variant?: 'table' | 'chart' | 'tiles' | 'block';
  /** Skeleton rows of the table variant. Default 6. */
  readonly rows?: number;
  /** Delay before the slow hint. Default 2,000 ms. */
  readonly slowAfterMs?: number;
  readonly 'data-testid'?: string;
}

/** Skeleton with an accessible busy status and the slow-query hint. */
export function LoadingState({
  variant = 'block',
  rows = 6,
  slowAfterMs = SLOW_LOADING_MS,
  'data-testid': testId,
}: LoadingStateProps) {
  const { t } = useTranslation();
  const [slow, setSlow] = useState(false);
  useEffect(() => {
    const id = setTimeout(() => {
      setSlow(true);
    }, slowAfterMs);
    return () => {
      clearTimeout(id);
    };
  }, [slowAfterMs]);

  const skeleton = (() => {
    switch (variant) {
      case 'table':
        return (
          <div className="skeleton skeleton--table">
            {Array.from({ length: Math.max(1, Math.min(rows, 50)) }, (_, index) => (
              <span key={index} className="skeleton__bar" />
            ))}
          </div>
        );
      case 'chart':
        return <div className="skeleton skeleton--chart" />;
      case 'tiles':
        return (
          <div className="skeleton skeleton--tiles">
            {Array.from({ length: 4 }, (_, index) => (
              <span key={index} className="skeleton__tile" />
            ))}
          </div>
        );
      case 'block':
        return <div className="skeleton skeleton--block" />;
    }
  })();

  return (
    <div
      className="state state--loading"
      role="status"
      aria-busy="true"
      aria-live="polite"
      data-testid={testId ?? 'loading-state'}
      data-slow={slow ? 'true' : undefined}
    >
      <span className="visually-hidden">{t('component.loading.label')}</span>
      {skeleton}
      {slow ? (
        <p className="state__hint" data-testid="loading-state-slow">
          {t('component.loading.slow')}
        </p>
      ) : null}
    </div>
  );
}
