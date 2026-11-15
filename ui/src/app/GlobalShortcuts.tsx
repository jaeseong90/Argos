// SPDX-License-Identifier: Apache-2.0
//
// Global keyboard shortcuts and the ? help (docs/15 §13). The key handling lives in
// utils/shortcuts.ts; this component maps the actions to the time range, navigation and the
// search box, and renders the help dialog (data-testid shortcut-help).
import { Modal } from 'antd';
import { useEffect, useLayoutEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';

import { useAppId, useCarriedNavigation, useCurrentScreen } from '../routes/navigation';
import { screenById } from '../routes/screens';
import { GLOBAL_SEARCH_ID } from '../routes/TopBar';
import { readRecentApps } from '../state/preferences';
import { useUiStore } from '../state/uiStore';
import type { RelativeRange } from '../state/urlState';
import { useTimeRange } from '../state/useTimeRange';
import { createShortcutHandler, SHORTCUTS, type ShortcutAction } from '../utils/shortcuts';

/** Keys of list navigation shown in the help (key names are not translated). */
const LIST_KEYS = ['↑', '↓', 'Enter'] as const;

const APP_TARGETS: Partial<Record<ShortcutAction, string>> = {
  goDashboard: 'dashboard',
  goXlog: 'xlog',
  goActive: 'active',
};

export function ShortcutHelp() {
  const { t } = useTranslation(['common', 'shell']);
  const open = useUiStore((s) => s.shortcutHelpOpen);
  const setOpen = useUiStore((s) => s.setShortcutHelpOpen);
  return (
    <Modal
      open={open}
      title={t('shell:shortcuts.title')}
      onCancel={() => {
        setOpen(false);
      }}
      footer={null}
      destroyOnHidden
    >
      <div data-testid="shortcut-help">
        <p className="shortcut-help__note">{t('shell:shortcuts.typingNote')}</p>
        <dl className="shortcut-help__list">
          {SHORTCUTS.map(({ action, keys }) => (
            <div key={action} className="shortcut-help__row">
              <dt>
                {keys.map((key) => (
                  <kbd key={key}>{key}</kbd>
                ))}
              </dt>
              <dd>{t(`shell:shortcuts.${action === 'toggleLive' ? 'live' : action}`)}</dd>
            </div>
          ))}
          <div className="shortcut-help__row">
            <dt>
              {LIST_KEYS.map((key) => (
                <kbd key={key}>{key}</kbd>
              ))}
            </dt>
            <dd>{t('shell:shortcuts.list')}</dd>
          </div>
        </dl>
      </div>
    </Modal>
  );
}

/** Attaches the shortcut handler to the window while the shell is mounted. */
export function GlobalShortcuts() {
  const time = useTimeRange();
  const screen = useCurrentScreen();
  const appId = useAppId();
  const { goToScreen } = useCarriedNavigation();
  const setHelp = useUiStore((s) => s.setShortcutHelpOpen);

  const perform = (action: ShortcutAction, preset?: RelativeRange): boolean => {
    switch (action) {
      case 'search': {
        const input = document.getElementById(GLOBAL_SEARCH_ID);
        if (input instanceof HTMLInputElement) {
          input.focus();
          input.select();
          return true;
        }
        return false;
      }
      case 'goOverview':
        goToScreen(screenById('overview'));
        return true;
      case 'goDashboard':
      case 'goXlog':
      case 'goActive': {
        const target = appId ?? readRecentApps()[0] ?? null;
        const id = APP_TARGETS[action];
        if (target === null || id === undefined) {
          return false;
        }
        goToScreen(screenById(id), target);
        return true;
      }
      case 'prev':
        time.dispatch({ type: 'prev' });
        return true;
      case 'next':
        time.dispatch({ type: 'next' });
        return true;
      case 'zoomIn':
        time.dispatch({ type: 'zoomIn' });
        return true;
      case 'zoomOut':
        time.dispatch({ type: 'zoomOut' });
        return true;
      case 'now':
        time.dispatch({ type: 'now' });
        return true;
      case 'toggleLive':
        // On a screen without live mode, l only turns off a live state carried over to it.
        if (screen?.live !== true && !time.live) {
          return false;
        }
        time.dispatch({ type: 'toggleLive' });
        return true;
      case 'preset':
        if (preset === undefined) {
          return false;
        }
        // Relative range, live kept (docs/15 §5 "프리셋 선택").
        time.dispatch({ type: 'preset', range: preset });
        return true;
      case 'escape':
        if (useUiStore.getState().shortcutHelpOpen) {
          setHelp(false);
          return true;
        }
        if (time.sel !== undefined) {
          time.dispatch({ type: 'clearSelection' });
          return true;
        }
        return false;
      case 'help':
        setHelp(true);
        return true;
    }
  };

  // The handler keeps the "g" prefix state, so it is created once; the latest action mapping is
  // read through a ref. The ref is updated in the commit phase (layout effect), not after paint, so
  // a key pressed right after a URL change acts on the committed range, not the previous one.
  const performRef = useRef(perform);
  useLayoutEffect(() => {
    performRef.current = perform;
  });
  useEffect(() => {
    const handler = createShortcutHandler((action, preset) => performRef.current(action, preset));
    const listener = (event: KeyboardEvent): void => {
      if (handler(event)) {
        event.preventDefault();
      }
    };
    window.addEventListener('keydown', listener);
    return () => {
      window.removeEventListener('keydown', listener);
    };
  }, []);

  return <ShortcutHelp />;
}
