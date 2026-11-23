// SPDX-License-Identifier: Apache-2.0
//
// Actions of the trace view (05 §2.8-5, docs/15 §9.5): copy the trace id, open logs (F-U18: only
// when a log link of the root transaction is known; the trace response has none, so it is the
// server-built `links.logs` of the root's detail when that detail is already loaded in this
// session; otherwise the action stays visible, disabled, with its reason shown next to it), export
// the trace as JSON (ADR-0010 C-207 (1): the response this page loaded, saved through saveFile; no
// new request), and query again (refetch; late callees appear).
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { SafeLink } from '../../../components/SafeLink';

export interface TraceActionsProps {
  readonly traceId: string;
  /** The root transaction's server-built log link, or null (disabled, with the reason). */
  readonly logsHref: string | null;
  /** Saves the loaded response; returns the file name. */
  readonly onExport: () => string;
  readonly onQueryAgain: () => void;
  readonly fetching: boolean;
}

const FEEDBACK_MS = 4000;

type Feedback =
  | { readonly kind: 'copied' }
  | { readonly kind: 'copyFailed' }
  | { readonly kind: 'exported'; readonly file: string }
  | null;

export function TraceActions({
  traceId,
  logsHref,
  onExport,
  onQueryAgain,
  fetching,
}: TraceActionsProps) {
  const { t } = useTranslation(['common', 'trace']);
  const [feedback, setFeedback] = useState<Feedback>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(
    () => () => {
      if (timer.current !== undefined) {
        clearTimeout(timer.current);
      }
    },
    [],
  );
  const show = (next: Feedback): void => {
    if (timer.current !== undefined) {
      clearTimeout(timer.current);
    }
    setFeedback(next);
    timer.current = setTimeout(() => {
      setFeedback(null);
    }, FEEDBACK_MS);
  };

  const copy = async (): Promise<void> => {
    try {
      const clipboard = typeof navigator === 'undefined' ? undefined : navigator.clipboard;
      if (clipboard === undefined) {
        throw new Error('Clipboard API unavailable');
      }
      await clipboard.writeText(traceId);
      show({ kind: 'copied' });
    } catch {
      show({ kind: 'copyFailed' });
    }
  };

  return (
    <div
      className="trace-actions"
      role="group"
      aria-label={t('trace:actions.label')}
      data-testid="trace-actions"
    >
      <button
        type="button"
        className="control-button"
        onClick={() => {
          void copy();
        }}
        data-testid="trace-copy-id"
      >
        {t('trace:actions.copyId')}
      </button>
      {logsHref === null ? (
        <>
          <button
            type="button"
            className="control-button"
            disabled
            aria-describedby="trace-open-logs-reason"
            data-testid="trace-open-logs-unavailable"
          >
            {t('trace:actions.openLogs')}
          </button>
          <span
            id="trace-open-logs-reason"
            className="trace-actions__status"
            data-testid="trace-open-logs-reason"
          >
            {t('trace:actions.openLogsUnavailable')}
          </span>
        </>
      ) : (
        <SafeLink href={logsHref} className="control-button" data-testid="trace-open-logs">
          {t('trace:actions.openLogs')}
        </SafeLink>
      )}
      <button
        type="button"
        className="control-button"
        title={t('trace:actions.exportJsonHint')}
        onClick={() => {
          show({ kind: 'exported', file: onExport() });
        }}
        data-testid="trace-export-json"
      >
        {t('trace:actions.exportJson')}
      </button>
      <button
        type="button"
        className="control-button"
        disabled={fetching}
        onClick={onQueryAgain}
        data-testid="trace-refetch"
      >
        {t('trace:actions.queryAgain')}
      </button>
      <span className="trace-actions__status" role="status" data-testid="trace-action-status">
        {feedback === null
          ? null
          : feedback.kind === 'copied'
            ? t('trace:actions.copied')
            : feedback.kind === 'copyFailed'
              ? t('trace:actions.copyFailed')
              : t('trace:actions.exported', { file: feedback.file })}
      </span>
    </div>
  );
}
