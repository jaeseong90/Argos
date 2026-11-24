// SPDX-License-Identifier: Apache-2.0
//
// Deletion request of `/admin/privacy` (docs/15 §9.10 "삭제 요청 폼(user_id 또는 IP + 기간)",
// 05 §2.11-9, docs/14 §4.14, docs/16 §6.4): the subject kind (user id or client address), the
// value, the application (or every one) and the range (at most 30 days), checked before anything is
// sent; a confirmation that says rollups are not changed; then the job is queued and followed.
//
// The value (SEC-33): kept in this form's state only while it is typed, sent once and cleared as
// soon as the server accepts the request; it is never put into the URL, a query key, a log or the
// confirmation text (the screen follows the job by its id).
import { useId, useState } from 'react';
import { useTranslation } from 'react-i18next';

import {
  deletionErrorKind,
  deletionFormProblem,
  DELETION_RANGE_MAX_MS,
  requestDeletion,
  type DeletionAccepted,
} from '../../../api/admin/privacy';
import type { App } from '../../../api/apps';
import { errorInfo } from '../../../api/errors';
import { ErrorState } from '../../../components/ErrorState';
import { appOptionText } from '../common/AdminAppPicker';
import { ConfirmDialog } from '../common/ConfirmDialog';

import { deletionErrorLabel, formProblemLabel } from './labels';

export interface DeletionFormProps {
  readonly apps: readonly App[];
  readonly defaultAppId: number | null;
  readonly onQueued: (accepted: DeletionAccepted) => void;
}

const MINUTE = 60_000;
const DAY = 24 * 60 * MINUTE;

/** `yyyy-MM-ddTHH:mm` of an instant in the browser time zone (datetime-local value). */
export function toLocalInput(ms: number): string {
  const date = new Date(ms);
  const pad = (n: number): string => String(n).padStart(2, '0');
  return `${String(date.getFullYear())}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`;
}

/** Epoch ms of a datetime-local value (browser time zone), or null. */
export function fromLocalInput(value: string): number | null {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/.test(value)) {
    return null;
  }
  const ms = new Date(value).getTime();
  return Number.isNaN(ms) ? null : ms;
}

