// SPDX-License-Identifier: Apache-2.0
//
// "Mute alerts of this target" (05 §2.9-4 "이 대상 알림 일시 중지(점검)"): a one-off mute of the
// row's application, environment, instance and rule from now, at most 30 days (docs/14 §4.13),
// with an optional reason (512 characters, 1 KB). The target fields are shown read-only; the
// application, environment and rule names are collected or entered strings (SafeText).
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import { createAlertMute, MUTE_REASON_MAX_CHARS } from '../../../api/alerts';
import { errorInfo } from '../../../api/errors';
import { queryKeys } from '../../../api/queryKeys';
import type { Untrusted } from '../../../api/untrusted';
import { ErrorState } from '../../../components/ErrorState';
import { SafeText } from '../../../components/SafeText';
import { MISSING_DASH } from '../../../utils/missing';
import { AlertDialog } from '../common/AlertDialog';

import { alertTextFits, MUTE_DURATIONS_H, muteRequest, type MuteTarget } from './model';

export interface MuteDialogTarget {
  readonly key: string;
  readonly target: MuteTarget;
  readonly appName: Untrusted | null;
  readonly instanceName: Untrusted | null;
  readonly ruleName: Untrusted | null;
}

export interface MuteDialogProps {
  readonly subject: MuteDialogTarget | null;
  readonly onClose: () => void;
  /** Clock of the request (tests). */
  readonly now?: () => number;
}

export function MuteDialog({ subject, onClose, now = Date.now }: MuteDialogProps) {
  return subject === null ? null : (
    <MuteDialogBody key={subject.key} subject={subject} onClose={onClose} now={now} />
  );
}

function MuteDialogBody({
  subject,
  onClose,
  now,
}: {
  readonly subject: MuteDialogTarget;
  readonly onClose: () => void;
  readonly now: () => number;
}) {
  const { t } = useTranslation(['common', 'alerts']);
  const queryClient = useQueryClient();
  const [hours, setHours] = useState<number>(4);
  const [reason, setReason] = useState('');
  const fits = alertTextFits(reason);
  const mutation = useMutation({
    mutationFn: () => {
      const request = muteRequest(subject.target, hours, reason, now());
      if (request === null) {
        throw new Error('invalid mute duration');
      }
      return createAlertMute(request);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: queryKeys.alerts.mutes() });
      onClose();
    },
  });
  const { target } = subject;
  const durationLabel = (h: number): string =>
    h % 24 === 0
      ? t('alerts:mutes.days', { count: h / 24 })
      : t('alerts:mutes.hours', { count: h });
  return (
    <AlertDialog
      open
      title={t('alerts:mutes.dialogTitle')}
      onClose={onClose}
      data-testid="alerts-mute-modal"
      footer={
        <>
          <button type="button" className="control-button" onClick={onClose}>
            {t('alerts:common.cancel')}
          </button>
          <button
            type="button"
            className="control-button control-button--primary"
            disabled={!fits || mutation.isPending}
            onClick={() => {
              mutation.mutate();
            }}
            data-testid="alerts-mute-submit"
          >
            {t('alerts:mutes.submit')}
          </button>
        </>
      }
    >
      <p className="alerts-field__help">{t('alerts:mutes.dialogHelp')}</p>
      <dl className="alerts-mute-target" data-testid="alerts-mute-target">
        <dt>{t('alerts:mutes.target.app')}</dt>
        <dd data-testid="alerts-mute-target-app" data-value={target.app ?? ''}>
          {subject.appName === null ? (
            (target.app ?? MISSING_DASH)
          ) : (
            <SafeText value={subject.appName} />
          )}
        </dd>
        <dt>{t('alerts:mutes.target.env')}</dt>
        <dd data-testid="alerts-mute-target-env">
          <SafeText value={target.env} />
        </dd>
        <dt>{t('alerts:mutes.target.instance')}</dt>
        <dd data-testid="alerts-mute-target-instance" data-value={target.instance ?? ''}>
          {subject.instanceName === null ? (
            (target.instance ?? t('alerts:mutes.target.anyInstance'))
          ) : (
            <SafeText value={subject.instanceName} />
          )}
        </dd>
        <dt>{t('alerts:mutes.target.rule')}</dt>
        <dd data-testid="alerts-mute-target-rule" data-value={target.rule ?? ''}>
          {subject.ruleName === null ? (
            (target.rule ?? t('alerts:mutes.target.anyRule'))
          ) : (
            <SafeText value={subject.ruleName} />
          )}
        </dd>
      </dl>
      <label className="alerts-field">
        <span className="alerts-field__label">{t('alerts:mutes.duration')}</span>
        <select
          className="alerts-select"
          value={String(hours)}
          onChange={(event) => {
            setHours(Number(event.target.value));
          }}
          data-testid="alerts-mute-duration"
        >
          {MUTE_DURATIONS_H.map((h) => (
            <option key={h} value={String(h)}>
              {durationLabel(h)}
            </option>
          ))}
        </select>
      </label>
      <p className="alerts-field__help">{t('alerts:mutes.maxDuration')}</p>
      <label className="alerts-field">
        <span className="alerts-field__label">{t('alerts:mutes.reason')}</span>
        <textarea
          className="alerts-textarea"
          value={reason}
          maxLength={MUTE_REASON_MAX_CHARS}
          rows={3}
          onChange={(event) => {
            setReason(event.target.value);
          }}
          data-testid="alerts-mute-reason"
        />
      </label>
      {fits ? null : (
        <p className="alerts-field__error" role="alert">
          {t('alerts:mutes.reasonTooLong')}
        </p>
      )}
      {mutation.isError ? (
        <ErrorState compact error={errorInfo(mutation.error)} data-testid="alerts-mute-error" />
      ) : null}
    </AlertDialog>
  );
}
