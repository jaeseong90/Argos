// SPDX-License-Identifier: Apache-2.0
//
// Actions of the transaction detail (05 §2.2-10, docs/15 §8.6, §9.3): back to the list and
// previous / next in its order (ADR-0010 C-206), the calling transaction (`caller.tx_id`, C-207
// (2)), the trace view, the log search link (`links.logs`, F-U18; only a configured template gives
// one, opened through SafeLink), the JSON export (the server's audited GET
// /transactions/{txId}/export, C-176 (1), saved under its Content-Disposition name) and the
// canonical link with `ts` to the clipboard. An action that cannot be offered stays visible,
// disabled, with its reason shown next to it (a disabled button takes no focus, so the reason is
// visible text, also referenced by aria-describedby).
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { isApiError } from '../../api/errors';
import { safeHref } from '../../components/safeHref';
import { SafeLink } from '../../components/SafeLink';
import type { TxDetailState } from '../../state/urlState';

import type { BackLink } from './data/listContext';
import {
  downloadTransactionExport,
  exportHref,
  type TransactionDetail,
} from './data/transactionApi';
import { callerHref, canonicalHref, type DetailLinks } from './model/links';
import { CopyButton } from './parts';
import type { AdjacentState, Neighbour } from './useAdjacent';

export interface DetailActionsProps {
  readonly detail: TransactionDetail;
  readonly tab: TxDetailState['tab'];
  readonly back: BackLink | null;
  readonly adjacent: AdjacentState;
  readonly links: DetailLinks;
  readonly onNavigate: (path: string) => void;
  /** Opens a neighbour of the list. */
  readonly onAdjacent: (neighbour: Neighbour) => void;
}

type ExportState =
  | { readonly status: 'idle' }
  | { readonly status: 'busy' }
  | { readonly status: 'done'; readonly fileName: string }
  | { readonly status: 'error'; readonly retryAfter: number | null; readonly rateLimited: boolean };

/** The reason text key of an unavailable previous/next. */
function adjacentReasonKey(adjacent: AdjacentState, which: 'previous' | 'next') {
  if (adjacent.status === 'pending') {
    return 'tx:actions.adjacent.pending' as const;
  }
  if (adjacent.status === 'unavailable') {
    switch (adjacent.reason) {
      case 'no_list':
        return 'tx:actions.adjacent.noList' as const;
      case 'not_xlog':
        return 'tx:actions.adjacent.notXlog' as const;
      case 'list_empty':
        return 'tx:actions.adjacent.listEmpty' as const;
      case 'not_in_list':
        return 'tx:actions.adjacent.notInList' as const;
      case 'list_error':
        return 'tx:actions.adjacent.listError' as const;
    }
  }
  return which === 'previous'
    ? ('tx:actions.adjacent.first' as const)
    : ('tx:actions.adjacent.last' as const);
}

