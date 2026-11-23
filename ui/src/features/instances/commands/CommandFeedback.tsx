// SPDX-License-Identifier: Apache-2.0
//
// The state of a requested command and the errors of a request, in words (05 §2.7-4, docs/14
// §4.10): queued / sent while it runs, done, failed or expired with the reason (ttl, timeout,
// session_ended, rejected, cooldown, agent_failed …), "no answer" when polling gave up, and the
// request errors (instance offline, another command running, rate limited with Retry-After,
// no master key, no permission). A live region announces the changes.
import type { TFunction } from 'i18next';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import {
  COMMAND_STATUSES,
  commandErrorKind,
  endReason,
  type CommandStatus,
  isTerminal,
  pollDeadline,
  type CommandView,
} from '../../../api/commands';
import { isApiError } from '../../../api/errors';

/** The i18n text of a request or read error. */
export function commandErrorText(t: TFunction<['common', 'commands']>, error: unknown): string {
  const kind = commandErrorKind(error);
  if (kind === 'rate_limited' && isApiError(error) && error.retryAfterSeconds !== null) {
    return t('commands:error.rate_limitedWait', { seconds: error.retryAfterSeconds });
  }
  return t(`commands:error.${kind}`);
}

/** The status text of a command (with the end reason of a failed or expired one). */
export function commandStatusText(
  t: TFunction<['common', 'commands']>,
  view: Pick<CommandView, 'status' | 'error'>,
): string {
  const status: CommandStatus | 'unknown' = (COMMAND_STATUSES as readonly string[]).includes(
    view.status,
  )
    ? (view.status as CommandStatus)
    : 'unknown';
  const label = t(`commands:status.${status}`);
  const reason = endReason(view.error);
  if (reason === null || status === 'done') {
    return label;
  }
  return t('commands:status.withReason', { status: label, reason: t(`commands:reason.${reason}`) });
}

/**
 * True once a running command's polling deadline (pollDeadline) has passed: polling has stopped
 * without a final state. A timer re-renders at the deadline.
 */
export function useDeadlinePassed(view: CommandView | undefined): boolean {
  const deadline = view === undefined || isTerminal(view.status) ? null : pollDeadline(view);
  const [passedFor, setPassedFor] = useState<number | null>(null);
  useEffect(() => {
    if (deadline === null) {
      return undefined;
    }
    const id = setTimeout(
      () => {
        setPassedFor(deadline);
      },
      Math.max(0, deadline - Date.now()),
    );
    return () => {
      clearTimeout(id);
    };
  }, [deadline]);
  return deadline !== null && passedFor === deadline;
}

export interface CommandFeedbackProps {
  readonly view: CommandView | undefined;
  readonly requestError: unknown;
  readonly pollError: unknown;
  readonly requesting: boolean;
  readonly 'data-testid'?: string;
}

export function CommandFeedback({
  view,
  requestError,
  pollError,
  requesting,
  'data-testid': testId = 'command-feedback',
}: CommandFeedbackProps) {
  const { t } = useTranslation(['common', 'commands']);
  const passed = useDeadlinePassed(view);
  let text: string | null = null;
  let tone: 'info' | 'error' | 'ok' = 'info';
  let state = 'idle';
  if (requesting) {
    text = t('commands:run.requesting');
    state = 'requesting';
  } else if (requestError !== null && requestError !== undefined) {
    text = commandErrorText(t, requestError);
    tone = 'error';
    state = 'request-error';
  } else if (pollError !== null && pollError !== undefined) {
    text = commandErrorText(t, pollError);
    tone = 'error';
    state = 'poll-error';
  } else if (view !== undefined) {
    state = view.status;
    if (passed) {
      text = t('commands:run.noAnswer');
      tone = 'error';
      state = 'no-answer';
    } else {
      text = commandStatusText(t, view);
      tone = view.status === 'done' ? 'ok' : isTerminal(view.status) ? 'error' : 'info';
    }
  }
  return (
    <p
      className={`cmd-feedback cmd-feedback--${tone}`}
      role="status"
      aria-live="polite"
      data-testid={testId}
      data-state={state}
      data-reason={view?.error ?? undefined}
      data-command-id={view?.command_id}
    >
      {text}
    </p>
  );
}
