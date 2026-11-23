// SPDX-License-Identifier: Apache-2.0
//
// Actions of the selected running transaction (05 §2.6-4, docs/15 §9.6 RowActions, T-ACPT-02):
// this thread's stack (the thread_stack command with the transaction id; the modal follows it and
// says whether the thread still runs the transaction), the instance's thread dump (requested here,
// then shown in the instance detail's dumps tab, WO-37), the instance detail, and the recent
// completed transactions of the same service (the XLog with the service key filter, the last 15
// minutes and the transaction's kind; the environment and live keys carried, docs/15 §5). The two
// commands need the operator role (SEC-21): viewers see them disabled with the reason.
import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';

import type { ActiveItem } from '../../api/active';
import { untrusted } from '../../api/untrusted';
import { Hint } from '../../components/Hint';
import { SafeText } from '../../components/SafeText';
import { useFormat } from '../../i18n/format';
import { useCan } from '../../state/permissions';
import {
  buildUrl,
  carriedState,
  defaultState,
  InstanceQ,
  KINDS,
  XLogQ,
  type CommonState,
  type Kind,
} from '../../state/urlState';
import { CommandFeedback } from '../instances/commands/CommandFeedback';
import { dumpTabHref } from '../instances/commands/links';
import { useCommandRun } from '../instances/commands/useCommands';
import { ThreadStackModal } from '../instances/dumps/ThreadStackModal';

/** The instance detail (WO-37) of an instance, keeping the carried keys (docs/15 §5). */
export function instanceHref(
  appId: number,
  instanceId: number,
  current: Partial<CommonState>,
): string {
  return buildUrl(`/apps/${String(appId)}/instances/${String(instanceId)}`, InstanceQ, {
    ...defaultState(InstanceQ),
    ...carriedState(current),
  });
}

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
  const { t } = useTranslation(['common', 'active', 'commands']);
  const format = useFormat();
  const navigate = useNavigate();
  const reasonId = useId();
  const noServiceId = useId();
  const canStack = useCan('commands.thread-stack');
  const canDump = useCan('commands.thread-dump');
  const runner = useCommandRun(item.instance_id);
  const [stack, setStack] = useState<{
    readonly txId: string;
    readonly commandId: string | null;
  } | null>(null);
  const [dumpRequested, setDumpRequested] = useState(false);
  const reason = t('active:actions.operatorRequired');
  const recent = recentCompletionsHref(appId, item, current);
  const busy = runner.requesting || runner.inFlight;

  const requestStack = async (): Promise<void> => {
    setDumpRequested(false);
    const txId = item.tx_id;
    setStack({ txId, commandId: null });
    const id = await runner.run('thread_stack', { tx_id: txId });
    setStack((open) => (open === null ? null : { txId, commandId: id }));
  };
  const requestDump = async (): Promise<void> => {
    setStack(null);
    setDumpRequested(true);
    const id = await runner.run('thread_dump');
    if (id !== null) {
      void navigate(dumpTabHref(appId, item.instance_id, id, current));
    }
  };

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
      {canStack ? (
        <button
          type="button"
          className="control-button"
          disabled={busy}
          title={t('active:actions.stackHint')}
          onClick={() => {
            void requestStack();
          }}
          data-testid="active-action-stack"
        >
          {t('active:actions.stack')}
        </button>
      ) : (
        <UnavailableAction
          label={t('active:actions.stack')}
          reason={reason}
          reasonId={reasonId}
          testId="active-action-stack"
        />
      )}
      {canDump ? (
        <button
          type="button"
          className="control-button"
          disabled={busy}
          title={t('active:actions.dumpHint')}
          onClick={() => {
            void requestDump();
          }}
          data-testid="active-action-dump"
        >
          {t('active:actions.dump')}
        </button>
      ) : (
        <UnavailableAction
          label={t('active:actions.dump')}
          reason={reason}
          reasonId={reasonId}
          testId="active-action-dump"
        />
      )}
      <Link
        to={instanceHref(appId, item.instance_id, current)}
        className="control-button"
        data-testid="active-action-instance"
      >
        {t('active:actions.instance')}
      </Link>
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
      {dumpRequested && (runner.requesting || runner.requestError !== null) ? (
        <CommandFeedback
          view={undefined}
          requestError={runner.requestError}
          pollError={null}
          requesting={runner.requesting}
          data-testid="active-dump-feedback"
        />
      ) : null}
      <ThreadStackModal
        appId={appId}
        open={stack !== null}
        commandId={stack?.commandId ?? null}
        txId={stack?.txId ?? null}
        requestError={runner.requestError}
        requesting={runner.requesting}
        onClose={() => {
          setStack(null);
        }}
      />
    </div>
  );
}
