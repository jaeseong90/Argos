// SPDX-License-Identifier: Apache-2.0
//
// Polite announcement of a new XLog selection (docs/15 §13, WO-04 C-12, docs/poc/xlog-ui.md 8):
// a live region that exists from the first render of the page, so that the first selection is
// announced too (a region inserted together with its text is often not read). It says how many
// points the selection holds once the summary has counted them; the summary itself shows the rest.
import { useTranslation } from 'react-i18next';

export interface SelectionAnnouncerProps {
  /** Points of the current selection; null without a selection or before it is counted. */
  readonly count: number | null;
}

export function SelectionAnnouncer({ count }: SelectionAnnouncerProps) {
  const { t } = useTranslation(['common', 'xlog']);
  return (
    <p
      className="visually-hidden"
      aria-live="polite"
      aria-atomic="true"
      data-testid="xlog-selection-announcer"
    >
      {count === null ? '' : t('xlog:a11y.selected', { count })}
    </p>
  );
}
