// SPDX-License-Identifier: Apache-2.0
//
// A command button with its confirmation (05 §2.7-4, docs/15 §9.8): the dialog says what the
// command does to the instance and who can read its result; thread_stack asks for a transaction id
// or a thread id (digits, docs/14 §4.10). Confirming requests the command; the caller follows it
// through the shared CommandRun.
import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';

import type { CommandType } from '../../../api/commands';

import { CommandDialog } from './CommandDialog';
import type { CommandRun } from './useCommands';

const DIGITS = /^[0-9]{1,20}$/;

/** The two ways to name the thread of a single-thread stack (docs/14 §4.10 args). */
const STACK_ARGS = ['tx_id', 'thread_id'] as const;

export interface RunCommandButtonProps {
  readonly type: CommandType;
  readonly runner: CommandRun;
  /** Label of the button (default: the command's name). */
  readonly label?: string;
  readonly disabled?: boolean;
  /** Called with the new command id after a successful request. */
  readonly onQueued?: (commandId: string) => void;
}

export function RunCommandButton({
  type,
  runner,
  label,
  disabled = false,
  onQueued,
}: RunCommandButtonProps) {
  const { t } = useTranslation(['common', 'commands']);
  const [open, setOpen] = useState(false);
  const [argKind, setArgKind] = useState<'tx_id' | 'thread_id'>('tx_id');
  const [argValue, setArgValue] = useState('');
  const fieldId = useId();
  const errorId = useId();
  const needsArg = type === 'thread_stack';
  const argValid = !needsArg || DIGITS.test(argValue.trim());
  const busy = runner.requesting || runner.inFlight;

  const confirm = async (): Promise<void> => {
    if (!argValid) {
      return;
    }
    const id = await runner.run(type, needsArg ? { [argKind]: argValue.trim() } : undefined);
    setOpen(false);
    if (id !== null) {
      onQueued?.(id);
    }
  };

  return (
    <>
      <button
        type="button"
        className="control-button"
        disabled={disabled || busy}
        onClick={() => {
          setOpen(true);
        }}
        data-testid={`command-run-${type}`}
      >
        {label ?? t(`commands:type.${type}`)}
      </button>
      <CommandDialog
        open={open}
        title={t('commands:dialog.title', { command: t(`commands:type.${type}`) })}
        onClose={() => {
          setOpen(false);
        }}
        data-testid={`command-dialog-${type}`}
        footer={
          <>
            <button
              type="button"
              className="control-button"
              onClick={() => {
                setOpen(false);
              }}
              data-testid="command-dialog-cancel"
            >
              {t('commands:dialog.cancel')}
            </button>
            <button
              type="button"
              className="control-button control-button--primary"
              disabled={!argValid || runner.requesting}
              onClick={() => {
                void confirm();
              }}
              data-testid="command-dialog-confirm"
            >
              {t('commands:dialog.confirm')}
            </button>
          </>
        }
      >
        <p data-testid="command-dialog-impact">{t(`commands:impact.${type}`)}</p>
        <p className="cmd-muted">{t(`commands:visibility.${type}`)}</p>
        {needsArg ? (
          <div className="cmd-field">
            <fieldset className="cmd-field__kinds">
              <legend>{t('commands:dialog.stackTarget')}</legend>
              {STACK_ARGS.map((kind) => (
                <label key={kind} className="cmd-field__kind">
                  <input
                    type="radio"
                    name={`${fieldId}-kind`}
                    value={kind}
                    checked={argKind === kind}
                    onChange={() => {
                      setArgKind(kind);
                    }}
                    data-testid={`command-dialog-kind-${kind}`}
                  />
                  {t(`commands:dialog.${kind}`)}
                </label>
              ))}
            </fieldset>
            <label htmlFor={fieldId}>{t(`commands:dialog.${argKind}`)}</label>
            <input
              id={fieldId}
              className="cmd-input"
              inputMode="numeric"
              autoComplete="off"
              maxLength={20}
              value={argValue}
              aria-invalid={argValue !== '' && !argValid}
              aria-describedby={errorId}
              onChange={(event) => {
                setArgValue(event.target.value);
              }}
              onKeyDown={(event) => {
                if (event.key === 'Enter') {
                  event.preventDefault();
                  void confirm();
                }
              }}
              data-testid="command-dialog-arg"
            />
            <span id={errorId} className="cmd-muted">
              {argValue !== '' && !argValid
                ? t('commands:dialog.digitsOnly')
                : t('commands:dialog.argHint')}
            </span>
          </div>
        ) : null}
      </CommandDialog>
    </>
  );
}
