// SPDX-License-Identifier: Apache-2.0
//
// The tab strip of the transaction detail (docs/15 §8.6, `tab=` of docs/14 §6, SEC-64 validated by
// TxDetailQ): timeline (default), tree, sql, ext, error, stack, headers, with the count of what each
// holds. WAI-ARIA tabs with automatic activation: Left/Right move and select, Home/End go to the
// first and last tab, and only the selected tab is in the tab order (docs/15 §13).
import { useRef, type KeyboardEvent, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import type { TxDetailState } from '../../state/urlState';

export type DetailTab = TxDetailState['tab'];

/** Tabs in display order. */
export const DETAIL_TABS: readonly DetailTab[] = [
  'timeline',
  'tree',
  'sql',
  'ext',
  'error',
  'stack',
  'headers',
];

export interface DetailTabsProps {
  readonly active: DetailTab;
  readonly onChange: (tab: DetailTab) => void;
  /** Count shown next to a tab label (SQL statements, calls, captures, headers), if any. */
  readonly counts: Partial<Record<DetailTab, number>>;
  /** Tabs to mark (the error tab of a failed transaction). */
  readonly marked?: ReadonlySet<DetailTab>;
  readonly children: ReactNode;
}

export function tabId(tab: DetailTab): string {
  return `tx-tab-${tab}`;
}

export function panelId(tab: DetailTab): string {
  return `tx-tabpanel-${tab}`;
}

export function DetailTabs({ active, onChange, counts, marked, children }: DetailTabsProps) {
  const { t } = useTranslation(['common', 'tx']);
  const refs = useRef(new Map<DetailTab, HTMLButtonElement | null>());
  const move = (event: KeyboardEvent<HTMLButtonElement>): void => {
    const index = DETAIL_TABS.indexOf(active);
    let next: number | null = null;
    switch (event.key) {
      case 'ArrowRight':
        next = (index + 1) % DETAIL_TABS.length;
        break;
      case 'ArrowLeft':
        next = (index - 1 + DETAIL_TABS.length) % DETAIL_TABS.length;
        break;
      case 'Home':
        next = 0;
        break;
      case 'End':
        next = DETAIL_TABS.length - 1;
        break;
      default:
        break;
    }
    if (next === null) {
      return;
    }
    event.preventDefault();
    const tab = DETAIL_TABS[next];
    if (tab !== undefined) {
      onChange(tab);
      refs.current.get(tab)?.focus();
    }
  };
  return (
    <div className="tx-tabs">
      <div
        role="tablist"
        aria-label={t('tx:tabs.label')}
        className="tx-tabs__list"
        data-testid="transaction-tabs"
      >
        {DETAIL_TABS.map((tab) => {
          const selected = tab === active;
          const count = counts[tab];
          return (
            <button
              key={tab}
              ref={(element) => {
                refs.current.set(tab, element);
              }}
              type="button"
              role="tab"
              id={tabId(tab)}
              aria-selected={selected}
              aria-controls={selected ? panelId(tab) : undefined}
              tabIndex={selected ? 0 : -1}
              className={`tx-tabs__tab${selected ? ' tx-tabs__tab--active' : ''}${marked?.has(tab) === true ? ' tx-tabs__tab--marked' : ''}`}
              onClick={() => {
                onChange(tab);
              }}
              onKeyDown={move}
              data-testid={`transaction-tab-${tab}`}
            >
              {t(`tx:tabs.${tab}`)}
              {count === undefined ? null : <span className="tx-tabs__count">{count}</span>}
            </button>
          );
        })}
      </div>
      <div
        role="tabpanel"
        id={panelId(active)}
        aria-labelledby={tabId(active)}
        className="tx-tabs__panel"
        data-testid={`transaction-tabpanel-${active}`}
      >
        {children}
      </div>
    </div>
  );
}
