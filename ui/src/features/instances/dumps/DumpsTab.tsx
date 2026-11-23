// SPDX-License-Identifier: Apache-2.0
//
// Thread dumps tab of the instance detail (05 §2.7-5, docs/15 §9.8, WO-37): the last 20 done thread
// dumps of the instance (from its command history; another user's dump is listed but disabled
// with the reason, SEC-50), a button to take a new one (operator), and the viewer of the dump in
// the URL (`cmd`; `q` is the viewer's search). A dump that is still running (just requested here
// or from the active transactions screen) is followed every second until it is done. The download
// is the dump's text (GET result?format=text), saved by the browser as a .txt file named after the
// instance id and the time only. Comparing two dumps is a later version (the slot is shown).
import { useQueryClient } from '@tanstack/react-query';
import { useCallback, useId, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';

import {
  commandKeys,
  getCommandResult,
  requesterName,
  useCommand,
  useCommandResult,
  useInstanceCommands,
  type CommandInstance,
  type CommandResultView,
  type CommandView,
} from '../../../api/commands';
import { errorInfo } from '../../../api/errors';
import { saveFile } from '../../../api/exportCsv';
import { untrusted } from '../../../api/untrusted';
import { EmptyState } from '../../../components/EmptyState';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';
import { SafeText } from '../../../components/SafeText';
import { useFormat } from '../../../i18n/format';
import { InstanceQ } from '../../../state/urlState';
import { useUrlState } from '../../../state/useUrlState';
import { CommandFeedback, commandErrorText } from '../commands/CommandFeedback';
import { RunCommandButton } from '../commands/RunCommandButton';
import { useCommandAccess, useCommandRun } from '../commands/useCommands';

import { dumpFileName } from './links';
import { ThreadDumpViewer, type DumpSearch } from './ThreadDumpViewer';

import './dumps.css';

export interface DumpsTabProps {
  readonly appId: number;
  readonly instance: CommandInstance;
}

/** Dumps kept in the list (05 §2.7-5 "최근 20개 보관"). */
export const RECENT_DUMPS = 20;

/** The last done thread dumps of a command list (newest first). */
export function recentDumps(list: readonly CommandView[]): CommandView[] {
  return list
    .filter((view) => view.type === 'thread_dump' && view.status === 'done')
    .slice(0, RECENT_DUMPS);
}

/** Saves a dump's text as a .txt download (no server HTML, nothing rendered). */
export function saveDumpText(text: string, instanceId: number, at: string | null): string {
  const name = dumpFileName(instanceId, at);
  saveFile(new Blob([text], { type: 'text/plain;charset=utf-8' }), name);
  return name;
}

export function DumpsTab({ appId, instance }: DumpsTabProps) {
  const { t } = useTranslation(['common', 'commands']);
  const access = useCommandAccess();
  const instanceId = instance.instance_id;
  const { state, set } = useUrlState(InstanceQ);
  const runner = useCommandRun(instanceId);
  const history = useInstanceCommands(instanceId, undefined, {
    enabled: access.canList,
    refetchMs: runner.inFlight ? 2_000 : false,
  });
  const dumps = useMemo(() => recentDumps(history.data ?? []), [history.data]);
  const firstReadable = dumps.find((view) => access.canRead(view)) ?? null;
  const selected = state.cmd ?? firstReadable?.command_id ?? null;

  const select = useCallback(
    (commandId: string) => {
      set({ cmd: commandId, q: undefined }, { replace: false });
    },
    [set],
  );

  if (!access.canList) {
    return (
      <div
        className="dump-tab"
        data-testid="instance-dumps"
        data-instance-id={instanceId}
        data-state="forbidden"
      >
        <p className="cmd-notice" data-testid="dumps-forbidden">
          {t('commands:dumps.operatorOnly')}
        </p>
      </div>
    );
  }

  return (
    <div className="dump-tab" data-testid="instance-dumps" data-instance-id={instanceId}>
      <div className="dump-tab__bar">
        {access.canRun('thread_dump') ? (
          <RunCommandButton
            type="thread_dump"
            runner={runner}
            label={t('commands:dumps.take')}
            onQueued={select}
          />
        ) : null}
        <button
          type="button"
          className="control-button"
          aria-disabled="true"
          title={t('commands:dumps.compareLater')}
          onClick={(event) => {
            event.preventDefault();
          }}
          data-testid="dumps-compare"
        >
          {t('commands:dumps.compare')}
        </button>
        <span className="cmd-muted">{t('commands:dumps.compareLater')}</span>
      </div>
      {runner.requestError === null ? null : (
        <CommandFeedback
          view={undefined}
          requestError={runner.requestError}
          pollError={null}
          requesting={false}
          data-testid="dumps-request-feedback"
        />
      )}
      <div className="dump-tab__layout">
        <DumpList
          history={history}
          dumps={dumps}
          selected={selected}
          onSelect={select}
          canRead={access.canRead}
        />
        <div className="dump-tab__main">
          {selected === null ? (
            history.isPending ? null : (
              <EmptyState
                compact
                title={t('commands:dumps.emptyTitle')}
                description={t('commands:dumps.emptyHint')}
                data-testid="dumps-empty"
              />
            )
          ) : (
            <SelectedDump
              key={selected}
              appId={appId}
              instanceId={instanceId}
              commandId={selected}
              search={state}
              onQueryChange={(q) => {
                set({ q: q.trim() === '' ? undefined : untrusted(q) }, { replace: true });
              }}
            />
          )}
        </div>
      </div>
    </div>
  );
}

function DumpList({
  history,
  dumps,
  selected,
  onSelect,
  canRead,
}: {
  readonly history: ReturnType<typeof useInstanceCommands>;
  readonly dumps: readonly CommandView[];
  readonly selected: string | null;
  readonly onSelect: (commandId: string) => void;
  readonly canRead: (view: CommandView) => boolean;
}) {
  const { t } = useTranslation(['common', 'commands']);
  const format = useFormat();
  const headingId = useId();
  let body;
  if (history.isPending) {
    body = <LoadingState variant="table" rows={4} slowHint="wait" />;
  } else if (history.isError) {
    body = (
      <ErrorState
        compact
        error={errorInfo(history.error)}
        onRetry={() => {
          void history.refetch();
        }}
      />
    );
  } else if (dumps.length === 0) {
    body = (
      <p className="cmd-muted" data-testid="dumps-list-empty">
        {t('commands:dumps.listEmpty')}
      </p>
    );
  } else {
    body = (
      <ul className="dump-list" data-testid="dumps-list" data-count={dumps.length}>
        {dumps.map((view) => {
          const readable = canRead(view);
          const at = view.finished_at ?? view.requested_at;
          const ms = Date.parse(at);
          const name = requesterName(view);
          const current = view.command_id === selected;
          return (
            <li key={view.command_id}>
              <button
                type="button"
                className={`dump-list__item${current ? ' dump-list__item--current' : ''}`}
                aria-current={current ? 'true' : undefined}
                aria-disabled={readable ? undefined : 'true'}
                title={readable ? undefined : t('commands:dumps.notYours')}
                onClick={() => {
                  if (readable) {
                    onSelect(view.command_id);
                  }
                }}
                data-testid={`dumps-item-${view.command_id}`}
                data-readable={String(readable)}
                data-truncated={String(view.truncated === true)}
              >
                <time
                  dateTime={at}
                  title={Number.isNaN(ms) ? undefined : format.dateTimeWithOffset(ms)}
                >
                  {Number.isNaN(ms) ? at : format.dateTime(ms)}
                </time>
                <span className="dump-list__meta">
                  {name === null ? null : <SafeText value={name} />}
                  {view.result_size === null ? null : (
                    <span className="cmd-muted">{format.bytes(view.result_size)}</span>
                  )}
                  {view.truncated === true ? (
                    <span className="cmd-badge cmd-badge--warn">
                      {t('commands:history.truncated')}
                    </span>
                  ) : null}
                </span>
                {readable ? null : (
                  <span className="cmd-muted">{t('commands:dumps.notYours')}</span>
                )}
              </button>
            </li>
          );
        })}
      </ul>
    );
  }
  return (
    <nav className="dump-tab__list" aria-labelledby={headingId}>
      <h3 id={headingId} className="dump-panel__title">
        {t('commands:dumps.recent', { count: RECENT_DUMPS })}
      </h3>
      {body}
    </nav>
  );
}

function SelectedDump({
  appId,
  instanceId,
  commandId,
  search,
  onQueryChange,
}: {
  readonly appId: number;
  readonly instanceId: number;
  readonly commandId: string;
  readonly search: DumpSearch;
  readonly onQueryChange: (query: string) => void;
}) {
  const { t } = useTranslation(['common', 'commands']);
  const client = useQueryClient();
  const command = useCommand(commandId);
  const view = command.data;
  const done = view?.status === 'done';
  const result = useCommandResult(commandId, 'json', done);
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  const download = useCallback(() => {
    setDownloading(true);
    setDownloadError(null);
    // The text form once per dump (results never change; every read is audited).
    const cached = client.getQueryData<CommandResultView>(commandKeys.result(commandId, 'text'));
    (cached === undefined ? getCommandResult(commandId, 'text') : Promise.resolve(cached))
      .then((text) => {
        client.setQueryData(commandKeys.result(commandId, 'text'), text);
        saveDumpText(text.text ?? '', instanceId, view?.finished_at ?? null);
      })
      .catch((error: unknown) => {
        setDownloadError(commandErrorText(t, error));
      })
      .finally(() => {
        setDownloading(false);
      });
  }, [client, commandId, instanceId, view?.finished_at, t]);

  if (command.isError) {
    return (
      <div data-testid="dump-selected" data-state="error">
        <p
          className="cmd-feedback cmd-feedback--error"
          role="alert"
          data-testid="dump-selected-error"
        >
          {commandErrorText(t, command.error)}
        </p>
      </div>
    );
  }
  if (view === undefined) {
    return <LoadingState variant="block" slowHint="wait" data-testid="dump-selected-loading" />;
  }
  if (view.type !== 'thread_dump') {
    return (
      <p className="cmd-notice" data-testid="dump-selected-not-dump">
        {t('commands:dumps.notDump')}
      </p>
    );
  }
  if (!done) {
    return (
      <div data-testid="dump-selected" data-state={view.status}>
        <CommandFeedback
          view={view}
          requestError={null}
          pollError={null}
          requesting={false}
          data-testid="dump-selected-feedback"
        />
      </div>
    );
  }
  if (result.isPending) {
    return <LoadingState variant="block" slowHint="wait" data-testid="dump-selected-loading" />;
  }
  if (result.isError) {
    return (
      <ErrorState
        compact
        error={errorInfo(result.error)}
        onRetry={() => {
          void result.refetch();
        }}
        data-testid="dump-selected-error"
      />
    );
  }
  return (
    <div data-testid="dump-selected" data-state="done">
      <ThreadDumpViewer
        appId={appId}
        result={result.data}
        search={search}
        onQueryChange={onQueryChange}
        onDownload={download}
        downloading={downloading}
        downloadError={downloadError}
      />
    </div>
  );
}
