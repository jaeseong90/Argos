// SPDX-License-Identifier: Apache-2.0
//
// One thread's stack (05 §2.6-4 "이 스레드의 스택", T-ACPT-02, docs/14 §4.10 thread_stack, WO-37):
// the view of a thread_stack result (name, state, virtual thread, the lock it waits for and the
// locks it holds, up to 64 frames, and whether the thread still runs the transaction asked for),
// and the modal of the active transactions screen that follows the command (polled every second,
// ADR-0010 C-231 (3)) until the stack arrives. Collected strings render as text only (SEC-60).
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

import { useCommand, useCommandResult, type CommandResultView } from '../../../api/commands';
import { errorInfo } from '../../../api/errors';
import { CodeBlock } from '../../../components/CodeBlock';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';
import { SafeText } from '../../../components/SafeText';
import { CommandDialog } from '../commands/CommandDialog';
import { CommandFeedback } from '../commands/CommandFeedback';

import { dumpThreads, MAX_DUMP_FRAMES, threadOfTransaction, type DumpThread } from './dumpModel';
import { transactionHref } from './links';

import './dumps.css';

export interface ThreadStackViewProps {
  readonly result: Pick<CommandResultView, 'threads' | 'truncated' | 'text'>;
  /** The transaction the stack was asked for (args.tx_id), null when asked by thread id. */
  readonly txId: string | null;
  /** The application of the transaction link; null hides the link. */
  readonly appId: number | null;
}

/** The thread of a stack result: the one of the transaction, else the first. */
export function stackThread(
  result: Pick<CommandResultView, 'threads'>,
  txId: string | null,
): DumpThread | null {
  const threads = dumpThreads(result);
  if (txId !== null) {
    const match = threadOfTransaction(threads, txId);
    if (match !== null) {
      return match;
    }
  }
  return threads[0] ?? null;
}

export function ThreadStackView({ result, txId, appId }: ThreadStackViewProps) {
  const { t } = useTranslation(['common', 'commands']);
  const thread = stackThread(result, txId);
  if (thread === null) {
    // No parsable thread: the agent's text as it came (or nothing).
    return result.text === null ? (
      <p className="cmd-muted" data-testid="thread-stack-none">
        {t('commands:stack.none')}
      </p>
    ) : (
      <CodeBlock value={result.text} language="stack" label={t('commands:stack.title')} />
    );
  }
  const match =
    txId === null
      ? 'none'
      : thread.txId === txId
        ? 'match'
        : thread.txId === null
          ? 'unknown'
          : 'other';
  return (
    <div
      className="dump-stack"
      data-testid="thread-stack"
      data-thread-id={thread.id ?? undefined}
      data-state={thread.group}
      data-tx-match={match}
      data-virtual={String(thread.virtual)}
    >
      <div className="dump-stack__head">
        <SafeText value={thread.name} mono data-testid="thread-stack-name" />
        <span className={`dump-state dump-state--${thread.group}`} data-testid="thread-stack-state">
          {thread.state === null ? (
            t('commands:viewer.state.OTHER')
          ) : (
            <SafeText value={thread.state} />
          )}
        </span>
        {thread.virtual ? (
          <span className="cmd-badge cmd-badge--info" title={t('commands:viewer.virtualTitle')}>
            {t('commands:viewer.virtual')}
          </span>
        ) : null}
        {thread.id === null ? null : (
          <span className="cmd-muted">{t('commands:viewer.threadId', { id: thread.id })}</span>
        )}
      </div>
      {match === 'none' ? null : (
        <p
          className={`dump-stack__match dump-stack__match--${match}`}
          data-testid="thread-stack-match"
        >
          {t(`commands:stack.match.${match}`, { txId })}{' '}
          {match === 'match' && appId !== null && txId !== null ? (
            <Link to={transactionHref(appId, txId)} data-testid="thread-stack-tx-link">
              {t('commands:viewer.openTransaction')}
            </Link>
          ) : null}
        </p>
      )}
      {thread.waitingOn === null ? null : (
        <p className="dump-stack__lock">
          <span className="cmd-muted">{t('commands:viewer.waitingOn')}</span>{' '}
          <SafeText value={thread.waitingOn} mono />
        </p>
      )}
      {thread.locksHeld.length === 0 ? null : (
        <div className="dump-stack__lock">
          <span className="cmd-muted">{t('commands:viewer.locksHeld')}</span>
          <ul className="dump-locks">
            {thread.locksHeld.map((lock, index) => (
              <li key={`${lock}#${String(index)}`}>
                <SafeText value={lock} mono />
              </li>
            ))}
          </ul>
        </div>
      )}
      <CodeBlock
        value={thread.frames.join('\n')}
        language="stack"
        foldLines={0}
        label={t('commands:stack.frames', { count: thread.frames.length })}
        data-testid="thread-stack-frames"
      />
      {thread.depthCut || result.truncated ? (
        <p className="cmd-notice" data-testid="thread-stack-truncated">
          {t('commands:stack.depthCut', { count: MAX_DUMP_FRAMES })}
        </p>
      ) : null}
    </div>
  );
}

export interface ThreadStackModalProps {
  readonly appId: number;
  /** The thread_stack command to follow; null closes the modal. */
  readonly commandId: string | null;
  readonly txId?: string | null;
  /** The request error when the command could not be queued. */
  readonly requestError?: unknown;
  readonly requesting?: boolean;
  readonly open?: boolean;
  readonly onClose: () => void;
}

export function ThreadStackModal({
  appId,
  commandId,
  txId = null,
  requestError = null,
  requesting = false,
  open,
  onClose,
}: ThreadStackModalProps) {
  const { t } = useTranslation(['common', 'commands']);
  const command = useCommand(commandId);
  const view = command.data;
  const done = view?.status === 'done';
  const result = useCommandResult(commandId, 'json', done);
  const isOpen = open ?? commandId !== null;

  let body;
  if (done && result.isPending) {
    body = <LoadingState variant="block" slowHint="wait" />;
  } else if (done && result.isError) {
    body = (
      <ErrorState
        compact
        error={errorInfo(result.error)}
        onRetry={() => {
          void result.refetch();
        }}
      />
    );
  } else if (done && result.data !== undefined) {
    body = <ThreadStackView result={result.data} txId={txId} appId={appId} />;
  } else {
    body = (
      <CommandFeedback
        view={view}
        requestError={requestError}
        pollError={command.error}
        requesting={requesting}
        data-testid="thread-stack-feedback"
      />
    );
  }
  return (
    <CommandDialog
      open={isOpen}
      title={t('commands:stack.title')}
      onClose={onClose}
      size="wide"
      data-testid="thread-stack-modal"
      footer={
        <button
          type="button"
          className="control-button"
          onClick={onClose}
          data-testid="thread-stack-close"
        >
          {t('commands:result.close')}
        </button>
      }
    >
      <div data-testid="thread-stack-body" data-state={done ? 'done' : (view?.status ?? 'pending')}>
        {body}
      </div>
    </CommandDialog>
  );
}
