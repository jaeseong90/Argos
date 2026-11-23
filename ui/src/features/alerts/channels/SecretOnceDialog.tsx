// SPDX-License-Identifier: Apache-2.0
//
// The webhook signing secret, shown once (docs/16 §8.3, SEC-52): after a create without a given
// secret or after a rotation. The value lives only in the parent's state while this dialog is open
// (never in the query cache, storage or the URL) and is dropped when it closes; the server cannot
// show it again. A copy button puts it on the clipboard.
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useFormat } from '../../../i18n/format';
import { AlertDialog } from '../common/AlertDialog';

export interface OneTimeSecret {
  readonly secret: string;
  /** Rotation: the previous key still signs until this instant (ISO). */
  readonly previousValidUntil: string | null;
}

export interface SecretOnceDialogProps {
  readonly value: OneTimeSecret | null;
  readonly onClose: () => void;
}

export function SecretOnceDialog({ value, onClose }: SecretOnceDialogProps) {
  const { t } = useTranslation(['common', 'alerts']);
  const format = useFormat();
  const [copied, setCopied] = useState<'idle' | 'done' | 'failed'>('idle');
  if (value === null) {
    return null;
  }
  const until =
    value.previousValidUntil === null ? Number.NaN : Date.parse(value.previousValidUntil);
  return (
    <AlertDialog
      open
      size="narrow"
      title={t('alerts:channels.secret.title')}
      onClose={() => {
        setCopied('idle');
        onClose();
      }}
      data-testid="alert-channel-secret-once"
      footer={
        <button
          type="button"
          className="control-button control-button--primary"
          onClick={() => {
            setCopied('idle');
            onClose();
          }}
          data-testid="alert-channel-secret-close"
        >
          {t('alerts:channels.secret.close')}
        </button>
      }
    >
      <p className="alerts-field__help">{t('alerts:channels.secret.help')}</p>
      <input
        className="alerts-input alerts-mono"
        type="text"
        readOnly
        value={value.secret}
        aria-label={t('alerts:channels.secret.label')}
        onFocus={(event) => {
          event.currentTarget.select();
        }}
        data-testid="alert-channel-secret-value"
      />
      <button
        type="button"
        className="control-button"
        onClick={() => {
          const clipboard = typeof navigator === 'undefined' ? undefined : navigator.clipboard;
          if (clipboard === undefined) {
            setCopied('failed');
            return;
          }
          clipboard.writeText(value.secret).then(
            () => {
              setCopied('done');
            },
            () => {
              setCopied('failed');
            },
          );
        }}
        data-testid="alert-channel-secret-copy"
      >
        {t('alerts:channels.secret.copy')}
      </button>
      <span className="alerts-muted" role="status">
        {copied === 'done'
          ? t('alerts:channels.secret.copied')
          : copied === 'failed'
            ? t('alerts:channels.secret.copyFailed')
            : null}
      </span>
      {Number.isNaN(until) ? null : (
        <p className="alerts-field__help" data-testid="alert-channel-secret-previous">
          {t('alerts:channels.secret.previousValid', { time: format.dateTime(until) })}
        </p>
      )}
    </AlertDialog>
  );
}