export function DeletionForm({ apps, defaultAppId, onQueued }: DeletionFormProps) {
  const { t } = useTranslation(['common', 'admin']);
  const valueId = useId();
  const [kind, setKind] = useState<'user_id' | 'ip'>('user_id');
  const [value, setValue] = useState('');
  const [appId, setAppId] = useState<number | null>(defaultAppId);
  const [initial] = useState(() => {
    const to = Math.floor(Date.now() / MINUTE) * MINUTE + MINUTE;
    return { from: toLocalInput(to - 7 * DAY), to: toLocalInput(to) };
  });
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);
  const [confirming, setConfirming] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const fromMs = fromLocalInput(from);
  const toMs = fromLocalInput(to);
  const problem = deletionFormProblem({ kind, value, appId, from: fromMs, to: toMs });

  const submit = async (): Promise<void> => {
    if (problem !== null || fromMs === null || toMs === null) {
      return;
    }
    setPending(true);
    setError(null);
    try {
      const accepted = await requestDeletion({
        kind,
        value: value.trim(),
        from: fromMs,
        to: toMs,
        appId,
      });
      // SEC-33: the subject leaves the client state as soon as the server has it.
      setValue('');
      setConfirming(false);
      onQueued(accepted);
    } catch (caught) {
      setError(caught);
      setConfirming(false);
    } finally {
      setPending(false);
    }
  };

  return (
    <section
      className="admin-panel"
      aria-labelledby="admin-privacy-delete-title"
      data-testid="admin-privacy-delete"
    >
      <h2 id="admin-privacy-delete-title" className="admin-panel__title">
        {t('admin:privacy.delete.title')}
      </h2>
      <p className="admin-note">{t('admin:privacy.delete.help')}</p>
      <div className="admin-toolbar">
        <label className="admin-field admin-field--inline">
          <span className="admin-field__label">{t('admin:privacy.delete.kind')}</span>
          <select
            className="admin-select"
            value={kind}
            onChange={(event) => {
              setKind(event.target.value === 'ip' ? 'ip' : 'user_id');
            }}
            data-testid="admin-privacy-delete-kind"
          >
            <option value="user_id">{t('admin:privacy.delete.kindUserId')}</option>
            <option value="ip">{t('admin:privacy.delete.kindIp')}</option>
          </select>
        </label>
        <span className="admin-field admin-field--inline">
          <label className="admin-field__label" htmlFor={valueId}>
            {kind === 'ip'
              ? t('admin:privacy.delete.valueIp')
              : t('admin:privacy.delete.valueUserId')}
          </label>
          <input
            id={valueId}
            className="admin-input admin-input--mono"
            type="text"
            autoComplete="off"
            spellCheck={false}
            value={value}
            onChange={(event) => {
              setValue(event.target.value);
            }}
            data-testid="admin-privacy-delete-value"
          />
        </span>
        <label className="admin-field admin-field--inline">
          <span className="admin-field__label">{t('admin:privacy.delete.app')}</span>
          <select
            className="admin-select"
            value={appId === null ? '' : String(appId)}
            onChange={(event) => {
              const next = Number(event.target.value);
              setAppId(Number.isInteger(next) && next > 0 ? next : null);
            }}
            data-testid="admin-privacy-delete-app"
          >
            <option value="">{t('admin:privacy.delete.allApps')}</option>
            {apps.map((app) => (
              <option key={app.app_id} value={String(app.app_id)}>
                {appOptionText(app)}
              </option>
            ))}
          </select>
        </label>
        <label className="admin-field admin-field--inline">
          <span className="admin-field__label">{t('admin:privacy.delete.from')}</span>
          <input
            className="admin-input"
            type="datetime-local"
            value={from}
            onChange={(event) => {
              setFrom(event.target.value);
            }}
            data-testid="admin-privacy-delete-from"
          />
        </label>
        <label className="admin-field admin-field--inline">
          <span className="admin-field__label">{t('admin:privacy.delete.to')}</span>
          <input
            className="admin-input"
            type="datetime-local"
            value={to}
            onChange={(event) => {
              setTo(event.target.value);
            }}
            data-testid="admin-privacy-delete-to"
          />
        </label>
      </div>
      <p className="admin-field__help">
        {t('admin:privacy.delete.rangeHelp', { days: DELETION_RANGE_MAX_MS / DAY })}
      </p>
      <div className="admin-actions">
        <button
          type="button"
          className="control-button control-button--primary"
          disabled={problem !== null || pending}
          onClick={() => {
            setError(null);
            setConfirming(true);
          }}
          data-testid="admin-privacy-delete-submit"
        >
          {t('admin:privacy.delete.submit')}
        </button>
        {problem === null ? null : (
          <span
            className="admin-muted"
            data-testid="admin-privacy-delete-problem"
            data-problem={problem}
          >
            {formProblemLabel(t, problem)}
          </span>
        )}
      </div>
      {error === null ? null : deletionErrorKind(error) === 'other' ? (
        <ErrorState compact error={errorInfo(error)} data-testid="admin-privacy-delete-error" />
      ) : (
        <p
          className="admin-error"
          role="alert"
          data-testid="admin-privacy-delete-error"
          data-kind={deletionErrorKind(error)}
        >
          {deletionErrorLabel(t, deletionErrorKind(error))}
        </p>
      )}
      <ConfirmDialog
        open={confirming}
        title={t('admin:privacy.delete.confirmTitle')}
        impact={t('admin:privacy.delete.confirmImpact', {
          kind:
            kind === 'ip' ? t('admin:privacy.delete.kindIp') : t('admin:privacy.delete.kindUserId'),
        })}
        okLabel={t('admin:privacy.delete.confirmOk')}
        pending={pending}
        onOk={() => {
          void submit();
        }}
        onCancel={() => {
          setConfirming(false);
        }}
      />
    </section>
  );
}
