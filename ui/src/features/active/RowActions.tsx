// SPDX-License-Identifier: Apache-2.0
//
// Actions of the selected running transaction (05 §2.6-4, docs/15 §9.6 RowActions): this thread's
// stack (single-thread stack command), the instance's thread dump and the recent completed
// transactions of the same service. The first two need the remote command API, which this version
// does not have yet (server WO-26, agent WO-17, dump viewer WO-37): they show disabled with the
// reason. The third opens the XLog with the service key filter, the last 15 minutes and the
// transaction's kind (the environment and live keys carried, docs/15 §5).
import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

import type { ActiveItem } from '../../api/active';
import { untrusted } from '../../api/untrusted';
import { Hint } from '../../components/Hint';
import { SafeText } from '../../components/SafeText';
import { useFormat } from '../../i18n/format';
import {
  buildUrl,
  carriedState,
  defaultState,
  KINDS,
  XLogQ,
  type CommonState,
  type Kind,
} from '../../state/urlState';

/** XLog link of "recent completions of the same service", or null without a service key. */
export function recentCompletionsHref(
  appId: number,
  item: ActiveItem,
  current: Partial<CommonState>,
): string | null {
  if (item.service_id === null) {
    return null;
  }
  const kind: Kind = (KINDS as readonly string[]).includes(item.kind) ? (item.kind as Kind) : 'ALL';
  return buildUrl(`/apps/${String(appId)}/xlog`, XLogQ, {
    ...defaultState(XLogQ),
    ...carriedState(current),
    time: { kind: 'relative', range: '15m' },
    kind,
    // The XLog filter vocabulary (api/transactionSearch.ts): f.service=<service key>.
    filters: [{ field: 'service', op: 'eq', value: untrusted(item.service_id) }],
  });
}

/**
 * An action this version cannot run: a focusable button marked aria-disabled (a `disabled` button
 * gets no focus and no tooltip), with the reason as tooltip and accessible description. A click
 * does nothing.
 */
function UnavailableAction({
  label,
  reason,
  reasonId,
  testId,
}: {
  readonly label: string;
  readonly reason: string;
  readonly reasonId: string;
  readonly testId: string;
}) {
  return (
    <Hint title={reason}>
      <button
        type="button"
        className="control-button active-actions__unavailable"
        aria-disabled="true"
        aria-describedby={reasonId}
        data-reason={reason}
        onClick={(event) => {
          event.preventDefault();
        }}
        data-testid={testId}
      >
        {label}
      </button>
    </Hint>
  );
}

export interface RowActionsProps {
  readonly appId: number;
  readonly item: ActiveItem;
  /** False when the selected transaction is no longer in the latest snapshot. */
  readonly running: boolean;
  readonly current: Partial<CommonState>;
  readonly onClear: () => void;
}

export function RowActions({ appId, item, running, current, onClear }: RowActionsProps) {
  const { t } = useTranslation(['common', 'active']);
  const format = useFormat();
  const reasonId = useId();
  const noServiceId = useId();
  const reason = t('active:actions.commandsUnavailable');
  const recent = recentCompletionsHref(appId, item, current);
  return (
    <div
      className="active-actions"
      role="toolbar"
      aria-label={t('active:actions.label')}
      data-testid="active-row-actions"
      data-tx-id={item.tx_id}
      data-running={String(running)}
    >
      <span className="active-actions__selected">
        <span className="active-actions__caption">{t('active:actions.selected')}</span>
        <SafeText value={item.service} />
        <span className="active-muted">{format.duration(item.elapsed_ms)}</span>
        {running ? null : (
          <span className="active-muted" data-testid="active-row-finished">
            {t('active:actions.finished')}
          </span>
        )}
      </span>
      <span id={reasonId} className="visually-hidden">
        {reason}
      </span>
      <span id={noServiceId} className="visually-hidden">
        {t('active:actions.noService')}
      </span>
      {/* FIXME(WO-26, WO-17): single-thread stack command of this transaction's thread (05 §2.6-4,
          T-ACPT-02, T-E2E-active-30); enable once the remote command API exists. */}
      <UnavailableAction
        label={t('active:actions.stack')}
        reason={reason}
        reasonId={reasonId}
        testId="active-action-stack"
      />
      {/* FIXME(WO-26, WO-17, WO-37): thread dump of this instance and the dump viewer (05 §2.6-4);
          enable once the remote command API and the viewer exist. */}
      <UnavailableAction
        label={t('active:actions.dump')}
        reason={reason}
        reasonId={reasonId}
        testId="active-action-dump"
      />
      {recent === null ? (
        <UnavailableAction
          label={t('active:actions.recent')}
          reason={t('active:actions.noService')}
          reasonId={noServiceId}
          testId="active-action-recent"
        />
      ) : (
        <Link to={recent} className="control-button" data-testid="active-action-recent">
          {t('active:actions.recent')}
        </Link>
      )}
      <button
        type="button"
        className="control-button"
        onClick={onClear}
        data-testid="active-actions-clear"
      >
        {t('active:actions.clear')}
      </button>
    </div>
  );
}
