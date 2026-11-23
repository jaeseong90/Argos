// SPDX-License-Identifier: Apache-2.0
//
// Commands tab of the instance detail (05 §2.7-4, docs/15 §9.8, docs/16 §5, WO-37): the buttons a
// role may use (operator: thread dump, single-thread stack, self diagnosis; administrator: also
// configuration reload and environment dump, SEC-21), each behind a confirmation that says what it
// does; the state of the running command (polled every second, ADR-0010 C-231 (3)) with the end
// reason; and the history of the instance's commands (type, requester, requested at, duration,
// result size, status and reason, truncation, the result). The history is the instance's command
// audit trail (every request, result and view is also an audit record on the server; the audit
// screen with its filter is WO-28/WO-39, linked for administrators).
//
// Results are readable by their requester and administrators only (SEC-50): other rows say so.
// A thread dump opens in the dumps tab; other results open below the history.
import { useId, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

import {
  ADMIN_COMMANDS,
  isCommandType,
  isTerminal,
  OPERATOR_COMMANDS,
  requesterName,
  useCommandResult,
  useInstanceCommands,
  type CommandInstance,
  type CommandType,
  type CommandView,
} from '../../../api/commands';
import { errorInfo } from '../../../api/errors';
import { CodeBlock } from '../../../components/CodeBlock';
import { DataTable, type DataTableColumn } from '../../../components/DataTable';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';
import { SafeText } from '../../../components/SafeText';
import { useFormat } from '../../../i18n/format';
import { InstanceQ } from '../../../state/urlState';
import { useUrlState } from '../../../state/useUrlState';
import { MISSING_DASH } from '../../../utils/missing';
import { ThreadStackView } from '../dumps/ThreadStackModal';

import { CommandFeedback, commandStatusText } from './CommandFeedback';
import { auditHref, dumpTabHref } from './links';
import { EnvDumpView, SelfDiagnosisView } from './ResultViews';
import { RunCommandButton } from './RunCommandButton';
import { useCommandAccess, useCommandRun } from './useCommands';

import './commands.css';

export interface CommandsTabProps {
  readonly appId: number;
  readonly instance: CommandInstance;
}

/** History refresh while a command of this tab runs. */
const HISTORY_REFRESH_MS = 2_000;

function typeLabelKey(type: string): `commands:type.${CommandType | 'unknown'}` {
  return isCommandType(type) ? `commands:type.${type}` : 'commands:type.unknown';
}

export function CommandsTab({ appId, instance }: CommandsTabProps) {
  const { t } = useTranslation(['common', 'commands']);
  const format = useFormat();
  const headingId = useId();
  const historyId = useId();
  const access = useCommandAccess();
  const instanceId = instance.instance_id;
  const runner = useCommandRun(instanceId);
  const { state: urlState } = useUrlState(InstanceQ);
  const [opened, setOpened] = useState<CommandView | null>(null);
  const history = useInstanceCommands(instanceId, undefined, {
    enabled: access.canList,
    refetchMs: runner.inFlight ? HISTORY_REFRESH_MS : false,
  });
  const allowedTypes = useMemo(
    () => [...OPERATOR_COMMANDS, ...ADMIN_COMMANDS].filter((type) => access.canRun(type)),
    [access],
  );
  const rows = history.data ?? [];

  const columns: DataTableColumn<CommandView>[] = [
    {
      id: 'type',
      header: t('commands:history.type'),
      value: (row) => t(typeLabelKey(row.type)),
      width: 150,
    },
    {
      id: 'requester',
      header: t('commands:history.requester'),
      value: (row) => requesterName(row),
      width: 120,
      render: (row) => {
        const name = requesterName(row);
        return name === null ? (
          <span className="cmd-muted" title={t('commands:history.requesterUnknown')}>
            {MISSING_DASH}
          </span>
        ) : (
          <SafeText value={name} data-testid={`command-requester-${row.command_id}`} />
        );
      },
    },
    {
      id: 'requested_at',
      header: t('commands:history.requestedAt'),
      value: (row) => Date.parse(row.requested_at),
      width: 170,
      render: (row) => {
        const ms = Date.parse(row.requested_at);
        return (
          <time dateTime={row.requested_at} title={format.dateTimeWithOffset(ms)}>
            {format.dateTime(ms)}
          </time>
        );
      },
    },
    {
      id: 'duration_ms',
      header: t('commands:history.duration'),
      value: (row) => row.duration_ms,
      kind: 'ms',
      width: 90,
    },
    {
      id: 'result_size',
      header: t('commands:history.size'),
      value: (row) => row.result_size,
      kind: 'bytes',
      width: 90,
    },
    {
      id: 'status',
      header: t('commands:history.status'),
      value: (row) => commandStatusText(t, row),
      width: 190,
      render: (row) => (
        <span
          className={`cmd-status cmd-status--${row.status}`}
          data-testid={`command-status-${row.command_id}`}
          data-status={row.status}
          data-reason={row.error ?? undefined}
        >
          {commandStatusText(t, row)}
          {row.truncated === true ? (
            <span
              className="cmd-badge cmd-badge--warn"
              title={t('commands:history.truncatedTitle')}
            >
              {t('commands:history.truncated')}
            </span>
          ) : null}
        </span>
      ),
    },
    {
      id: 'result',
      header: t('commands:history.result'),
      value: () => null,
      sortable: false,
      width: 150,
      render: (row) => {
        if (row.status !== 'done') {
          return <span className="cmd-muted">{MISSING_DASH}</span>;
        }
        if (!access.canRead(row)) {
          return (
            <span className="cmd-muted" data-testid={`command-unreadable-${row.command_id}`}>
              {t('commands:history.notYours')}
            </span>
          );
        }
        if (row.type === 'thread_dump') {
          return (
            <Link
              to={dumpTabHref(appId, instanceId, row.command_id, urlState)}
              data-testid={`command-open-${row.command_id}`}
            >
              {t('commands:history.openDump')}
            </Link>
          );
        }
        return (
          <button
            type="button"
            className="cmd-link-button"
            onClick={(event) => {
              event.stopPropagation();
              setOpened(row);
            }}
            data-testid={`command-open-${row.command_id}`}
          >
            {t('commands:history.open')}
          </button>
        );
      },
    },
  ];

  const lastView = runner.view;
  const lastDone = lastView !== undefined && lastView.status === 'done' ? lastView : null;

  return (
    <div className="cmd-tab" data-testid="instance-commands" data-instance-id={instanceId}>
      <section className="cmd-panel" aria-labelledby={headingId} data-testid="command-buttons">
        <h2 id={headingId} className="cmd-panel__title">
          {t('commands:tab.run')}
        </h2>
        {allowedTypes.length === 0 ? (
          <p className="cmd-notice" data-testid="command-buttons-forbidden">
            {t('commands:tab.viewerNotice')}
          </p>
        ) : (
          <>
            <div className="cmd-buttons" role="group" aria-label={t('commands:tab.run')}>
              {allowedTypes.map((type) => (
                <RunCommandButton key={type} type={type} runner={runner} />
              ))}
            </div>
            {instance.state !== 'UP' && instance.state !== 'WARN' ? (
              <p className="cmd-muted" data-testid="command-offline-hint">
                {t('commands:tab.offlineHint')}
              </p>
            ) : null}
            <p className="cmd-muted">{t('commands:tab.limits')}</p>
          </>
        )}
        {runner.commandId === null && runner.requestError === null && !runner.requesting ? null : (
          <div className="cmd-run">
            <CommandFeedback
              view={runner.view}
              requestError={runner.requestError}
              pollError={runner.pollError}
              requesting={runner.requesting}
            />
            {lastDone !== null && lastDone.type === 'thread_dump' && access.canRead(lastDone) ? (
              <Link
                to={dumpTabHref(appId, instanceId, lastDone.command_id, urlState)}
                data-testid="command-run-open-dump"
              >
                {t('commands:history.openDump')}
              </Link>
            ) : null}
            {lastDone !== null && lastDone.type !== 'thread_dump' && access.canRead(lastDone) ? (
              <button
                type="button"
                className="cmd-link-button"
                onClick={() => {
                  setOpened(lastDone);
                }}
                data-testid="command-run-open-result"
              >
                {t('commands:history.open')}
              </button>
            ) : null}
          </div>
        )}
      </section>

      <section className="cmd-panel" aria-labelledby={historyId} data-testid="command-history">
        <div className="cmd-panel__head">
          <h2 id={historyId} className="cmd-panel__title">
            {t('commands:history.title')}
          </h2>
          {access.isAdmin ? (
            <Link to={auditHref(instanceId)} data-testid="command-audit-link">
              {t('commands:history.auditLink')}
            </Link>
          ) : null}
        </div>
        <p className="cmd-muted">{t('commands:history.auditNote')}</p>
        {!access.canList ? (
          <p className="cmd-notice" data-testid="command-history-forbidden">
            {t('commands:history.operatorOnly')}
          </p>
        ) : history.isPending ? (
          <LoadingState variant="table" rows={4} slowHint="wait" />
        ) : history.isError ? (
          <ErrorState
            compact
            error={errorInfo(history.error)}
            onRetry={() => {
              void history.refetch();
            }}
          />
        ) : (
          <DataTable<CommandView>
            tableId="instance-commands"
            caption={t('commands:history.caption')}
            columns={columns}
            rows={rows}
            rowKey={(row) => row.command_id}
            dataComplete
            density="dense"
            maxHeight={360}
            onRowOpen={(row) => {
              if (row.status === 'done' && access.canRead(row) && row.type !== 'thread_dump') {
                setOpened(row);
              }
            }}
            empty={{
              title: t('commands:history.empty'),
              description: t('commands:history.emptyHint'),
            }}
            rowAttributes={(row) => ({
              'data-testid': `command-row-${row.command_id}`,
              'data-type': row.type,
              'data-status': row.status,
              'data-running': String(!isTerminal(row.status)),
            })}
          />
        )}
      </section>

      {opened === null ? null : (
        <OpenedResult
          view={opened}
          onClose={() => {
            setOpened(null);
          }}
        />
      )}
    </div>
  );
}

/** A done command's result below the history (self diagnosis, env dump, stack, reload text). */
function OpenedResult({
  view,
  onClose,
}: {
  readonly view: CommandView;
  readonly onClose: () => void;
}) {
  const { t } = useTranslation(['common', 'commands']);
  const headingId = useId();
  const result = useCommandResult(view.command_id, 'json');
  let body;
  if (result.isPending) {
    body = <LoadingState variant="block" slowHint="wait" />;
  } else if (result.isError) {
    body = (
      <ErrorState
        compact
        error={errorInfo(result.error)}
        onRetry={() => {
          void result.refetch();
        }}
      />
    );
  } else if (view.type === 'self_diagnosis') {
    body = <SelfDiagnosisView result={result.data} />;
  } else if (view.type === 'env_dump') {
    body = <EnvDumpView result={result.data} />;
  } else if (view.type === 'thread_stack') {
    body = <ThreadStackView result={result.data} txId={view.args.tx_id ?? null} appId={null} />;
  } else {
    body = (
      <CodeBlock value={result.data.text} language="text" label={t(typeLabelKey(view.type))} />
    );
  }
  return (
    <section
      className="cmd-panel"
      aria-labelledby={headingId}
      data-testid="command-result"
      data-command-id={view.command_id}
      data-type={view.type}
      data-state={result.isPending ? 'loading' : result.isError ? 'error' : 'ready'}
    >
      <div className="cmd-panel__head">
        <h2 id={headingId} className="cmd-panel__title">
          {t('commands:result.title', { command: t(typeLabelKey(view.type)) })}
        </h2>
        <button
          type="button"
          className="control-button"
          onClick={onClose}
          data-testid="command-result-close"
        >
          {t('commands:result.close')}
        </button>
      </div>
      {result.data?.truncated === true ? (
        <p className="cmd-notice" data-testid="command-result-truncated">
          {t('commands:result.truncated')}
        </p>
      ) : null}
      {body}
    </section>
  );
}
