// SPDX-License-Identifier: Apache-2.0
//
// Empty state (docs/15 §6.7, 05 §2.0-8): icon, one-line fact, one-line next action and buttons.
// When requests were excluded in the range (D-19) a link says how many.
import { InboxOutlined } from '@ant-design/icons';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import './states.css';

export interface StateAction {
  readonly label: string;
  readonly onClick: () => void;
  readonly 'data-testid'?: string;
}

export interface EmptyStateProps {
  /** The fact ("No transactions in the selected range."). Default: generic no-data text. */
  readonly title?: string;
  /** The next action ("Widen the range or check the filters and kind."). */
  readonly description?: string;
  /** Adds a "Widen range" button. */
  readonly onWidenRange?: () => void;
  /** Adds a "Clear filters" button. */
  readonly onClearFilters?: () => void;
  /** More buttons after the built-in ones. */
  readonly actions?: readonly StateAction[];
  /** Requests excluded in this range (D-19): shows "N requests excluded in this range". */
  readonly excluded?: { readonly count: number; readonly onOpen: () => void } | null;
  readonly icon?: ReactNode;
  readonly compact?: boolean;
  readonly 'data-testid'?: string;
}

/** Empty result with the next action. */
export function EmptyState({
  title,
  description,
  onWidenRange,
  onClearFilters,
  actions = [],
  excluded,
  icon,
  compact = false,
  'data-testid': testId,
}: EmptyStateProps) {
  const { t } = useTranslation();
  const buttons: StateAction[] = [];
  if (onWidenRange !== undefined) {
    buttons.push({
      label: t('component.empty.widenRange'),
      onClick: onWidenRange,
      'data-testid': 'empty-state-widen',
    });
  }
  if (onClearFilters !== undefined) {
    buttons.push({
      label: t('component.empty.clearFilters'),
      onClick: onClearFilters,
      'data-testid': 'empty-state-clear-filters',
    });
  }
  buttons.push(...actions);
  return (
    <div
      className={`state state--empty${compact ? ' state--compact' : ''}`}
      data-testid={testId ?? 'empty-state'}
    >
      <span className="state__icon" aria-hidden="true">
        {icon ?? <InboxOutlined />}
      </span>
      <p className="state__title">{title ?? t('component.empty.title')}</p>
      <p className="state__description">{description ?? t('component.empty.description')}</p>
      {buttons.length > 0 ? (
        <div className="state__actions">
          {buttons.map((action, index) => (
            <button
              key={index}
              type="button"
              className="state__button"
              onClick={action.onClick}
              data-testid={action['data-testid']}
            >
              {action.label}
            </button>
          ))}
        </div>
      ) : null}
      {excluded !== undefined && excluded !== null && excluded.count > 0 ? (
        <button
          type="button"
          className="state__link"
          onClick={excluded.onOpen}
          data-testid="empty-state-excluded"
        >
          {t('component.empty.excluded', { count: excluded.count })}
        </button>
      ) : null}
    </div>
  );
}
