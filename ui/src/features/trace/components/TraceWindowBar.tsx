// SPDX-License-Identifier: Apache-2.0
//
// The searched range of the trace (docs/14 §4.7, docs/15 §9.5 "`?ts` 힌트, 없으면 24h"): what the
// server searched (the response's window and its basis: ±1 h around the ts hint, the time in the
// trace id, a window, the 24 h default) and the time hint of the URL, with actions to search the
// last 7 days or the last 30 days. The 30-day search is a heavy, rate-limited query (SEC-56): it
// asks first. The same buttons serve the "not in the searched range" state.
import { useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { Popup } from '../../../components/Popup';
import { formatDateTimeWithOffset } from '../../../utils/format';
import type { TraceWindow, TraceWindowRange } from '../data/traceApi';

export interface WindowSearchProps {
  /** The `window` of the URL, if any. */
  readonly current: TraceWindow | undefined;
  readonly onSearch: (window: '7d' | '30d') => void;
  /** data-testids of the two buttons; the 30-day dialog's ids derive from `month`. */
  readonly testIds?: { readonly week: string; readonly month: string };
}

const WINDOW_TEST_IDS = { week: 'trace-window-7d', month: 'trace-window-30d' } as const;

/** "Search the last 7 days" and "Search the last 30 days…" (with the heavy-query confirmation). */
export function WindowSearch({ current, onSearch, testIds = WINDOW_TEST_IDS }: WindowSearchProps) {
  const { t } = useTranslation(['common', 'trace']);
  const [confirming, setConfirming] = useState(false);
  const trigger = useRef<HTMLButtonElement>(null);
  if (current === '30d') {
    return null;
  }
  return (
    <span className="trace-window__search">
      {current === '7d' ? null : (
        <button
          type="button"
          className="control-button"
          onClick={() => {
            onSearch('7d');
          }}
          data-testid={testIds.week}
        >
          {t('trace:window.search7d')}
        </button>
      )}
      <span className="trace-window__confirm-anchor">
        <button
          ref={trigger}
          type="button"
          className="control-button"
          aria-haspopup="dialog"
          aria-expanded={confirming}
          onClick={() => {
            setConfirming((open) => !open);
          }}
          data-testid={testIds.month}
        >
          {t('trace:window.search30d')}
        </button>
        <Popup
          open={confirming}
          onClose={() => {
            setConfirming(false);
          }}
          label={t('trace:window.confirmTitle')}
          triggerRef={trigger}
          align="end"
          data-testid={`${testIds.month}-dialog`}
        >
          <div className="popup__form">
            <p className="popup__heading">{t('trace:window.confirmTitle')}</p>
            <p className="popup__note">{t('trace:window.confirmBody')}</p>
            <div className="popup__actions">
              <button
                type="button"
                className="control-button"
                onClick={() => {
                  setConfirming(false);
                  trigger.current?.focus();
                }}
                data-testid={`${testIds.month}-cancel`}
              >
                {t('trace:window.cancel')}
              </button>
              <button
                type="button"
                className="control-button control-button--primary"
                onClick={() => {
                  setConfirming(false);
                  onSearch('30d');
                }}
                data-testid={`${testIds.month}-confirm`}
              >
                {t('trace:window.confirm')}
              </button>
            </div>
          </div>
        </Popup>
      </span>
    </span>
  );
}

export interface TraceWindowBarProps {
  readonly range: TraceWindowRange;
  /** The `window` of the URL, if any. */
  readonly window: TraceWindow | undefined;
  /** The ts hint of the URL (epoch ms), if any. */
  readonly hintMs: number | undefined;
  readonly onSearch: (window: '7d' | '30d') => void;
}

function useBasisText(): (basis: string, window: TraceWindow | undefined) => string {
  const { t } = useTranslation(['common', 'trace']);
  return (basis, window) => {
    switch (basis) {
      case 'ts_hint':
        return t('trace:window.basis.tsHint');
      case 'id_hint':
        return t('trace:window.basis.idHint');
      case 'default':
        return t('trace:window.basis.default');
      case 'id_hint_then_default':
        return t('trace:window.basis.idHintThenDefault');
      case 'window':
        switch (window) {
          case '1h':
            return t('trace:window.basis.window1h');
          case '24h':
            return t('trace:window.basis.window24h');
          case '7d':
            return t('trace:window.basis.window7d');
          case '30d':
            return t('trace:window.basis.window30d');
          default:
            return t('trace:window.basis.unknown');
        }
      default:
        return t('trace:window.basis.unknown');
    }
  };
}

export function TraceWindowBar({ range, window, hintMs, onSearch }: TraceWindowBarProps) {
  const { t, i18n } = useTranslation(['common', 'trace']);
  const locale = i18n.resolvedLanguage ?? 'en';
  const basisText = useBasisText();
  const from = Date.parse(range.from);
  const to = Date.parse(range.to);
  return (
    <div
      className="trace-window"
      data-testid="trace-window"
      data-basis={range.basis}
      data-from={range.from}
      data-to={range.to}
    >
      <span className="trace-window__label">{t('trace:window.label')}</span>
      <span className="trace-window__basis" data-testid="trace-window-basis">
        {basisText(range.basis, window)}
      </span>
      <span className="trace-window__range" data-testid="trace-window-range">
        {Number.isFinite(from) ? formatDateTimeWithOffset(from, locale) : range.from} –{' '}
        {Number.isFinite(to) ? formatDateTimeWithOffset(to, locale) : range.to}
      </span>
      {hintMs === undefined ? null : (
        <span className="trace-window__hint" data-testid="trace-window-hint">
          {t('trace:window.hint')}: {formatDateTimeWithOffset(hintMs, locale)}
        </span>
      )}
      <WindowSearch current={window} onSearch={onSearch} />
    </div>
  );
}