export function DetailActions({
  detail,
  tab,
  back,
  adjacent,
  links,
  onNavigate,
  onAdjacent,
}: DetailActionsProps) {
  const { t } = useTranslation(['common', 'tx']);
  const [exportState, setExportState] = useState<ExportState>({ status: 'idle' });
  const previous = adjacent.status === 'ready' ? adjacent.previous : null;
  const next = adjacent.status === 'ready' ? adjacent.next : null;
  const adjacentNote =
    previous !== null && next !== null
      ? null
      : previous === null && next === null
        ? t(adjacentReasonKey(adjacent, 'previous'))
        : previous === null
          ? t(adjacentReasonKey(adjacent, 'previous'))
          : t(adjacentReasonKey(adjacent, 'next'));
  const caller = callerHref(detail.caller, links.context);
  const logs = detail.links.logs;
  const copyText = `${window.location.origin}${canonicalHref(detail.app.app_id, detail.tx_id, detail.ts, tab)}`;

  const runExport = (): void => {
    setExportState({ status: 'busy' });
    downloadTransactionExport(detail.tx_id, detail.ts).then(
      (fileName) => {
        setExportState({ status: 'done', fileName });
      },
      (error: unknown) => {
        const rateLimited = isApiError(error) && error.status === 429;
        setExportState({
          status: 'error',
          rateLimited,
          retryAfter: isApiError(error) ? error.retryAfterSeconds : null,
        });
      },
    );
  };

  return (
    <div
      className="tx-actions"
      role="toolbar"
      aria-label={t('tx:actions.label')}
      data-testid="transaction-actions"
    >
      <div className="tx-actions__group">
        {back === null ? null : (
          <SafeLink
            href={back.href}
            onNavigate={onNavigate}
            className="control-button"
            data-testid="transaction-back-to-list"
          >
            {t('tx:notFound.backToList')}
          </SafeLink>
        )}
        <button
          type="button"
          className="control-button"
          disabled={previous === null}
          aria-describedby={previous === null ? 'tx-adjacent-reason' : undefined}
          onClick={() => {
            if (previous !== null) {
              onAdjacent(previous);
            }
          }}
          data-testid="transaction-prev"
          data-target={previous?.txId ?? ''}
        >
          {t('tx:actions.previous')}
        </button>
        <button
          type="button"
          className="control-button"
          disabled={next === null}
          aria-describedby={next === null ? 'tx-adjacent-reason' : undefined}
          onClick={() => {
            if (next !== null) {
              onAdjacent(next);
            }
          }}
          data-testid="transaction-next"
          data-target={next?.txId ?? ''}
        >
          {t('tx:actions.next')}
        </button>
        {adjacent.status === 'ready' ? (
          <span className="tx-muted" data-testid="transaction-adjacent-position">
            {adjacent.total === null
              ? t('tx:actions.adjacent.position', { n: adjacent.index + 1 })
              : t('tx:actions.adjacent.positionOf', {
                  n: adjacent.index + 1,
                  total: adjacent.total,
                })}
          </span>
        ) : null}
        {adjacentNote === null ? null : (
          <span
            id="tx-adjacent-reason"
            className="tx-muted"
            data-testid="transaction-adjacent-reason"
            data-state={adjacent.status === 'unavailable' ? adjacent.reason : adjacent.status}
          >
            {adjacentNote}
          </span>
        )}
      </div>
      <div className="tx-actions__group">
        {caller === null ? (
          <>
            <button
              type="button"
              className="control-button"
              disabled
              aria-describedby="tx-caller-reason"
              data-testid="transaction-caller"
            >
              {t('tx:actions.caller')}
            </button>
            <span
              id="tx-caller-reason"
              className="tx-muted"
              data-testid="transaction-caller-reason"
            >
              {detail.caller === null
                ? detail.flags.incoming_trace
                  ? t('tx:actions.callerUnknown')
                  : t('tx:actions.callerNone')
                : t('tx:actions.callerNotFound')}
            </span>
          </>
        ) : (
          <SafeLink
            href={caller}
            onNavigate={onNavigate}
            className="control-button"
            data-testid="transaction-caller"
          >
            {t('tx:actions.caller')}
          </SafeLink>
        )}
        {links.trace === null ? null : (
          <SafeLink
            href={links.trace}
            onNavigate={onNavigate}
            className="control-button"
            data-testid="transaction-trace-link"
          >
            {t('tx:actions.trace')}
          </SafeLink>
        )}
        {logs === null ? (
          <>
            <button
              type="button"
              className="control-button"
              disabled
              aria-describedby="tx-logs-reason"
              data-testid="transaction-logs-unavailable"
            >
              {t('tx:actions.logs')}
            </button>
            <span id="tx-logs-reason" className="tx-muted" data-testid="transaction-logs-reason">
              {t('tx:actions.logsUnavailable')}
            </span>
          </>
        ) : (
          <SafeLink href={logs} className="control-button" data-testid="transaction-logs">
            {t('tx:actions.logs')}
          </SafeLink>
        )}
        <a
          className="control-button"
          href={safeHref(exportHref(detail.tx_id, detail.ts))}
          download
          aria-disabled={exportState.status === 'busy' ? true : undefined}
          onClick={(event) => {
            event.preventDefault();
            if (exportState.status !== 'busy') {
              runExport();
            }
          }}
          data-testid="transaction-export"
          data-state={exportState.status}
        >
          {t('tx:actions.export')}
        </a>
        <CopyButton
          text={copyText}
          label={t('tx:actions.copyLink')}
          data-testid="transaction-copy-link"
        />
      </div>
      <p
        className="tx-actions__status"
        role="status"
        aria-live="polite"
        data-testid="transaction-export-status"
      >
        {exportState.status === 'done'
          ? t('tx:actions.exported', { name: exportState.fileName })
          : exportState.status === 'error'
            ? exportState.rateLimited
              ? exportState.retryAfter === null
                ? t('common:component.error.rateLimitedNow')
                : t('common:component.error.rateLimited', { seconds: exportState.retryAfter })
              : t('tx:actions.exportFailed')
            : ''}
      </p>
    </div>
  );
}
