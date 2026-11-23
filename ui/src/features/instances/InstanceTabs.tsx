// SPDX-License-Identifier: Apache-2.0
//
// The tab strip of the instance detail (docs/15 §9.8, `tab=` of InstanceQ, SEC-64 validated):
// metrics (default), environment, commands, thread dumps. WAI-ARIA tabs with automatic activation:
// Left/Right move and select, Home/End go to the first and last tab, and only the selected tab is
// in the tab order (docs/15 §13).
import { useRef, type KeyboardEvent, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

export type InstanceTab = 'metrics' | 'env' | 'commands' | 'dumps';

/** Tabs in display order. */
export const INSTANCE_TABS: readonly InstanceTab[] = ['metrics', 'env', 'commands', 'dumps'];

export interface InstanceTabsProps {
  readonly active: InstanceTab;
  readonly onChange: (tab: InstanceTab) => void;
  readonly children: ReactNode;
}

function tabId(tab: InstanceTab): string {
  return `instance-tab-${tab}`;
}

function panelId(tab: InstanceTab): string {
  return `instance-tabpanel-${tab}`;
}

export function InstanceTabs({ active, onChange, children }: InstanceTabsProps) {
  const { t } = useTranslation(['common', 'instance']);
  const refs = useRef(new Map<InstanceTab, HTMLButtonElement | null>());
  const label = (tab: InstanceTab): string => {
    switch (tab) {
      case 'metrics':
        return t('instance:tabs.metrics');
      case 'env':
        return t('instance:tabs.env');
      case 'commands':
        return t('instance:tabs.commands');
      case 'dumps':
        return t('instance:tabs.dumps');
    }
  };
  const move = (event: KeyboardEvent<HTMLButtonElement>): void => {
    const index = INSTANCE_TABS.indexOf(active);
    let next: number | null = null;
    switch (event.key) {
      case 'ArrowRight':
        next = (index + 1) % INSTANCE_TABS.length;
        break;
      case 'ArrowLeft':
        next = (index - 1 + INSTANCE_TABS.length) % INSTANCE_TABS.length;
        break;
      case 'Home':
        next = 0;
        break;
      case 'End':
        next = INSTANCE_TABS.length - 1;
        break;
      default:
        break;
    }
    if (next === null) {
      return;
    }
    event.preventDefault();
    const tab = INSTANCE_TABS[next];
    if (tab !== undefined) {
      onChange(tab);
      refs.current.get(tab)?.focus();
    }
  };
  return (
    <div className="instance-tabs">
      <div
        role="tablist"
        aria-label={t('instance:tabs.label')}
        className="instance-tabs__list"
        data-testid="instance-tabs"
      >
        {INSTANCE_TABS.map((tab) => {
          const selected = tab === active;
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
              className={`instance-tabs__tab${selected ? ' instance-tabs__tab--active' : ''}`}
              onClick={() => {
                onChange(tab);
              }}
              onKeyDown={move}
              data-testid={`instance-tab-${tab}`}
            >
              {label(tab)}
            </button>
          );
        })}
      </div>
      <div
        role="tabpanel"
        id={panelId(active)}
        aria-labelledby={tabId(active)}
        className="instance-tabs__panel"
        data-testid={`instance-tabpanel-${active}`}
      >
        {children}
      </div>
    </div>
  );
}
