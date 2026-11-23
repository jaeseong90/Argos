// SPDX-License-Identifier: Apache-2.0
//
// Notices under the top bar: the url-restore-notice after a link with invalid items (shown once,
// docs/15 §4.2, SEC-64), the refusal to write a URL over 2 KB (docs/14 §6) and the applied
// filters as chips with individual removal (docs/15 §4.3 deep link 3, 05 §2.0-4).
import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation } from 'react-router';

import { FilterChipBar } from '../components/FilterChipBar';
import { toUrlFilter } from '../state/filters';
import { useUiStore } from '../state/uiStore';
import type { Filter } from '../state/urlState';
import { useUrlState } from '../state/useUrlState';

import { useFilterValueLabel } from './filterLabels';

export function UrlNotices() {
  const { t } = useTranslation(['common', 'shell']);
  const { pathname } = useLocation();
  const notice = useUiStore((s) => s.restoreNotice);
  const restorePath = useUiStore((s) => s.restorePath);
  const dismissRestore = useUiStore((s) => s.dismissRestoreNotice);
  const tooLong = useUiStore((s) => s.urlTooLong);
  const setTooLong = useUiStore((s) => s.setUrlTooLong);
  // The notice belongs to the link that was opened: it ends when the user moves to another screen.
  const stale = notice !== null && restorePath !== null && restorePath !== pathname;
  useEffect(() => {
    if (stale) {
      dismissRestore();
    }
  }, [stale, dismissRestore]);
  const restore = stale ? null : notice;
  if (restore === null && !tooLong) {
    return null;
  }
  return (
    <div className="shell-notices">
      {restore !== null && (
        <div className="shell-notice" role="status" data-testid="url-restore-notice">
          <span>{t('shell:notice.urlRestore')}</span>
          <button type="button" className="shell-notice__dismiss" onClick={dismissRestore}>
            {t('shell:notice.dismiss')}
          </button>
        </div>
      )}
      {tooLong && (
        <div
          className="shell-notice shell-notice--warning"
          role="alert"
          data-testid="url-too-long-notice"
        >
          <span>{t('shell:notice.urlTooLong')}</span>
          <button
            type="button"
            className="shell-notice__dismiss"
            onClick={() => {
              setTooLong(false);
            }}
          >
            {t('shell:notice.dismiss')}
          </button>
        </div>
      )}
    </div>
  );
}

/**
 * Applied `f.*` filters as chips (FilterChipBar); removing one rewrites the URL (push). Keys are
 * named when the screen registered a resolver (filterLabels.tsx).
 */
export function UrlFilterChips() {
  const { state, set } = useUrlState();
  const valueLabel = useFilterValueLabel();
  const filters = state.filters;
  if (filters.length === 0) {
    return null;
  }
  const write = (next: readonly Filter[]): void => {
    set({ filters: next }, { replace: false });
  };
  return (
    <FilterChipBar
      filters={filters}
      valueLabel={valueLabel ?? undefined}
      onRemove={(index) => {
        write(filters.filter((_, i) => i !== index));
      }}
      onClear={() => {
        write([]);
      }}
      onEdit={(index, spec) => {
        const next = toUrlFilter(spec);
        if (next !== null) {
          write(filters.map((filter, i) => (i === index ? next : filter)));
        }
      }}
    />
  );
}
