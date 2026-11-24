// SPDX-License-Identifier: Apache-2.0
//
// A secret shown once (docs/16 §2.1, SEC-02, 05 §2.11-4, WO-39 §1.4 `admin-token-once`): an agent
// token after issue, rotation or approval, or a user's initial password. The value lives only in
// the parent's state while this dialog is open; the parent drops it on close, so afterwards it is in
// no DOM node, query or mutation cache, browser storage or URL. The server cannot show it again.
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { useFormat } from '../../../i18n/format';
import { AlertDialog } from '../../alerts/common/AlertDialog';

import './admin.css';

export interface OnceSecret {
  readonly kind: 'token' | 'password';
  readonly value: string;
  /** Token expiry (ISO), null for none. */
  readonly expiresAt?: string | null;
  /** Rotation: the previous token keeps working until this instant (ISO). */
  readonly previousValidUntil?: string | null;
}

export interface OnceSecretDialogProps {
  readonly secret: OnceSecret | null;
  readonly onClose: () => void;
}

function timeOf(iso: string | null | undefined): number {
  return iso === null || iso === undefined ? Number.NaN : Date.parse(iso);
}

export function OnceSecretDialog({ secret, onClose }: OnceSecretDialogProps) {
  const { t } = useTranslation(['common', 'admin']);
  const format = useFormat();
  const [copied, setCopied] = useState<'idle' | 'done' | 'failed'>('idle');
  if (secret === null) {
    return null;
  }
  const close = (): void => {
    setCopied('idle');
    onClose();
  };
  const expires = timeOf(secret.expiresAt);
  const previous = timeOf(secret.previousValidUntil);
  const isToken = secret.kind === 'token';
  return (
    <AlertDialog
      open
      size="narrow"
      title={isToken ? t('admin:common.once.tokenTitle') : t('admin:common.once.passwordTitle')}
      onClose={close}
      data-testid="admin-token-once"
      footer={
        <button
          type="button"
          className="control-button control-button--primary"
          onClick={close}
          data-testid="admin-token-once-close"
        >
          {t('admin:common.once.close')}
        </button>
      }
    >
      <p className="admin-warning" data-testid="admin-token-once-notice">
        {t('admin:common.once.notice')}
      </p>
      <input
        className="admin-input admin-secret"
        type="text"
        readOnly
        autoComplete="off"
        spellCheck={false}
        value={secret.value}
        aria-label={
          isToken ? t('admin:common.once.tokenLabel') : t('admin:common.once.passwordLabel')
        }
        onFocus={(event) => {
          event.currentTarget.select();
        }}
        data-testid="admin-token-once-value"
      />
      <div className="admin-toolbar">
        <button
          type="button"
          className="control-button"
          onClick={() => {
            const clipboard = typeof navigator === 'undefined' ? undefined : navigator.clipboard;
            if (clipboard === undefined) {
              setCopied('failed');
              return;
            }
            clipboard.writeText(secret.value).then(
              () => {
                setCopied('done');
              },
              () => {
                setCopied('failed');
              },
            );
          }}
          data-testid="admin-token-once-copy"
        >
          {t('admin:common.once.copy')}
        </button>
        <span className="admin-muted" role="status">
          {copied === 'done'
            ? t('admin:common.once.copied')
            : copied === 'failed'
              ? t('admin:common.once.copyFailed')
              : null}
        </span>
      </div>
      {isToken ? (
        <p className="admin-note" data-testid="admin-token-once-expiry">
          {Number.isNaN(expires)
            ? t('admin:common.once.noExpiry')
            : t('admin:common.once.expires', { time: format.dateTime(expires) })}
        </p>
      ) : (
        <p className="admin-note">{t('admin:common.once.passwordChange')}</p>
      )}
      {Number.isNaN(previous) ? null : (
        <p className="admin-note" data-testid="admin-token-once-previous">
          {t('admin:common.once.previousValid', { time: format.dateTime(previous) })}
        </p>
      )}
      {isToken ? <p className="admin-note">{t('admin:common.once.store')}</p> : null}
    </AlertDialog>
  );
}
