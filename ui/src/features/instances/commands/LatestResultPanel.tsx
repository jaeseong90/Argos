// SPDX-License-Identifier: Apache-2.0
//
// The latest environment dump or self diagnosis of an instance, for the environment tab (05 §2.7-3,
// docs/15 §9.8, WO-37): the newest done command of the type that the signed-in user may read
// (their own, or any for an administrator; docs/14 §4.10, SEC-50), with who ran it and when, and a
// button to run it again. The environment dump (environment variables, system properties and JVM
// arguments) is for administrators only (SEC-21; PM question 2 of the plan: JVM options come only
// from it); other roles see why. A newer result of another user is mentioned, not shown.
//
// With `reveal` the result is read only after the user presses "Show latest …" (ADR-0010 C-234
// (3)): reading a result is audited (COMMAND_RESULT_VIEWED), so opening the tab must not read it.
import { useId } from 'react';
import { useTranslation } from 'react-i18next';

import { requesterName, useCommandResult } from '../../../api/commands';
import { errorInfo } from '../../../api/errors';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingState } from '../../../components/LoadingState';
import { SafeText } from '../../../components/SafeText';
import { useFormat } from '../../../i18n/format';

import { CommandFeedback } from './CommandFeedback';
import { EnvDumpView, SelfDiagnosisView } from './ResultViews';
import { RunCommandButton } from './RunCommandButton';
import { useCommandAccess, useCommandRun, useLatestCommand } from './useCommands';

import './commands.css';

/** Read the result only on request: `revealed` is false until the user asks (`onReveal`). */
export interface RevealOnRequest {
  readonly revealed: boolean;
  readonly onReveal: () => void;
}

export interface LatestResultPanelProps {
  readonly appId: number;
  readonly instanceId: number;
  readonly type: 'env_dump' | 'self_diagnosis';
  /** Without it the newest result is read at once. */
  readonly reveal?: RevealOnRequest;
}

export function LatestResultPanel({ instanceId, type, reveal }: LatestResultPanelProps) {
  const { t } = useTranslation(['common', 'commands']);
  const format = useFormat();
  const headingId = useId();
  const access = useCommandAccess();
  const allowed = type === 'env_dump' ? access.isAdmin : access.canList;
  const testId = `latest-${type}`;

  if (!allowed) {
    return (
      <section
        className="cmd-panel"
        aria-labelledby={headingId}
        data-testid={testId}
        data-state="forbidden"
      >
        <h3 id={headingId} className="cmd-panel__title">
          {t(`commands:latest.title.${type}`)}
        </h3>
        <p className="cmd-notice" data-testid={`${testId}-forbidden`}>
          {type === 'env_dump' ? t('commands:latest.adminOnly') : t('commands:latest.operatorOnly')}
        </p>
      </section>
    );
  }
  return (
    <LatestResultBody
      instanceId={instanceId}
      type={type}
      headingId={headingId}
      testId={testId}
      reveal={reveal}
      formatDate={(iso) => {
        const ms = Date.parse(iso);
        return Number.isNaN(ms) ? iso : format.dateTimeWithOffset(ms);
      }}
    />
  );
}

function LatestResultBody({
  instanceId,
  type,
  headingId,
  testId,
  reveal,
  formatDate,
}: {
  readonly instanceId: number;
  readonly type: 'env_dump' | 'self_diagnosis';
  readonly headingId: string;
  readonly testId: string;
  readonly reveal: RevealOnRequest | undefined;
  readonly formatDate: (iso: string) => string;
}) {
  const { t } = useTranslation(['common', 'commands']);
  const access = useCommandAccess();
  const latest = useLatestCommand(instanceId, type);
  const runner = useCommandRun(instanceId);
  const commandId = latest.latest?.command_id ?? null;
  const hidden = reveal !== undefined && !reveal.revealed;
  const result = useCommandResult(commandId, 'json', !hidden);
  const name = latest.latest === null ? null : requesterName(latest.latest);

  let body;
  let state: string;
  if (latest.loading) {
    state = 'loading';
    body = <LoadingState variant="block" slowHint="wait" />;
  } else if (latest.error !== null) {
    state = 'error';
    body = <ErrorState compact error={errorInfo(latest.error)} onRetry={latest.refetch} />;
  } else if (latest.latest === null) {
    state = 'empty';
    body = (
      <p className="cmd-muted" data-testid={`${testId}-empty`}>
        {t(`commands:latest.empty.${type}`)}
      </p>
    );
  } else if (hidden) {
    state = 'hidden';
    body = (
      <p className="cmd-muted" data-testid={`${testId}-hidden`}>
        <button
          type="button"
          className="instance-link-button"
          onClick={reveal.onReveal}
          data-testid={`${testId}-show`}
        >
          {t(`commands:latest.show.${type}`)}
        </button>{' '}
        {t('commands:latest.showHint')}
      </p>
    );
  } else if (result.isPending) {
    state = 'loading';
    body = <LoadingState variant="block" slowHint="wait" />;
  } else if (result.isError) {
    state = 'error';
    body = (
      <ErrorState
        compact
        error={errorInfo(result.error)}
        onRetry={() => {
          void result.refetch();
        }}
      />
    );
  } else {
    state = 'ready';
    body =
      type === 'env_dump' ? (
        <EnvDumpView result={result.data} />
      ) : (
        <SelfDiagnosisView result={result.data} />
      );
  }

  return (
    <section
      className="cmd-panel"
      aria-labelledby={headingId}
      data-testid={testId}
      data-state={state}
      data-command-id={commandId ?? undefined}
    >
      <div className="cmd-panel__head">
        <h3 id={headingId} className="cmd-panel__title">
          {t(`commands:latest.title.${type}`)}
        </h3>
        {latest.latest === null || latest.latest.finished_at === null ? null : (
          <span className="cmd-muted" data-testid={`${testId}-meta`}>
            {t('commands:latest.asOf', { at: formatDate(latest.latest.finished_at) })}{' '}
            {name === null ? null : (
              <>
                {t('commands:latest.by')} <SafeText value={name} />
              </>
            )}
          </span>
        )}
        {access.canRun(type) ? (
          <RunCommandButton
            type={type}
            runner={runner}
            label={latest.latest === null ? t('commands:latest.run') : t('commands:latest.refresh')}
          />
        ) : null}
      </div>
      {runner.commandId === null && runner.requestError === null ? null : (
        <CommandFeedback
          view={runner.view}
          requestError={runner.requestError}
          pollError={runner.pollError}
          requesting={runner.requesting}
          data-testid={`${testId}-feedback`}
        />
      )}
      {latest.newerUnreadable === null ? null : (
        <p className="cmd-notice" data-testid={`${testId}-newer`}>
          {t('commands:latest.newerUnreadable')}
        </p>
      )}
      {body}
    </section>
  );
}
